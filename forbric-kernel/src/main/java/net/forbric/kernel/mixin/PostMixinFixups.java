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

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.boot.KernelRegistryAliases;
import net.forbric.kernel.util.ByteScan;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.ForbricSwitches;

/**
 * Repairs the few classes a guest mixin wove <em>incorrectly</em> because the Forge/NeoForge byte-merge restructured
 * the target — the cases the pre-mixin adapter ({@link KernelGuestMixinAdapter}) cannot pre-empt because the damage
 * is done by Mixin's own weaving. Runs on the post-Mixin bytes, so it can see mixin-added members.
 *
 * <p>A mixin's field initialiser that Mixin put after a merged constructor's own constructions of its class, instead of
 * after {@code super()}, is moved back there by {@link MixinFieldInitialiserPlacement} — recognised by the woven class's
 * shape and the mixin's native class, whichever mod's field it is.
 */
public final class PostMixinFixups {
	/** Mixin names every injected callback {@code handler$<id>$<method>}. */
	private static final String HANDLER_PREFIX = "handler$";
	private static final String MIXIN_MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";

	/** {@code -Dforbric.postMixinFixups=off} disables these repairs. */
	static final String PROPERTY = "forbric.postMixinFixups";

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	private PostMixinFixups() {
	}

	/** Applies any post-mixin repair keyed on {@code name}. {@code bytes} may be null (a mixin class-gen request). */
	public static byte[] apply(String name, byte[] bytes) {
		if (bytes == null || !enabled()) return bytes;
		String internal = name.replace('.', '/');
		bytes = MixinFieldInitialiserPlacement.repair(bytes, NativeGameReferences::reference, MixinStubRebind::ecosystemOf);
		// A value a guest changed, whose native consumer the carrier replaced: handed to the carrier's own consumer of it.
		bytes = NativeDeadValueConsumers.apply(internal, bytes, NativeGameReferences::reference, MixinStubRebind::ecosystemOf);
		if (MAPPED_REGISTRY.equals(internal)) bytes = askTheRegistryWhetherAnAliasTargetExists(bytes);
		// The fuel table a native build hands its caller, after every return hook (FabricFuelValuesInjector.afterMixin).
		bytes = net.forbric.kernel.transform.FabricFuelValuesInjector.afterMixin(internal, bytes);
		return replayDelegatedConstructorInjections(internal, bytes, NativeGameReferences::reference, MixinStubRebind::ecosystemOf);
	}

	private static final String MAPPED_REGISTRY = "net/minecraft/core/MappedRegistry";
	/** {@code off} leaves the alias-target warning as Mixin wove it, so NeoForge's alias-first order warns again. */
	static final String ALIAS_PRESENCE_PROPERTY = KernelRegistryAliases.PROPERTY;
	/** The registry's id map, which fabric-registry-sync's warning reads. */
	private static final String BY_LOCATION = "byLocation";

	/**
	 * Lets fabric-registry-sync's "alias target doesn't exist" warning hear about NeoForge's alias-first order.
	 *
	 * <p>fabric-registry-sync's mixin-added {@code MappedRegistry.addAlias} warns when {@code !this.byLocation.containsKey(target)}.
	 * On NeoForge {@code MappedRegistry} extends NeoForge's {@code BaseMappedRegistry}, whose own {@code addAlias} the mixin's
	 * method overrides, and NeoForge's {@code DeferredRegister.addEntries} applies a mod's aliases BEFORE it registers that
	 * mod's entries, in the same call. So every NeoForge mod alias reached fabric-api's warning before its target existed:
	 * sophisticatedcore's "Adding sophisticatedcore:render_info_tag as an alias for …, but the latter doesn't exist" on every
	 * boot, for an entry registered a few lines later. NeoForge's own {@code addAlias} never warns about its own order.
	 *
	 * <p>The {@code containsKey} becomes {@code KernelRegistryAliases.aliasTargetPresent(byLocation, target)}: the same exact,
	 * non-resolving lookup of the same map, and yes besides while NeoForge's {@code DeferredRegister.addEntries} is the
	 * caller. Not {@code containsKey(Identifier)}, which an alias mixin's {@code @ModifyVariable} and the parity injector
	 * both resolve through aliases, so an alias chain would change what the warning says.
	 *
	 * <p>Only a check whose only consequence is a warning: {@code this.byLocation.containsKey(x)} whose "absent" path goes
	 * straight into a logger's {@code warn} without throwing or returning first — whatever the method and the message are
	 * called. The collision check before it ("already present in registry", which throws) reads the same map for the ALIAS,
	 * and is left alone. Straight-line replacement with the same stack shape (the map, then the id, then a boolean), so
	 * every frame stays valid. {@code -Dforbric.aliasPresenceParity=off} leaves the method as Mixin wove it.
	 *
	 * <p>On the merged base this also swapped the map read for the registry's {@code keySet()}, because MinecraftForge's
	 * registry wrappers never filled {@code byLocation}. NeoForge's registries fill it and {@code keySet()} is a view of it,
	 * so the read is the mixin's own again.
	 */
	static byte[] askTheRegistryWhetherAnAliasTargetExists(byte[] bytes) {
		if ("off".equalsIgnoreCase(ForbricSwitches.get(ALIAS_PRESENCE_PROPERTY, "on"))) return bytes;
		if (!ByteScan.contains(bytes, BY_LOCATION_NEEDLE) || !ByteScan.contains(bytes, WARN_NEEDLE)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		int repaired = 0;
		List<String> where = new ArrayList<>();
		for (MethodNode method : node.methods) {
			if ((method.access & Opcodes.ACC_STATIC) != 0) continue;
			int before = repaired;
			for (AbstractInsnNode insn : method.instructions.toArray()) {
				if (!(insn instanceof MethodInsnNode contains) || contains.getOpcode() != Opcodes.INVOKEINTERFACE
						|| !contains.owner.equals("java/util/Map") || !contains.name.equals("containsKey")) continue;
				AbstractInsnNode key = realPrevious(contains);
				AbstractInsnNode map = key == null ? null : realPrevious(key);
				if (!(key instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD
						|| !(map instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
						|| !field.owner.equals(node.name) || !field.name.equals(BY_LOCATION) || !field.desc.equals("Ljava/util/Map;")
						|| !onlyWarnsWhenAbsent(contains)) continue;
				method.instructions.set(contains, new MethodInsnNode(Opcodes.INVOKESTATIC,
						"net/forbric/kernel/boot/KernelRegistryAliases", "aliasTargetPresent",
						"(Ljava/util/Map;Ljava/lang/Object;)Z", false));
				repaired++;
			}
			if (repaired > before) where.add(method.name);
		}
		if (repaired == 0) return bytes;
		ForbricLog.info("[Forbric/Aliases] post-mixin repair: %s.%s no longer warns that an alias target is missing while "
				+ "NeoForge's DeferredRegister is applying its aliases, which it does before it registers their targets",
				node.name.replace('/', '.'), String.join(", ", where));
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static final byte[] BY_LOCATION_NEEDLE = ByteScan.needle(BY_LOCATION);
	private static final byte[] WARN_NEEDLE = ByteScan.needle("warn");

	/**
	 * Whether the "absent" path of this {@code containsKey} goes straight into a logger's {@code warn}: the branch after
	 * it, then — on the path taken when the key is missing — at most a few dozen straight-line instructions that end in
	 * a call named {@code warn}, with no throw, return or other branch on the way.
	 */
	private static boolean onlyWarnsWhenAbsent(AbstractInsnNode check) {
		AbstractInsnNode branch = check.getNext();
		while (branch != null && branch.getOpcode() < 0) branch = branch.getNext();
		if (!(branch instanceof org.objectweb.asm.tree.JumpInsnNode jump)) return false;
		AbstractInsnNode insn;
		if (jump.getOpcode() == Opcodes.IFNE) insn = jump.getNext();
		else if (jump.getOpcode() == Opcodes.IFEQ) insn = jump.label;
		else return false;
		for (int seen = 0; insn != null && seen < 40; insn = insn.getNext()) {
			int op = insn.getOpcode();
			if (op < 0) continue;
			seen++;
			if (insn instanceof MethodInsnNode call && call.name.equals("warn")) return true;
			if (op == Opcodes.ATHROW || (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) || insn instanceof org.objectweb.asm.tree.JumpInsnNode
					|| insn instanceof org.objectweb.asm.tree.TableSwitchInsnNode || insn instanceof org.objectweb.asm.tree.LookupSwitchInsnNode) return false;
		}
		return false;
	}

	private static AbstractInsnNode realPrevious(AbstractInsnNode insn) {
		AbstractInsnNode previous = insn.getPrevious();
		while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
		return previous;
	}

	/**
	 * Moves a guest mixin's constructor-injected handler from a DELEGATING constructor into the one it delegates to,
	 * so the handler still runs when callers use the constructor the carrier made canonical.
	 *
	 * <p>NeoForge adds wider constructor overloads to vanilla classes and rewrites the vanilla-shaped one to delegate to
	 * them. A guest mixin written against vanilla injects into the only constructor it knew about — the narrow one — so
	 * every caller that uses the wide one skips the mixin's field initialization entirely. The failure surfaces far away,
	 * as a null field with no stack frame pointing at the cause.
	 *
	 * <p>Measured archetype: NeoForge made {@code BakedQuad}'s 12-arg constructor (adding {@code BakedNormals} +
	 * {@code BakedColors}) canonical and made the vanilla 10-arg one delegate to it with {@code UNSPECIFIED} /
	 * {@code DEFAULT}. Sodium's {@code BakedQuadMixin} injected into the 10-arg one to compute its {@code @Unique
	 * normalFace}, so every quad built through the 12-arg constructor carried a null {@code normalFace} and Sodium's
	 * chunk mesher threw {@code NullPointerException} at {@code EncodingFormat.normalFace}. On NeoForge's own base the
	 * same shape holds for fabric-object-builder's {@code BlockEntityType} blocks set, fabric-data-generation's
	 * {@code TagsProvider} path resolver and fabric-model-loading's {@code ModelBakery} hook: each wide constructor is
	 * NeoForge's and not vanilla's.
	 *
	 * <p>Only where the mixin's own platform does not delegate that way itself: when the class as the mixin's ecosystem
	 * compiled it (its native reference) has the narrow constructor delegating to the same wide one — vanilla's own
	 * {@code CommandSourceStack} does, into its private wide constructor — the handler natively runs for callers of the
	 * narrow one only, and moving it would run it where it never runs natively. A NeoForge mixin's native class is the
	 * base itself, so it is never moved. A mixin whose ecosystem or native class is unknown is moved as before.
	 *
	 * <p><b>The move is only performed when it is provably equivalent</b>, which is what keeps this general rather
	 * than a hand-patch. It requires that, in the delegating constructor, the handler call sits immediately after the
	 * {@code this(...)} delegation and immediately before {@code RETURN}, with nothing but argument loads in between.
	 * Then "at the end of the narrow constructor" and "at the end of the wide constructor" denote the same program
	 * point on the narrow path, so moving changes nothing for existing callers and only adds the missing call for
	 * direct callers of the wide one. The argument sequence is cloned verbatim: the narrow constructor's parameters
	 * are a prefix of the wide one's with identical types, so the local slots it loads are valid unchanged.
	 */
	static byte[] replayDelegatedConstructorInjections(String internal, byte[] bytes,
			BiFunction<Ecosystem, String, ClassNode> natives, Function<String, Ecosystem> ecosystemOf) {
		// Cheapest pre-check first, on the raw bytes. This runs for EVERY class the game loads, and the one below
		// it — cheap only by comparison — still parses the whole class including every instruction of every
		// method. A class Mixin never wove cannot contain a handler name, and a method name lives in the constant
		// pool as plain ASCII, so a byte scan settles it without parsing anything.
		if (!mentionsAMixinHandler(bytes)) return bytes;

		// Then the structural check: this only ever applies to a class with several constructors, one of which
		// Mixin wove.
		if (!hasWovenConstructorPair(bytes)) return bytes;

		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);

		boolean changed = false;
		for (MethodNode narrow : new ArrayList<>(node.methods)) {
			if (!narrow.name.equals("<init>")) continue;

			MethodInsnNode delegation = thisDelegation(node, narrow);
			if (delegation == null) continue;

			Replay replay = trailingHandlerCall(narrow, delegation);
			if (replay == null) continue;

			MethodNode wide = findOwn(node, "<init>", delegation.desc);
			if (wide == null || wide == narrow) continue;
			// Only when the handler's parameters are a prefix of the wide constructor's, so the cloned loads line up.
			if (!parametersArePrefix(replay.call.desc, wide.desc)) continue;
			// If the mixin already injected into the wide constructor too, adding a second call would double-run it.
			if (callsHandler(wide, replay.call)) continue;
			// Natively the narrow constructor delegates the same way, so the handler natively skips the wide one's callers.
			if (nativeDelegatesTheSameWay(node, narrow.desc, delegation.desc, replay.call, natives, ecosystemOf)) continue;

			for (AbstractInsnNode insn = wide.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn.getOpcode() != Opcodes.RETURN) continue;
				wide.instructions.insertBefore(insn, cloneRange(replay));
			}
			wide.maxStack = Math.max(wide.maxStack, narrow.maxStack);
			// Remove the original so the narrow path still runs it exactly once, via the delegate.
			for (AbstractInsnNode insn : replay.range()) narrow.instructions.remove(insn);
			changed = true;

			ForbricLog.info("[Forbric/Mixin] post-mixin repair: replayed %s's constructor injection %s into the "
					+ "constructor it delegates to (%s) — the carrier made that overload canonical, so callers of it "
					+ "were skipping the guest mixin's initializer", internal.replace('/', '.'), replay.call.name,
					delegation.desc);
		}
		if (!changed) return bytes;

		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * Whether the class as the handler's mixin's ecosystem compiled it has constructor {@code narrowDesc} delegating
	 * {@code this(...)} to {@code wideDesc}: then natively, too, only the narrow constructor's callers run the handler.
	 * A NeoForge mixin's native class is the base itself. Unknown ecosystem or no native reference: false.
	 */
	static boolean nativeDelegatesTheSameWay(ClassNode node, String narrowDesc, String wideDesc, MethodInsnNode handler,
			BiFunction<Ecosystem, String, ClassNode> natives, Function<String, Ecosystem> ecosystemOf) {
		if (ecosystemOf == null) return false;
		Ecosystem ecosystem = ecosystemOf.apply(mixinOf(node, handler));
		if (ecosystem == Ecosystem.NEOFORGE) return true;
		if (ecosystem == null || natives == null) return false;
		ClassNode original = natives.apply(ecosystem, node.name);
		if (original == null) return false;
		MethodNode narrow = findOwn(original, "<init>", narrowDesc);
		if (narrow == null) return false;
		MethodInsnNode delegation = thisDelegation(original, narrow);
		return delegation != null && delegation.desc.equals(wideDesc);
	}

	/** The mixin (internal name) the handler method was merged from ({@code @MixinMerged}), or null. */
	private static String mixinOf(ClassNode node, MethodInsnNode handler) {
		MethodNode method = findOwn(node, handler.name, handler.desc);
		if (method == null) return null;
		for (List<AnnotationNode> annotations : List.of(
				method.visibleAnnotations == null ? List.<AnnotationNode>of() : method.visibleAnnotations,
				method.invisibleAnnotations == null ? List.<AnnotationNode>of() : method.invisibleAnnotations)) {
			for (AnnotationNode annotation : annotations) {
				if (!MIXIN_MERGED.equals(annotation.desc)) continue;
				Object mixin = MixinFit.value(annotation, "mixin");
				if (mixin instanceof String name) return name.replace('.', '/');
			}
		}
		return null;
	}

	/** A handler call plus the argument loads that feed it, as a contiguous instruction range. */
	private record Replay(AbstractInsnNode first, MethodInsnNode call) {
		List<AbstractInsnNode> range() {
			List<AbstractInsnNode> out = new ArrayList<>();
			for (AbstractInsnNode insn = first; insn != null; insn = insn.getNext()) {
				out.add(insn);
				if (insn == call) break;
			}
			return out;
		}
	}

	private static InsnList cloneRange(Replay replay) {
		InsnList out = new InsnList();
		java.util.Map<org.objectweb.asm.tree.LabelNode, org.objectweb.asm.tree.LabelNode> labels =
				new java.util.HashMap<>();
		for (AbstractInsnNode insn : replay.range()) out.add(insn.clone(labels));
		return out;
	}

	/**
	 * The mixin handler call that closes {@code ctor}, if everything between {@code delegation} and {@code RETURN} is
	 * that call and its argument loads. Anything else in the gap — real constructor work, a branch, another call —
	 * means the move would not be equivalent, so this returns null and the constructor is left alone.
	 */
	private static Replay trailingHandlerCall(MethodNode ctor, MethodInsnNode delegation) {
		AbstractInsnNode first = null;
		MethodInsnNode call = null;

		for (AbstractInsnNode insn = delegation.getNext(); insn != null; insn = insn.getNext()) {
			if (insn instanceof org.objectweb.asm.tree.LabelNode
					|| insn instanceof org.objectweb.asm.tree.LineNumberNode
					|| insn instanceof org.objectweb.asm.tree.FrameNode) {
				continue;
			}
			if (call != null) {
				// Only the RETURN may follow the handler call.
				return insn.getOpcode() == Opcodes.RETURN ? new Replay(first, call) : null;
			}
			if (insn instanceof MethodInsnNode mi) {
				if (!mi.owner.equals(delegation.owner) || !mi.name.startsWith(HANDLER_PREFIX)) return null;
				call = mi;
				continue;
			}
			if (insn.getOpcode() == Opcodes.RETURN) return null;  // no handler here, just a plain delegating ctor
			if (!isArgumentLoad(insn)) return null;
			if (first == null) first = insn;
		}
		return null;
	}

	/** Loads and constants only — proves the cloned range has no side effects of its own. */
	private static boolean isArgumentLoad(AbstractInsnNode insn) {
		int op = insn.getOpcode();
		if (insn instanceof VarInsnNode) {
			return op == Opcodes.ALOAD || op == Opcodes.ILOAD || op == Opcodes.LLOAD
					|| op == Opcodes.FLOAD || op == Opcodes.DLOAD;
		}
		if (insn instanceof org.objectweb.asm.tree.LdcInsnNode) return true;
		return op == Opcodes.ACONST_NULL
				|| (op >= Opcodes.ICONST_M1 && op <= Opcodes.DCONST_1)
				|| op == Opcodes.BIPUSH || op == Opcodes.SIPUSH;
	}

	/** The {@code this(...)} chain call opening {@code ctor}, or null when it calls {@code super(...)} instead. */
	private static MethodInsnNode thisDelegation(ClassNode node, MethodNode ctor) {
		for (AbstractInsnNode insn = ctor.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.INVOKESPECIAL) continue;
			MethodInsnNode call = (MethodInsnNode) insn;
			if (!call.name.equals("<init>")) continue;
			return call.owner.equals(node.name) ? call : null;
		}
		return null;
	}

	private static MethodNode findOwn(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name) && m.desc.equals(desc)) return m;
		}
		return null;
	}

	private static boolean callsHandler(MethodNode method, MethodInsnNode handler) {
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode mi && mi.name.equals(handler.name) && mi.desc.equals(handler.desc)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the handler's parameters (its trailing {@code CallbackInfo} aside) are a prefix of {@code wideDesc}'s,
	 * with identical types — the condition that makes cloning the argument loads slot-for-slot valid.
	 */
	private static boolean parametersArePrefix(String handlerDesc, String wideDesc) {
		Type[] handler = Type.getArgumentTypes(handlerDesc);
		Type[] wide = Type.getArgumentTypes(wideDesc);
		if (handler.length == 0) return false;
		int shared = handler.length - 1;  // drop the trailing CallbackInfo
		if (shared > wide.length) return false;
		for (int i = 0; i < shared; i++) {
			if (!handler[i].equals(wide[i])) return false;
		}
		return true;
	}

	/**
	 * Whether the raw class bytes mention a Mixin handler name at all.
	 *
	 * <p>A method name is stored in the constant pool as modified UTF-8, and {@code handler$} is pure ASCII, so
	 * it appears verbatim in the bytes of any class that has one. A class without the sequence provably has no
	 * such method, which makes a "no" here final rather than a guess. A "yes" is only a hint — the constant could
	 * be a string literal — and the structural check that follows settles it.
	 */
	static boolean mentionsAMixinHandler(byte[] bytes) {
		return ByteScan.contains(bytes, HANDLER_NEEDLE);
	}

	private static final byte[] HANDLER_NEEDLE = ByteScan.needle(HANDLER_PREFIX);

	/** Quick reject: does this class even have several constructors, one of which calls a Mixin handler? */
	private static boolean hasWovenConstructorPair(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		int ctors = 0;
		boolean woven = false;
		for (MethodNode m : node.methods) {
			if (!m.name.equals("<init>")) continue;
			ctors++;
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null && !woven; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode mi && mi.name.startsWith(HANDLER_PREFIX)) woven = true;
			}
		}
		return ctors > 1 && woven;
	}
}
