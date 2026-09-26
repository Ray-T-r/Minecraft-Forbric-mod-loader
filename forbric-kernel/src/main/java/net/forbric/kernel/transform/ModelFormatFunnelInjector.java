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

import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Asks the kernel about a model's format before NeoForge's model deserializer throws on a loader it does not own.
 *
 * <h2>What the merge did</h2>
 *
 * <p>NeoForge won {@code ModelManager.lambda$loadBlockModels$2}, so every block model is read by
 * {@code UnbakedModelParser.parse} through {@code CuboidModel.GSON}, whose adapter for {@code UnbakedModel} is
 * NeoForge's {@code UnbakedModelParser$Deserializer}. That deserializer reads {@code "loader"}, dispatches to a
 * NeoForge {@code UnbakedModelLoader}, and throws {@code Unknown loader} when there is none — before it reaches
 * the vanilla {@code CuboidModel$Deserializer}. On MinecraftForge the same key is read INSIDE that vanilla
 * deserializer (its geometry loaders), and so are the format hooks mods put on it: fusion claims
 * {@code "loader": "fusion:model"} at its HEAD. Fabric's {@code "fabric:type"} is never read at all, because the
 * two fabric-model-loading injectors that dispatched it cannot fit the merged {@code ModelManager}.
 *
 * <p>Measured on sweep90: Traveler's Backpack's 220 backpack block and item models failed to bake
 * ({@code Expected BackpackDynamicModel, instead received ...CuboidModel}), and fusion's connected-texture models
 * were one mounted overlay away from {@code Unknown loader fusion:model}.
 *
 * <h2>The insertion</h2>
 *
 * <p>Immediately after NeoForge stores the unwrapped {@code JsonObject} and immediately before it reads
 * {@code "loader"}:
 * <pre>
 *   aload json; aload context
 *   invokestatic KernelModelFormats.foreign(JsonObject, JsonDeserializationContext)UnbakedModel
 *   dup; ifnull L; areturn
 *   L: pop
 * </pre>
 * Null means "NeoForge's", and everything after {@code L} is NeoForge's code as it was. {@code KernelModelFormats}
 * says what counts as foreign and what is deliberately left unchanged.
 *
 * <p>The only new branch target is {@code L}, so the class is read with its frames expanded and one full frame
 * is added there. The shape is checked rather than assumed — the unwrap, the store, the {@code "loader"} read
 * right after it, and the frame NeoForge's own branch leaves at the method's first join — and any drift stands
 * the pass down with a warning, leaving NeoForge's deserializer exactly as it shipped.
 *
 * <p>{@code -Dforbric.modelFormatFunnel=off} restores that.
 */
public final class ModelFormatFunnelInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.modelFormatFunnel";

	static final String TARGET = "net/neoforged/neoforge/client/model/UnbakedModelParser$Deserializer";
	static final String METHOD = "deserialize";
	static final String DESC = "(Lcom/google/gson/JsonElement;Ljava/lang/reflect/Type;"
			+ "Lcom/google/gson/JsonDeserializationContext;)Lnet/minecraft/client/resources/model/UnbakedModel;";
	static final String FUNNEL_OWNER = "net/forbric/kernel/runtime/KernelModelFormats";
	static final String FUNNEL = "foreign";
	static final String FUNNEL_DESC = "(Lcom/google/gson/JsonObject;Lcom/google/gson/JsonDeserializationContext;)"
			+ "Lnet/minecraft/client/resources/model/UnbakedModel;";

	private static final String JSON_ELEMENT = "com/google/gson/JsonElement";
	private static final String JSON_OBJECT = "com/google/gson/JsonObject";
	private static final String UNBAKED_MODEL = "net/minecraft/client/resources/model/UnbakedModel";
	/** The context is the third parameter of an instance method: slot 3. */
	private static final int CONTEXT_SLOT = 3;
	/** What the locals must be where the object has just been stored; anything else is a reshaped method. */
	private static final List<Object> LOCALS = List.of(TARGET, JSON_ELEMENT, "java/lang/reflect/Type",
			"com/google/gson/JsonDeserializationContext", JSON_OBJECT);

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public String name() {
		return "forbric-model-format-funnel";
	}

	@Override
	public AnchorSet anchors() {
		return AnchorSet.of(new AnchorSet.Anchor(TARGET.replace('/', '.'), AnchorSet.Severity.REQUIRED,
				"NeoForge's model deserializer throws on every loader it did not register, before the vanilla "
						+ "deserializer MinecraftForge's geometry loaders and fusion's model hook read, and ignores "
						+ "fabric:type entirely — those models parse as plain cuboids or fail"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if (!TARGET.equals(className.replace('.', '/')) || !enabled()) return classBytes;
		try {
			ClassNode node = new ClassNode();
			// EXPAND_FRAMES: the new frame is absolute, and a compressed frame after it (NeoForge's "append
			// JsonObject" at its first join) would be read relative to it and double the local.
			new ClassReader(classBytes).accept(node, ClassReader.EXPAND_FRAMES);
			MethodNode deserialize = null;
			for (MethodNode method : node.methods) {
				if (METHOD.equals(method.name) && DESC.equals(method.desc)) deserialize = method;
			}
			if (deserialize == null || deserialize.instructions == null) return classBytes;
			for (AbstractInsnNode insn : deserialize.instructions) {
				if (insn instanceof MethodInsnNode call && FUNNEL_OWNER.equals(call.owner) && FUNNEL.equals(call.name)) {
					return classBytes;                  // already funnelled: idempotent
				}
			}

			VarInsnNode store = objectStore(deserialize);
			if (store == null || !readsLoaderNext(store) || !framedAsExpected(deserialize, store)) {
				ForbricLog.warn("[Forbric/ModelFormats] NeoForge's %s.%s is not the shape this funnel was checked "
						+ "against — leaving it as shipped, so Fabric fabric:type formats and MinecraftForge/fusion "
						+ "\"loader\" formats keep parsing as plain models or failing", TARGET, METHOD);
				return classBytes;
			}

			LabelNode neoForges = new LabelNode();
			InsnList ask = new InsnList();
			ask.add(new VarInsnNode(Opcodes.ALOAD, store.var));
			ask.add(new VarInsnNode(Opcodes.ALOAD, CONTEXT_SLOT));
			ask.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FUNNEL_OWNER, FUNNEL, FUNNEL_DESC, false));
			ask.add(new InsnNode(Opcodes.DUP));
			ask.add(new JumpInsnNode(Opcodes.IFNULL, neoForges));
			ask.add(new InsnNode(Opcodes.ARETURN));
			ask.add(neoForges);
			ask.add(new FrameNode(Opcodes.F_NEW, LOCALS.size(), LOCALS.toArray(), 1, new Object[] {UNBAKED_MODEL}));
			ask.add(new InsnNode(Opcodes.POP));
			deserialize.instructions.insert(store, ask);
			deserialize.maxStack = Math.max(deserialize.maxStack, 2);

			ClassWriter writer = new ClassWriter(0);
			node.accept(writer);
			ForbricLog.info("[Forbric/ModelFormats] NeoForge's model deserializer asks the kernel first about a format "
					+ "it does not own — fabric:type goes to fabric-model-loading's registry, and a \"loader\" NeoForge "
					+ "never registered goes to the vanilla deserializer MinecraftForge's geometry loaders and fusion "
					+ "read, instead of failing as \"Unknown loader\"");
			return writer.toByteArray();
		} catch (RuntimeException e) {
			ForbricLog.warn("[Forbric/ModelFormats] could not funnel NeoForge's model deserializer", e);
			return classBytes;
		}
	}

	/** The {@code ASTORE} of {@code element.getAsJsonObject()}: where NeoForge's dispatch starts. */
	private static VarInsnNode objectStore(MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && JSON_ELEMENT.equals(call.owner)
					&& "getAsJsonObject".equals(call.name) && ("()L" + JSON_OBJECT + ";").equals(call.desc)) {
				AbstractInsnNode next = real(insn.getNext());
				return next instanceof VarInsnNode var && var.getOpcode() == Opcodes.ASTORE
						&& var.var == LOCALS.size() - 1 ? var : null;
			}
		}
		return null;
	}

	/** {@code aload json; ldc "loader"} right after the store — the funnel goes before NeoForge's first look. */
	private static boolean readsLoaderNext(VarInsnNode store) {
		AbstractInsnNode load = real(store.getNext());
		AbstractInsnNode key = load == null ? null : real(load.getNext());
		return load instanceof VarInsnNode var && var.getOpcode() == Opcodes.ALOAD && var.var == store.var
				&& key instanceof LdcInsnNode ldc && "loader".equals(ldc.cst);
	}

	/**
	 * Whether the last frame before the store holds exactly {@code this} and the three parameters, which with the
	 * stored object is the frame written at the new label. A method whose locals are anything else was rewritten.
	 */
	private static boolean framedAsExpected(MethodNode method, VarInsnNode store) {
		for (AbstractInsnNode insn = store.getPrevious(); insn != null; insn = insn.getPrevious()) {
			if (!(insn instanceof FrameNode frame)) continue;
			return frame.type == Opcodes.F_NEW && frame.local != null
					&& LOCALS.subList(0, LOCALS.size() - 1).equals(frame.local);
		}
		return false;
	}

	/** The next instruction that is not a label, line number or frame. */
	private static AbstractInsnNode real(AbstractInsnNode insn) {
		while (insn != null && insn.getOpcode() < 0) insn = insn.getNext();
		return insn;
	}
}
