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

package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Restores vanilla's field descriptor BESIDE the merged one for the fields the merge re-typed.
 *
 * <p>A JVM field is identified by name AND descriptor. NeoForge widened {@code RangedBowAttackGoal.mob} from
 * {@code Monster} to {@code Mob} and re-typed {@code AttributeSupplier$Builder.builder} from
 * {@code ImmutableMap.Builder} to {@code Map}: source-compatible, descriptor-incompatible. A mod compiled against
 * vanilla reads {@code mob:LMonster;} and gets {@code NoSuchFieldError}; fabric-object-builder's
 * {@code @Accessor} typed {@code ImmutableMap$Builder} cannot bind ({@code InvalidAccessorException} on every
 * fabric-api boot) and every Fabric mod loses the default-attribute registry path. One field cannot serve both
 * readers — a {@code HashMap} is never an {@code ImmutableMap.Builder} — so each row gets a public, non-final
 * TWIN with vanilla's descriptor, written where the merged field is written. Two write shapes:
 * <ul>
 *   <li>NARROW ({@code mob}): after every {@code putfield mob:LMob;} fed by an {@code aload n}, the twin is written
 *       from the same slot through {@code KernelWidenedFields.asMonster} — the guarded cast, null when NeoForge's
 *       widening is in use.</li>
 *   <li>FRESH+DRAIN ({@code builder}): the twin is written as a fresh {@code ImmutableMap.builder()} beside each
 *       Map write, and every reader of the Map begins by draining the twin into it ({@code putIfAbsent}: the
 *       Map's own entries win, Fabric's copy-then-override order). fabric-object-builder only ever
 *       {@code putAll}s into a fresh builder's twin and then {@code build()}s, which the drain serves exactly.</li>
 * </ul>
 * Stand down whole per row when any write is not the recognised shape. {@code -Dforbric.widenedFieldTwins=off}.
 * The ParticleResources repair ships the same two-fields-one-name shape already.
 */
public final class WidenedFieldTwinInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.widenedFieldTwins";

	static final String RUNTIME = "net/forbric/kernel/runtime/KernelWidenedFields";
	static final String IMMUTABLE_MAP = "com/google/common/collect/ImmutableMap";
	static final String IMMUTABLE_MAP_BUILDER_DESC = "L" + IMMUTABLE_MAP + "$Builder;";
	static final String MAP_DESC = "Ljava/util/Map;";
	static final String MONSTER_DESC = "Lnet/minecraft/world/entity/monster/Monster;";
	static final String MOB_DESC = "Lnet/minecraft/world/entity/Mob;";

	enum Shape { NARROW, FRESH_DRAIN }

	/**
	 * @param owner       the class (internal name)
	 * @param name        the field name both descriptors share
	 * @param vanillaDesc the descriptor a vanilla-compiled reader carries — the twin's
	 * @param mergedDesc  the descriptor the merged base declares
	 */
	record Row(String owner, String name, String vanillaDesc, String mergedDesc, Shape shape) {
	}

	static final List<Row> ROWS = List.of(
			new Row("net/minecraft/world/entity/ai/goal/RangedBowAttackGoal", "mob", MONSTER_DESC, MOB_DESC, Shape.NARROW),
			new Row("net/minecraft/world/entity/ai/goal/RangedCrossbowAttackGoal", "mob", MONSTER_DESC, MOB_DESC, Shape.NARROW),
			new Row("net/minecraft/world/entity/ai/attributes/AttributeSupplier$Builder", "builder", IMMUTABLE_MAP_BUILDER_DESC,
					MAP_DESC, Shape.FRESH_DRAIN));

	private int twins;

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public String name() {
		return "forbric:widened-field-twins";
	}

	@Override
	public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("switched off by -D" + PROPERTY);
		List<AnchorSet.Anchor> anchors = new ArrayList<>();
		for (Row row : ROWS) {
			anchors.add(new AnchorSet.Anchor(row.owner().replace('/', '.'), AnchorSet.Severity.REQUIRED,
					"a vanilla-compiled reader of " + simple(row.owner()) + "." + row.name() + " gets NoSuchFieldError"
							+ (row.shape() == Shape.FRESH_DRAIN
									? ", and fabric-object-builder's attribute accessor cannot bind" : "")));
		}
		return AnchorSet.of(anchors.toArray(new AnchorSet.Anchor[0]));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!enabled() || classBytes == null || classBytes.length == 0) return classBytes;
		String internal = className.replace('.', '/');
		Row row = null;
		for (Row r : ROWS) if (r.owner().equals(internal)) row = r;
		if (row == null) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		if (hasField(node, row.name(), row.vanillaDesc())) return classBytes;    // rebuilt base, or a second pass
		if (!hasField(node, row.name(), row.mergedDesc())) {
			ForbricLog.warn("[Forbric/WidenedFields] %s no longer declares %s:%s — the drift this twin answers is gone; "
					+ "delete its row", className, row.name(), row.mergedDesc());
			return classBytes;
		}

		int writes = 0;
		int drains = 0;
		List<Runnable> edits = new ArrayList<>();
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof FieldInsnNode put) || put.getOpcode() != Opcodes.PUTFIELD || !internal.equals(put.owner)
						|| !row.name().equals(put.name) || !row.mergedDesc().equals(put.desc)) {
					continue;
				}
				InsnList twin = new InsnList();
				if (row.shape() == Shape.NARROW) {
					AbstractInsnNode feed = previousReal(put);
					if (!(feed instanceof VarInsnNode var) || var.getOpcode() != Opcodes.ALOAD) {
						ForbricLog.warn("[Forbric/WidenedFields] %s.%s writes %s from something other than a local — not the "
								+ "recognised shape, no twin", className, method.name, row.name());
						return classBytes;
					}
					twin.add(new VarInsnNode(Opcodes.ALOAD, 0));
					twin.add(new VarInsnNode(Opcodes.ALOAD, var.var));
					twin.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "asMonster", "(" + MOB_DESC + ")" + MONSTER_DESC, false));
					twin.add(new FieldInsnNode(Opcodes.PUTFIELD, internal, row.name(), row.vanillaDesc()));
				} else {
					twin.add(new VarInsnNode(Opcodes.ALOAD, 0));
					twin.add(new MethodInsnNode(Opcodes.INVOKESTATIC, IMMUTABLE_MAP, "builder", "()" + IMMUTABLE_MAP_BUILDER_DESC, false));
					twin.add(new FieldInsnNode(Opcodes.PUTFIELD, internal, row.name(), row.vanillaDesc()));
				}
				MethodNode m = method;
				FieldInsnNode anchor = put;
				edits.add(() -> {
					m.instructions.insert(anchor, twin);
					m.maxStack = Math.max(m.maxStack, 2);
				});
				writes++;
			}
		}
		if (writes == 0) {
			ForbricLog.warn("[Forbric/WidenedFields] %s never writes %s:%s — no place to write a twin", className,
					row.name(), row.mergedDesc());
			return classBytes;
		}
		if (row.shape() == Shape.FRESH_DRAIN) {
			for (MethodNode method : node.methods) {
				if ("<init>".equals(method.name) || method.instructions.size() == 0 || !reads(method, internal, row)) continue;
				InsnList drain = new InsnList();
				drain.add(new VarInsnNode(Opcodes.ALOAD, 0));
				drain.add(new FieldInsnNode(Opcodes.GETFIELD, internal, row.name(), row.mergedDesc()));
				drain.add(new VarInsnNode(Opcodes.ALOAD, 0));
				drain.add(new FieldInsnNode(Opcodes.GETFIELD, internal, row.name(), row.vanillaDesc()));
				drain.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "drain",
						"(" + MAP_DESC + IMMUTABLE_MAP_BUILDER_DESC + ")V", false));
				MethodNode m = method;
				edits.add(() -> {
					m.instructions.insert(drain);
					m.maxStack = Math.max(m.maxStack, 2);
				});
				drains++;
			}
		}
		for (Runnable edit : edits) edit.run();
		node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, row.name(), row.vanillaDesc(), null, null));
		twins++;
		ForbricLog.info("[Forbric/WidenedFields] %s: vanilla-descriptor twin %s:%s beside the merged %s (%d write(s), %d "
				+ "drain(s)) — the merge re-typed the field, and a JVM field is its name AND its descriptor", className,
				row.name(), row.vanillaDesc(), row.mergedDesc(), writes, drains);

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static boolean reads(MethodNode method, String owner, Row row) {
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD && owner.equals(f.owner)
					&& row.name().equals(f.name) && row.mergedDesc().equals(f.desc)) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasField(ClassNode node, String name, String desc) {
		for (FieldNode f : node.fields) if (f.name.equals(name) && f.desc.equals(desc)) return true;
		return false;
	}

	private static AbstractInsnNode previousReal(AbstractInsnNode cursor) {
		AbstractInsnNode prev = cursor == null ? null : cursor.getPrevious();
		while (prev != null && prev.getOpcode() < 0) prev = prev.getPrevious();
		return prev;
	}

	private static String simple(String internal) {
		return internal.substring(internal.lastIndexOf('/') + 1);
	}

	/** Twins added so far, for the boot summary. */
	public int twinsAdded() {
		return twins;
	}
}
