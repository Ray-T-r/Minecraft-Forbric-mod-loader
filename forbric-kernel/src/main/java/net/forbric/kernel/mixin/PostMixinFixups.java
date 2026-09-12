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

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Repairs the few classes a guest mixin wove <em>incorrectly</em> because the Forge/NeoForge byte-merge restructured
 * the target — the cases the pre-mixin adapter ({@link KernelGuestMixinAdapter}) cannot pre-empt because the damage
 * is done by Mixin's own weaving. Runs on the post-Mixin bytes, so it can see mixin-added members.
 *
 * <p>Currently one entry: {@code fabric-resource-loader-v1}'s {@code PackMixin} adds a {@code parentsPredicate} field
 * initialized to {@code DEFAULT_PARENT_PREDICATE}. Mixin injects a field initializer after the constructor's
 * super/this call — but NeoForge's merged {@code Pack} 5-arg constructor recursively calls {@code new Pack(...)}
 * inside its child-building loop, and Mixin placed the initializer after THAT recursive call, i.e. inside the loop.
 * The loop is skipped for any pack with an empty children list — every top-level pack from {@code readMetaAndCreate}
 * — so their {@code parentsPredicate} stays {@code null}. {@code fabric$isHidden()} is {@code parentsPredicate !=
 * DEFAULT}, so a null makes EVERY top-level pack (including the vanilla data pack) read as a hidden mod pack;
 * {@code refreshAutoEnabledPacks} then strips them from the selected set, the vanilla datapack never loads, and 13
 * dynamic registries come up empty. The repair re-adds the initializer where Mixin should have put it: immediately
 * after {@code super()}, so {@code parentsPredicate} is DEFAULT for every pack regardless of children (the stray
 * in-loop assignment becomes a harmless repeat).
 */
public final class PostMixinFixups {
	private static final String PACK = "net/minecraft/server/packs/repository/Pack";
	private static final String GUI_RENDERER = "net/minecraft/client/gui/render/GuiRenderer";
	private static final String PIP_RENDERERS = "pictureInPictureRenderers";
	private static final String MAP_DESC = "Ljava/util/Map;";
	/** Mixin names every injected callback {@code handler$<id>$<method>}. */
	private static final String HANDLER_PREFIX = "handler$";
	private static final String FABRIC_PACK = "net/fabricmc/fabric/impl/resource/pack/FabricPack";
	private static final String PARENTS_PREDICATE = "parentsPredicate";
	private static final String DEFAULT_PARENT_PREDICATE = "DEFAULT_PARENT_PREDICATE";
	private static final String PREDICATE_DESC = "Ljava/util/function/Predicate;";

	/**
	 * Merged-base fields this class SEEDS, as {@code owner#field}. {@link MixinFit} consults this so it does not
	 * report a repaired field as an orphan: it resolves against pre-mixin bytes, which are also pre-repair, and
	 * would otherwise suppress the very mixins the repair exists to keep working.
	 */
	public static final java.util.Set<String> SEEDED_FIELDS = java.util.Set.of(GUI_RENDERER + "#" + PIP_RENDERERS);

	/** Whether {@link #SEEDED_FIELDS} covers {@code owner#field} — and the repair pass is actually on. */
	public static boolean isSeeded(String owner, String field) {
		return enabled() && SEEDED_FIELDS.contains(owner + "#" + field);
	}

	/** {@code -Dforbric.postMixinFixups=off} disables these repairs. */
	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty("forbric.postMixinFixups", "on"));
	}

	private PostMixinFixups() {
	}

	/** Applies any post-mixin repair keyed on {@code name}. {@code bytes} may be null (a mixin class-gen request). */
	public static byte[] apply(String name, byte[] bytes) {
		if (bytes == null || !enabled()) return bytes;
		String internal = name.replace('.', '/');
		if (PACK.equals(internal)) return repairPackParentsPredicate(bytes);
		if (GUI_RENDERER.equals(internal)) return seedOrphanedPipRenderers(bytes);
		return replayDelegatedConstructorInjections(internal, bytes);
	}

	/**
	 * Moves a guest mixin's constructor-injected handler from a DELEGATING constructor into the one it delegates to,
	 * so the handler still runs when callers use the constructor the merge made canonical.
	 *
	 * <p>The merge routinely adds a wider constructor overload and rewrites the vanilla-shaped one to delegate to it.
	 * A guest mixin, written against vanilla, injects into the only constructor it knew about — the narrow one — so
	 * every caller that uses the wide one now skips the mixin's field initialization entirely. The failure surfaces
	 * far away, as a null field with no stack frame pointing at the cause.
	 *
	 * <p>Measured archetype: NeoForge made {@code BakedQuad}'s 12-arg constructor (adding {@code BakedNormals} +
	 * {@code BakedColors}) canonical and made the vanilla 10-arg one delegate to it with {@code UNSPECIFIED} /
	 * {@code DEFAULT}. Sodium's {@code BakedQuadMixin} injects into the 10-arg one to compute its {@code @Unique
	 * normalFace}, so every quad built through the 12-arg constructor carried a null {@code normalFace} and Sodium's
	 * chunk mesher threw {@code NullPointerException} at {@code EncodingFormat.normalFace} — the crash that made
	 * terrain unbuildable once the mesher was no longer being suppressed outright.
	 *
	 * <p><b>The move is only performed when it is provably equivalent</b>, which is what keeps this general rather
	 * than a hand-patch. It requires that, in the delegating constructor, the handler call sits immediately after the
	 * {@code this(...)} delegation and immediately before {@code RETURN}, with nothing but argument loads in between.
	 * Then "at the end of the narrow constructor" and "at the end of the wide constructor" denote the same program
	 * point on the narrow path, so moving changes nothing for existing callers and only adds the missing call for
	 * direct callers of the wide one. The argument sequence is cloned verbatim: the narrow constructor's parameters
	 * are a prefix of the wide one's with identical types, so the local slots it loads are valid unchanged.
	 */
	private static byte[] replayDelegatedConstructorInjections(String internal, byte[] bytes) {
		// Cheap pre-check: this only ever applies to a class with several constructors, one of which Mixin wove.
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

			for (AbstractInsnNode insn = wide.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn.getOpcode() != Opcodes.RETURN) continue;
				wide.instructions.insertBefore(insn, cloneRange(replay));
			}
			wide.maxStack = Math.max(wide.maxStack, narrow.maxStack);
			// Remove the original so the narrow path still runs it exactly once, via the delegate.
			for (AbstractInsnNode insn : replay.range()) narrow.instructions.remove(insn);
			changed = true;

			ForbricLog.info("[Forbric/Mixin] post-mixin repair: replayed %s's constructor injection %s into the "
					+ "constructor it delegates to (%s) — the merge made that overload canonical, so callers of it "
					+ "were skipping the guest mixin's initializer", internal.replace('/', '.'), replay.call.name,
					delegation.desc);
		}
		if (!changed) return bytes;

		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
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

	/**
	 * Seeds {@code GuiRenderer.pictureInPictureRenderers} with an empty map, repairing the orphan instead of
	 * suppressing the mixins that read it.
	 *
	 * <p>NeoForge won the byte-merge of {@code GuiRenderer.<init>}, re-typed its third parameter and replaced
	 * vanilla's {@code pictureInPictureRenderers} map with its own {@code pictureInPictureRendererPools}. The field
	 * survives but nothing assigns it, and generic erasure hides that from Mixin (both descriptors are just
	 * {@code List}), so any mixin that {@code @Shadow}s it applies cleanly and then reads null.
	 *
	 * <p>Suppressing those mixins is not a fix, because the damage is not confined to the mixin that reads the
	 * field. Measured: dropping {@code mixins.malilib.json:gui.MixinGuiRenderer} left MaLiLib's sibling
	 * {@code MixinGameRenderer} calling {@code RenderUtils.registerSpecialGuiRenderers}, which does
	 * {@code ImmutableMap.Builder.putAll(map)} on the map the dropped mixin was supposed to populate → a NEW
	 * {@code NullPointerException} during {@code Minecraft.<init>}. That coupling runs through mod-owned STATE, not
	 * through an interface, so the cast-contract closure cannot see it and no suppression policy can be made safe.
	 *
	 * <p>An empty map degrades honestly: mods that register custom picture-in-picture renderers get no effect
	 * (NeoForge's pool mechanism owns that path in the merged base), but every read succeeds and both ecosystems'
	 * GUI mixins apply. This is the same shape as the {@code Pack.parentsPredicate} repair above — a field the merge
	 * left unassigned, seeded where the original initializer would have run.
	 */
	private static byte[] seedOrphanedPipRenderers(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);

		boolean declared = false;
		if (node.fields != null) {
			for (FieldNode f : node.fields) {
				if (PIP_RENDERERS.equals(f.name) && MAP_DESC.equals(f.desc)) {
					declared = true;
					break;
				}
			}
		}
		if (!declared) return bytes;
		// If a CONSTRUCTOR already assigns it, the merge did not orphan it — leave well alone.
		//
		// Scanning every method instead would defeat the repair: these are post-mixin bytes, and the very mixin this
		// exists to support writes the field back inside its own handler
		// (`handler$…$mutableSpecialElementRenderers` does `new IdentityHashMap(pictureInPictureRenderers)` and
		// stores the result). That write happens AFTER the null read that crashes, so it must not count as an
		// initializer. Only a write on the constructor's own path proves the field is initialized before use.
		for (MethodNode m : node.methods) {
			if (!m.name.equals("<init>")) continue;
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTFIELD
						&& PIP_RENDERERS.equals(fi.name)) {
					return bytes;
				}
			}
		}

		boolean changed = false;
		for (MethodNode m : node.methods) {
			if (!m.name.equals("<init>")) continue;
			AbstractInsnNode superCall = superInitCall(m);
			if (superCall == null) continue;

			InsnList init = new InsnList();
			init.add(new VarInsnNode(Opcodes.ALOAD, 0));
			init.add(new TypeInsnNode(Opcodes.NEW, "java/util/IdentityHashMap"));
			init.add(new InsnNode(Opcodes.DUP));
			init.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/IdentityHashMap", "<init>", "()V", false));
			init.add(new FieldInsnNode(Opcodes.PUTFIELD, GUI_RENDERER, PIP_RENDERERS, MAP_DESC));
			m.instructions.insert(superCall, init);
			m.maxStack = Math.max(m.maxStack, 3);
			changed = true;
		}
		if (!changed) return bytes;

		ForbricLog.info("[Forbric/Mixin] post-mixin repair: seeded GuiRenderer.%s with an empty map — NeoForge won the "
				+ "byte-merge of the constructor and replaced it with pictureInPictureRendererPools, orphaning the "
				+ "field that guest GUI mixins @Shadow", PIP_RENDERERS);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static byte[] repairPackParentsPredicate(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);

		// Only if PackMixin actually applied (the field + the FabricPack interface are present).
		boolean hasField = false;
		if (node.fields != null) {
			for (FieldNode f : node.fields) {
				if (PARENTS_PREDICATE.equals(f.name) && PREDICATE_DESC.equals(f.desc)) {
					hasField = true;
					break;
				}
			}
		}
		boolean isFabricPack = node.interfaces != null && node.interfaces.contains(FABRIC_PACK);
		if (!hasField || !isFabricPack) return bytes;

		boolean changed = false;
		for (MethodNode m : node.methods) {
			if (!m.name.equals("<init>")) continue;
			AbstractInsnNode superCall = superInitCall(m);
			if (superCall == null) continue; // a this()-delegating ctor; the super()-calling one gets the init

			InsnList init = new InsnList();
			init.add(new VarInsnNode(Opcodes.ALOAD, 0));
			init.add(new FieldInsnNode(Opcodes.GETSTATIC, PACK, DEFAULT_PARENT_PREDICATE, PREDICATE_DESC));
			init.add(new FieldInsnNode(Opcodes.PUTFIELD, PACK, PARENTS_PREDICATE, PREDICATE_DESC));
			m.instructions.insert(superCall, init);
			m.maxStack = Math.max(m.maxStack, 2);
			changed = true;
		}
		if (!changed) return bytes;

		ForbricLog.info("[Forbric/Mixin] post-mixin repair: seeded Pack.parentsPredicate=DEFAULT after super() — "
				+ "PackMixin's initializer was mis-woven into NeoForge's child-building loop, leaving top-level packs "
				+ "(incl. the vanilla datapack) hidden");
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** The {@code invokespecial Object.<init>()} that opens a super()-calling ctor, or null for a this()-delegating one. */
	private static AbstractInsnNode superInitCall(MethodNode ctor) {
		for (AbstractInsnNode insn = ctor.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.INVOKESPECIAL) continue;
			MethodInsnNode call = (MethodInsnNode) insn;
			if (!call.name.equals("<init>")) continue;
			// The first <init> invokespecial in a ctor is its own chain call: super() (owner Object) or this() (owner
			// Pack). Only the super()-calling ctor should carry the field init; the this()-delegating one inherits it.
			return call.owner.equals("java/lang/Object") ? call : null;
		}
		return null;
	}
}
