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
 * named kernel repair does <b>everything this mixin class does</b>, and was written for exactly this failure. A
 * mixin class with a second injection the kernel does not replace does not belong here, because suppressing the
 * mark would hide that half.
 *
 * <p>A name in this table is a CLAIM, and a claim resolves nothing on its own. The failure is recorded like any
 * other; it is resolved only when the class that carries the replacement is defined with the replacement actually
 * in its bytes, and only while the replacement's own switch is on. Resolving on the table entry alone let a
 * switched-off or never-installed repair report the loss as handled.
 *
 * <p>{@code -Dforbric.supersededMixins=off} marks them like any other failure, which is how the claim in each
 * entry can be checked against what the game actually does.
 */
public final class SupersededMixins {
	static final String PROPERTY = "forbric.supersededMixins";

	/**
	 * One replacement: the words the log uses, the switch that turns the repair off, and where it is seen -- the
	 * class that carries it and the structural test its defined bytes must pass.
	 */
	private record Replacement(String words, String switchProperty, String witnessClass, Predicate<ClassNode> witness) {
		boolean switchedOn() {
			return !"off".equalsIgnoreCase(System.getProperty(switchProperty, "on"));
		}
	}

	/** Mixin class → the kernel repair that does its job. */
	private static final Map<String, Replacement> SUPERSEDED = superseded();
	/** Failures recorded while their replacement was not yet proved: mixin → configs. */
	private static final Map<String, Set<String>> PENDING = new ConcurrentHashMap<>();
	private static final Set<String> PROVED = ConcurrentHashMap.newKeySet();

	private static Map<String, Replacement> superseded() {
		Map<String, Replacement> map = new LinkedHashMap<>();
		// Both of this mixin's members are the fabric:load_conditions evaluator: a @WrapOperation on the codec
		// parse that applies the conditions, and an @Inject that skips the entry it rejected. Neither can apply —
		// NeoForge's patch of scanDirectory made the value Optional and reordered the lambda's captures, so the
		// descriptor Mixin expects is not the one the mod was built against. KernelFabricConditions does both
		// jobs one level down, on ConditionalOps' own funnel, which covers every consumer rather than this one.
		// Seen when ConditionalOps is defined with KernelFabricConditions.alsoAskFabric wrapping its factory's
		// single exit (ForbricMergedBaseCompatTransformer.letFabricResourceConditionsDecide), while
		// -Dforbric.fabricConditions is not off -- off makes alsoAskFabric hand the codec back untouched.
		map.put("net.fabricmc.fabric.mixin.resource.conditions.SimpleJsonResourceReloadListenerMixin",
				new Replacement("the kernel evaluates fabric:load_conditions at ConditionalOps' funnel instead "
						+ "(KernelFabricConditions), which covers every consumer rather than this one call site",
						"forbric.fabricConditions", "net.neoforged.neoforge.common.conditions.ConditionalOps",
						SupersededMixins::conditionalOpsAsksFabric));
		// fabric-transfer-api's hopper mixin is two injectors, both "ask ItemStorage.SIDED where vanilla found no
		// container": after getAttachedContainer in ejectItems, after getSourceContainer in suckInItems. NeoForge's
		// hopper calls neither. KernelFabricHopperStorage runs the same lookup, faces and one-item move on NeoForge's
		// two found-nothing branches. Seen when HopperBlockEntity is defined with both calls in place, while
		// -Dforbric.hopperFabricStorage is not off.
		map.put("net.fabricmc.fabric.mixin.transfer.HopperBlockEntityMixin",
				new Replacement("the kernel runs Fabric's hopper storage lookup on NeoForge's found-nothing branches of "
						+ "ejectItems and suckInItems instead (KernelFabricHopperStorage)",
						"forbric.hopperFabricStorage", "net.minecraft.world.level.block.entity.HopperBlockEntity",
						SupersededMixins::hopperAsksFabric));
		return Map.copyOf(map);
	}

	private SupersededMixins() {
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
		Replacement replacement = enabled() ? SUPERSEDED.get(mixinClass) : null;
		return replacement != null && replacement.switchedOn() ? replacement.words() : null;
	}

	/** The mixin classes with an entry, for the tests that check each claim is still true. */
	static Map<String, String> all() {
		Map<String, String> words = new LinkedHashMap<>();
		SUPERSEDED.forEach((mixin, replacement) -> words.put(mixin, replacement.words()));
		return words;
	}

	/**
	 * What replaced {@code mixinClass}, with where it was seen, once the replacement has been proved; null before
	 * that, and whenever a switch turns the entry off.
	 */
	static String provedReplacement(String mixinClass) {
		String words = replacementFor(mixinClass);
		return words == null || !PROVED.contains(mixinClass) ? null : words + "; seen in the defined " + SUPERSEDED.get(mixinClass).witnessClass();
	}

	/** A recorded failure of a superseded mixin: resolved now if its replacement is proved, else when it is. */
	static void awaitProof(String config, String mixinClass) {
		if (PROVED.contains(mixinClass)) {
			resolve(config, mixinClass);
			return;
		}
		PENDING.computeIfAbsent(mixinClass, k -> ConcurrentHashMap.newKeySet()).add(String.valueOf(config));
		if (PROVED.contains(mixinClass)) resolveAll(mixinClass);  // proved between the two checks
	}

	/**
	 * Every class the game loader defines passes here; the one that carries a replacement proves it or does not.
	 * Never throws into the definition it observes.
	 */
	public static void observeDefinition(String binaryName, byte[] bytes) {
		for (Map.Entry<String, Replacement> entry : SUPERSEDED.entrySet()) {
			Replacement replacement = entry.getValue();
			if (!replacement.witnessClass().equals(binaryName) || !replacement.switchedOn()) continue;
			try {
				ClassNode node = new ClassNode();
				new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				if (!replacement.witness().test(node)) {
					ForbricLog.warn("[Forbric/Mixin] %s was defined without the repair that supersedes %s, so its"
							+ " failure stays reported", binaryName, entry.getKey());
					continue;
				}
			} catch (RuntimeException unreadable) {
				continue;
			}
			PROVED.add(entry.getKey());
			resolveAll(entry.getKey());
		}
	}

	/** Every loader session proves its own replacements. */
	static void reset() {
		PENDING.clear();
		PROVED.clear();
	}

	private static void resolveAll(String mixinClass) {
		Set<String> configs = PENDING.remove(mixinClass);
		if (configs != null) for (String config : configs) resolve("null".equals(config) ? null : config, mixinClass);
	}

	private static void resolve(String config, String mixinClass) {
		String words = replacementFor(mixinClass);
		if (words == null) return;
		String proof = words + "; seen in the defined " + SUPERSEDED.get(mixinClass).witnessClass();
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
}
