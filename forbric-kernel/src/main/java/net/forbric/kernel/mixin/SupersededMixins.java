/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.mixin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * Guest mixins whose job the kernel has taken over, so their failure is not the mod's failure.
 *
 * <p>A mixin that cannot apply normally means a mod lost something, and the Mods screen says so. Sometimes it
 * means the opposite: the kernel could not make the mixin fit, looked at what it was for, and did that job
 * itself somewhere else. Marking the mod then reports a loss that did not happen — and a report that cries wolf
 * is worse than no report, because the next real one is read the same way.
 *
 * <p>The bar for an entry here is deliberately high. It is not "the kernel has something similar"; it is that a
 * kernel repair does <b>everything this mixin class does</b>, and was written for exactly this failure. A mixin class
 * with a second injection the kernel does not replace does not belong here, because suppressing the mark would hide
 * that half.
 *
 * <p>Which mixins a repair takes over is read from the mixin itself, never from its name: each repair carries a claim —
 * a structural test of what every one of the mixin's injectors does — and config discovery hands every guest mixin to
 * {@link #noteMixin}. A mixin no claim fits is never superseded, whatever it is called; one that fits is, whatever it
 * is called.
 *
 * <p>A claim is still only a CLAIM, and a claim resolves nothing on its own. The failure is recorded like any other;
 * it is resolved only when the class that carries the replacement is defined with the replacement actually in its
 * bytes, and only while the replacement's own switch is on. Resolving on the claim alone let a switched-off or
 * never-installed repair report the loss as handled.
 *
 * <p>{@code -Dforbric.supersededMixins=off} marks them like any other failure, which is how the claim in each
 * entry can be checked against what the game actually does.
 */
public final class SupersededMixins {
	static final String PROPERTY = "forbric.supersededMixins";

	/**
	 * One replacement: the words the log uses, the switch that turns the repair off, where it is seen -- the class that
	 * carries it and the structural test its defined bytes must pass -- and which mixins it takes over: the structural
	 * test a mixin must pass, given a reader of the classes its handlers call.
	 */
	private record Replacement(String kind, String words, String switchProperty, String witnessClass, Predicate<ClassNode> witness,
			java.util.function.BiPredicate<ClassNode, java.util.function.Function<String, byte[]>> claims) {
		boolean switchedOn() {
			return !"off".equalsIgnoreCase(System.getProperty(switchProperty, "on"));
		}
	}

	private static final String HOPPER = "net/minecraft/world/level/block/entity/HopperBlockEntity";
	private static final String JSON_READER = "net/minecraft/server/packs/resources/SimpleJsonResourceReloadListener";
	/** The JSON key the kernel's funnel (KernelFabricConditions) evaluates: Fabric's public data contract for conditions. */
	private static final String CONDITIONS_KEY = "fabric:load_conditions";

	/** The kernel repairs that take a guest mixin's whole job over, each with its claim. */
	private static final List<Replacement> KINDS = List.of(
			// A closed decode guard on the JSON reader: a @WrapOperation of Codec.parse that answers its own private sentinel
			// or the original parse, and an @Inject that cancels the consumer on that sentinel — the guard judging
			// fabric:load_conditions (its evaluator's class holds that key). NeoForge's patch of scanDirectory made the value
			// Optional and reordered the lambda's captures, so neither half may apply; KernelFabricConditions does both jobs
			// one level down, on ConditionalOps' own funnel, which covers every consumer rather than this one. Seen when
			// ConditionalOps is defined with KernelFabricConditions.alsoAskFabric wrapping its factory's single exit
			// (ForbricMergedBaseCompatTransformer.letFabricResourceConditionsDecide), while -Dforbric.fabricConditions is not
			// off -- off makes alsoAskFabric hand the codec back untouched.
			new Replacement("resource-conditions", "the kernel evaluates fabric:load_conditions at ConditionalOps' funnel instead "
					+ "(KernelFabricConditions), which covers every consumer rather than this one call site",
					"forbric.fabricConditions", "net.neoforged.neoforge.common.conditions.ConditionalOps",
					SupersededMixins::conditionalOpsAsksFabric, SupersededMixins::judgesResourceConditions),
			// A hopper mixin whose every injector asks the public ItemStorage.SIDED lookup (fabric-transfer-api's asks it after
			// getAttachedContainer in ejectItems and after getSourceContainer in suckInItems). NeoForge's hopper calls neither.
			// KernelFabricHopperStorage runs the same lookup, faces and one-item move on NeoForge's two found-nothing branches.
			// Seen when HopperBlockEntity is defined with both calls in place, while -Dforbric.hopperFabricStorage is not off.
			new Replacement("hopper-storage", "the kernel runs Fabric's hopper storage lookup on NeoForge's found-nothing branches of "
					+ "ejectItems and suckInItems instead (KernelFabricHopperStorage)",
					"forbric.hopperFabricStorage", "net.minecraft.world.level.block.entity.HopperBlockEntity",
					SupersededMixins::hopperAsksFabric, (mixin, classes) -> asksSidedItemStorage(mixin)));

	/** Mixin class (binary name) → the kind of replacement whose claim it fits, as config discovery noted it. */
	private static final Map<String, String> CLAIMS = new ConcurrentHashMap<>();
	/** Failures recorded while their replacement was not yet proved: mixin → configs. */
	private static final Map<String, Set<String>> PENDING = new ConcurrentHashMap<>();
	private static final Set<String> PROVED = ConcurrentHashMap.newKeySet();

	private SupersededMixins() {
	}

	/**
	 * Hands config discovery's view of a guest mixin to the claims: when one fits, the mixin is that repair's to supersede.
	 * {@code classes} reads a class by resource path, for a claim that looks into what the handlers call.
	 */
	public static void noteMixin(ClassNode mixin, java.util.function.Function<String, byte[]> classes) {
		if (mixin == null) return;
		String binary = mixin.name.replace('/', '.');
		CLAIMS.remove(binary);
		for (Replacement kind : KINDS) {
			boolean fits;
			try {
				fits = kind.claims().test(mixin, classes == null ? path -> null : classes);
			} catch (RuntimeException unreadable) {
				Caught.warn(unreadable, "[Forbric/Mixin] could not judge whether %s is a %s mixin; taken as not", binary, kind.kind());
				fits = false;
			}
			if (fits) {
				CLAIMS.put(binary, kind.kind());
				return;
			}
		}
	}

	/** Test seam: what {@link #noteMixin} would record for a mixin fitting {@code kind}'s claim, kept apart from discovery's. */
	static void claimForTests(String mixinClass, String kind) {
		TEST_CLAIMS.put(mixinClass, kind);
	}

	/** Claims a test made with {@link #claimForTests}: discovery never forgets them, {@link #reset} does. */
	private static final Map<String, String> TEST_CLAIMS = new ConcurrentHashMap<>();

	private static Map<String,Replacement> replacements(){
		Map<String,Replacement> all=new LinkedHashMap<>();
		Map<String,String> claims=new LinkedHashMap<>(TEST_CLAIMS);claims.putAll(CLAIMS);
		claims.forEach((mixin,kind)->KINDS.stream().filter(k->k.kind().equals(kind)).findFirst().ifPresent(k->all.put(mixin,k)));
		String source=net.forbric.kernel.boot.LootSourceCallbacks.sourceName();
		if(source!=null&&net.forbric.kernel.boot.LootSourceCallbacks.whole(source))all.put(source,new Replacement("loot-source-callbacks","KernelLootBridge executes the original closed loot callback group through its generated helper and the native typed reload seam","forbric.lootBridge",net.forbric.kernel.boot.LootSourceCallbacks.targetName(),ignored->net.forbric.kernel.boot.LootSourceCallbacks.proved(source),(mixin,classes)->false));
		return all;
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/**
	 * What the kernel does instead of {@code mixinClass}, or null when nothing does.
	 *
	 * <p>Null is the answer whenever the switch is off, or the replacement's own switch is, so either turns the
	 * entry back into an ordinary marked failure rather than merely changing the wording.
	 */
	public static String replacementFor(String mixinClass) {
		Replacement replacement = enabled() ? replacements().get(mixinClass) : null;
		return replacement != null && replacement.switchedOn() ? replacement.words() : null;
	}

	/** The mixin classes claimed so far, with the words of the repair that claims each. */
	static Map<String, String> all() {
		Map<String, String> words = new LinkedHashMap<>();
		replacements().forEach((mixin, replacement) -> words.put(mixin, replacement.words()));
		return words;
	}

	/** Every repair's words, by kind, for the tests that check each claim's repair still exists. */
	static Map<String, String> kinds() {
		Map<String, String> words = new LinkedHashMap<>();
		for (Replacement kind : KINDS) words.put(kind.kind(), kind.words());
		return words;
	}

	/**
	 * What replaced {@code mixinClass}, with where it was seen, once the replacement has been proved; null before
	 * that, and whenever a switch turns the entry off.
	 */
	static String provedReplacement(String mixinClass) {
		String words = replacementFor(mixinClass);
		return words == null || !provedFor(mixinClass) || (mixinClass.equals(net.forbric.kernel.boot.LootSourceCallbacks.sourceName())&&!net.forbric.kernel.boot.LootSourceCallbacks.proved(mixinClass)) ? null : words + "; seen in the defined " + replacements().get(mixinClass).witnessClass();
	}

	/** A recorded failure of a superseded mixin: resolved now if its replacement is proved, else when it is. */
	static void awaitProof(String config, String mixinClass) {
		if (provedFor(mixinClass)) {
			resolve(config, mixinClass);
			return;
		}
		PENDING.computeIfAbsent(mixinClass, k -> ConcurrentHashMap.newKeySet()).add(String.valueOf(config));
		if (provedFor(mixinClass)) resolveAll(mixinClass);  // proved between the two checks
	}

	/**
	 * Every class the game loader defines passes here; the one that carries a replacement proves it or does not.
	 * Never throws into the definition it observes.
	 */
	public static void observeDefinition(String binaryName, byte[] bytes) {observeDefinition(null,binaryName,bytes);}
	public static void observeDefinition(ClassLoader definingLoader,String binaryName, byte[] bytes) {
		net.forbric.kernel.boot.LootSourceCallbacks.observeDefinition(definingLoader,binaryName,bytes);
		Map<String,Replacement> claimed=replacements();
		for (Replacement replacement : allKinds(claimed)) {
			boolean sourceCallback=replacement.kind().equals("loot-source-callbacks");
			if (sourceCallback&&!net.forbric.kernel.boot.LootSourceContracts.isWitness(binaryName))continue;
			if ((!sourceCallback&&!replacement.witnessClass().equals(binaryName)) || !replacement.switchedOn()) continue;
			List<String> waiting=claimed.entrySet().stream().filter(e->e.getValue().kind().equals(replacement.kind())).map(Map.Entry::getKey).toList();
			try {
				ClassNode node = new ClassNode();
				new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				if (!replacement.witness().test(node)) {
					if(sourceCallback)continue; // a multi-class proof may still await its helper; the unresolved ledger remains loud
					// Loud only when a failure is waiting on this proof: the class is defined on every boot, and a repair
					// that was never meant to run (its mod absent) has nothing to supersede.
					if (waiting.stream().anyMatch(PENDING::containsKey)) {
						ForbricLog.warn("[Forbric/Mixin] %s was defined without the repair that supersedes %s, so its"
								+ " failure stays reported", binaryName, waiting);
					} else {
						ForbricLog.debug("[Forbric/Mixin] %s defined without the repair for %s; nothing waits on it",
								binaryName, replacement.kind());
					}
					continue;
				}
			} catch (RuntimeException unreadable) {
				Caught.warn(unreadable, "[Forbric/Mixin] could not read %s to check the repair that supersedes %s;"
						+ " not counted as proved", binaryName, waiting);
				continue;
			}
			PROVED.add(replacement.kind());
			for (String mixin : waiting) resolveAll(mixin);
		}
	}

	/** Every repair kind, the dynamic loot one included when a mixin of it is claimed. */
	private static List<Replacement> allKinds(Map<String,Replacement> claimed) {
		List<Replacement> out = new java.util.ArrayList<>(KINDS);
		claimed.values().stream().filter(r -> r.kind().equals("loot-source-callbacks")).findFirst().ifPresent(out::add);
		return out;
	}

	/** Every loader session proves its own replacements. */
	static void reset() {
		PENDING.clear();
		PROVED.clear();
		CLAIMS.clear();
		TEST_CLAIMS.clear();
		net.forbric.kernel.boot.LootSourceCallbacks.reset();
	}

	/** Forgets what config discovery claimed, as discovery starts over. */
	static void forgetClaims() {
		CLAIMS.clear();
	}

	/** Whether the repair claiming {@code mixinClass} has been seen in its witness class. */
	private static boolean provedFor(String mixinClass) {
		Replacement replacement = replacements().get(mixinClass);
		return replacement != null && PROVED.contains(replacement.kind());
	}

	private static void resolveAll(String mixinClass) {
		Set<String> configs = PENDING.remove(mixinClass);
		if (configs != null) for (String config : configs) resolve("null".equals(config) ? null : config, mixinClass);
	}

	private static void resolve(String config, String mixinClass) {
		String words = replacementFor(mixinClass);
		if (words == null) return;
		String proof = words + "; seen in the defined " + replacements().get(mixinClass).witnessClass();
		MixinCompatibility.resolve(config, mixinClass, proof);
		// The replacement does the WHOLE mixin's job, so each of its injectors' own "no attachment" verdicts is
		// resolved with it. Left standing, one of them stopped a strict dedicated server on the popular pack while
		// the mixin-level row beside it already said the job was done (which of the two comes first follows the
		// order the target and the witness class happen to be defined in).
		String injectors = ":" + mixinClass + "#";
		for (net.forbric.api.CompatibilityFinding finding : net.forbric.api.CompatibilityFindings.all()) {
			if (!finding.id().startsWith("mixin-injector:") || !finding.id().contains(injectors)) continue;
			if (config != null && !finding.id().startsWith("mixin-injector:" + config + ":")) continue;
			if (finding.confidence() == net.forbric.api.CompatibilityFinding.Confidence.RESOLVED) continue;
			net.forbric.api.CompatibilityFindings.resolve(finding.id(), finding.modId(), proof);
		}
		// The line gate-m9 reads. The handler's line only says the failure is pending; this one says the repair
		// was seen, and it is the only place that knows.
		ForbricLog.info("[Forbric/Mixin] %s:%s is superseded — %s, so its mod is not marked",
				config == null ? "?" : MixinConfigOwners.describe(config), mixinClass, proof);
	}

	/** HopperBlockEntity's ejectItems asks KernelFabricHopperStorage.insert and its suckInItems asks extract. */
	static boolean hopperAsksFabric(ClassNode node) {
		boolean insert = false, extract = false;
		for (MethodNode method : node.methods) {
			boolean eject = "ejectItems".equals(method.name), suck = "suckInItems".equals(method.name);
			if (!eject && !suck) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
						|| !"net/forbric/kernel/runtime/transfer/KernelFabricHopperStorage".equals(call.owner)) continue;
				insert |= eject && "insert".equals(call.name);
				extract |= suck && "extract".equals(call.name);
			}
		}
		return insert && extract;
	}

	/** ConditionalOps' codec factory hands its one result to KernelFabricConditions.alsoAskFabric, then returns. */
	static boolean conditionalOpsAsksFabric(ClassNode node) {
		for (MethodNode method : node.methods) {
			if (!"createConditionalCodecWithConditions".equals(method.name) || !method.desc
					.equals("(Lcom/mojang/serialization/Codec;Ljava/lang/String;)Lcom/mojang/serialization/Codec;"))
				continue;
			AbstractInsnNode exit = null;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn.getOpcode() != Opcodes.ARETURN) continue;
				if (exit != null) return false;
				exit = insn;
			}
			AbstractInsnNode before = exit == null ? null : exit.getPrevious();
			while (before != null && before.getOpcode() < 0) before = before.getPrevious();
			return before instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
					&& "net/forbric/kernel/runtime/KernelFabricConditions".equals(call.owner)
					&& "alsoAskFabric".equals(call.name)
					&& "(Lcom/mojang/serialization/Codec;)Lcom/mojang/serialization/Codec;".equals(call.desc);
		}
		return false;
	}

	/**
	 * Whether every injector of {@code mixin}, a mixin into the hopper, asks Fabric's public {@code ItemStorage.SIDED}
	 * lookup: calls {@code find} on that lookup, read by data flow. One that does anything else is not this repair's job.
	 */
	static boolean asksSidedItemStorage(ClassNode mixin) {
		if (!MixinFit.mixinTargets(mixin).equals(List.of(HOPPER))) return false;
		int injectors = 0;
		for (MethodNode method : mixin.methods) {
			if (MixinFit.injectorOf(method) == null) continue;
			injectors++;
			HandlerFlow flow = HandlerFlow.of(mixin.name, method);
			if (flow == null || flow.calls(c -> c.owner.equals("net/fabricmc/fabric/api/lookup/v1/block/BlockApiLookup") && c.name.equals("find")
					&& HandlerFlow.only(flow.argument(c, 0)) instanceof org.objectweb.asm.tree.FieldInsnNode read
					&& read.getOpcode() == Opcodes.GETSTATIC && read.owner.equals("net/fabricmc/fabric/api/transfer/v1/item/ItemStorage")
					&& read.name.equals("SIDED")).isEmpty()) return false;
		}
		return injectors > 0;
	}

	/**
	 * Whether {@code mixin}, a mixin into the JSON reader, is a closed resource-condition decode guard and nothing else: a
	 * private static sentinel its class initializer makes as a bare {@code new Object()}; at least one {@code @WrapOperation}
	 * whose every answer is the original operation's or {@code DataResult.success(sentinel)}, and which calls a static
	 * evaluator whose class holds the condition key; at least one cancellable {@code @Inject} whose only effect is cancelling
	 * when an argument is that sentinel; and no other injector.
	 */
	static boolean judgesResourceConditions(ClassNode mixin, java.util.function.Function<String, byte[]> classes) {
		if (!MixinFit.mixinTargets(mixin).equals(List.of(JSON_READER))) return false;
		org.objectweb.asm.tree.FieldNode sentinel = null;
		MethodNode clinit = mixin.methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst().orElse(null);
		HandlerFlow init = clinit == null ? null : HandlerFlow.of(mixin.name, clinit);
		if (init == null) return false;
		for (AbstractInsnNode instruction : clinit.instructions)
			if (instruction instanceof org.objectweb.asm.tree.FieldInsnNode put && put.getOpcode() == Opcodes.PUTSTATIC && put.owner.equals(mixin.name)
					&& HandlerFlow.only(init.operand(put, 0, 1)) instanceof org.objectweb.asm.tree.TypeInsnNode made
					&& made.getOpcode() == Opcodes.NEW && made.desc.equals("java/lang/Object")) {
				org.objectweb.asm.tree.FieldNode field = mixin.fields.stream().filter(f -> f.name.equals(put.name) && f.desc.equals(put.desc)).findFirst().orElse(null);
				if (field != null && (field.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) == (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) {
					if (sentinel != null) return false;
					sentinel = field;
				}
			}
		if (sentinel == null) return false;
		org.objectweb.asm.tree.FieldNode marker = sentinel;
		java.util.function.Predicate<AbstractInsnNode> readsMarker = i -> i instanceof org.objectweb.asm.tree.FieldInsnNode read
				&& read.getOpcode() == Opcodes.GETSTATIC && read.owner.equals(mixin.name) && read.name.equals(marker.name) && read.desc.equals(marker.desc);
		int guards = 0, consumers = 0;
		for (MethodNode method : mixin.methods) {
			org.objectweb.asm.tree.AnnotationNode injector = MixinFit.injectorOf(method);
			if (injector == null) continue;
			HandlerFlow flow = HandlerFlow.of(mixin.name, method);
			if (flow == null) return false;
			if (injector.desc.equals("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;")) {
				Set<AbstractInsnNode> answers = flow.returned();
				boolean answersMarker = false;
				for (AbstractInsnNode answer : answers) {
					if (answer instanceof MethodInsnNode call && call.owner.equals("com/llamalad7/mixinextras/injector/wrapoperation/Operation")
							&& call.name.equals("call")) continue;
					if (answer instanceof MethodInsnNode success && success.owner.equals("com/mojang/serialization/DataResult") && success.name.equals("success")
							&& readsMarker.test(HandlerFlow.only(flow.argument(success, 0)))) { answersMarker = true; continue; }
					return false;
				}
				boolean judgesTheKey = flow.calls(c -> c.getOpcode() == Opcodes.INVOKESTATIC && !c.owner.equals(mixin.name)).stream()
						.anyMatch(c -> holdsKey(classes.apply(c.owner + ".class")));
				if (!answersMarker || !judgesTheKey) return false;
				guards++;
			} else if (injector.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;") && Boolean.TRUE.equals(MixinFit.value(injector, "cancellable"))) {
				List<MethodInsnNode> cancels = flow.calls(c -> c.name.equals("cancel") && c.desc.equals("()V"));
				if (cancels.isEmpty() || !flow.onlyCalls(c -> cancels.contains(c))) return false;
				boolean tested = false;
				for (AbstractInsnNode instruction : method.instructions)
					if (instruction instanceof org.objectweb.asm.tree.JumpInsnNode jump && (jump.getOpcode() == Opcodes.IF_ACMPEQ || jump.getOpcode() == Opcodes.IF_ACMPNE)
							&& (readsMarker.test(HandlerFlow.only(flow.operand(jump, 0, 2))) || readsMarker.test(HandlerFlow.only(flow.operand(jump, 1, 2))))) tested = true;
				if (!tested) return false;
				consumers++;
			} else {
				return false;
			}
		}
		return guards > 0 && consumers > 0;
	}

	/** Whether a class file holds the condition key as a constant. */
	private static boolean holdsKey(byte[] classBytes) {
		return classBytes != null && net.forbric.kernel.util.ByteScan.constantPoolNames(classBytes,
				new byte[][] {net.forbric.kernel.util.ByteScan.poolEntry(CONDITIONS_KEY)})[0];
	}
}
