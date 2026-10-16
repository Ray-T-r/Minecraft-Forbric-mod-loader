/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import net.forbric.kernel.util.ForbricLog;

/**
 * Lets MinecraftForge's block-model data flow through the merged client, which asks NeoForge's model questions.
 *
 * <p>MinecraftForge gives a block model its context as model data: its section compiler takes the block entity's data
 * from the level's {@code ModelDataManager}, asks the model {@code getModelData(level, pos, state, data)} — where a
 * connected-texture model reads its neighbours — and tessellates with
 * {@code ModelBlockRenderer.tesselateBlock(…, seed, data)}, which collects the parts with
 * {@code collectParts(random, parts, data)}. The merged section compiler, renderers and particle code are NeoForge's:
 * they ask {@code collectParts(level, pos, state, random, parts)} and call the vanilla-signature
 * {@code tesselateBlock(…, seed)}. None of MinecraftForge's model-data questions were ever asked, the level had no
 * MinecraftForge manager to ask, and MinecraftForge's tessellation entry — where its mods hook a block's rendering —
 * was called by nothing. fusion's connected textures never connected; Physics Mod's vine hook never ran.
 *
 * <p>Three repairs, each found by shape, none by a mod:
 * <ul>
 *   <li><b>the model's questions</b> — the merged {@code BlockStateModel}, which extends both families' model extensions,
 *       answers NeoForge's contextual {@code collectParts} and {@code particleMaterial} through MinecraftForge's contract
 *       ({@code KernelForgeModelData}). A model that does not take model data is asked exactly NeoForge's question, since
 *       MinecraftForge's defaults pass the data through and drop it; a NeoForge model that answers NeoForge's question
 *       itself keeps its own answer.</li>
 *   <li><b>the level's manager</b> — the merged {@code ClientLevel} lost MinecraftForge's {@code ModelDataManager}
 *       (NeoForge's field and accessor of the same names won the merge), so MinecraftForge's interface default answered
 *       {@code null} and no block entity's model data was ever kept. It gets MinecraftForge's own manager back, built in
 *       its constructor as MinecraftForge builds it.</li>
 *   <li><b>the tessellation entry's edge</b> — where a class keeps the carrier's model-data overload of a method beside
 *       the vanilla signature, and the vanilla signature carries the live body instead of forwarding to the overload,
 *       the vanilla signature starts by entering the overload's edge twin with the model data MinecraftForge's own caller
 *       hands it: a method with the overload's exact signature whose body only marks that it was reached. A guest
 *       injector at the overload's start is given the twin as a second target ({@code MixinForgeModelDataEdge}), so it
 *       runs, and cancels the block's tessellation, wherever the merged game tessellates a block — and still at the
 *       overload itself, which a mod may call.</li>
 * </ul>
 * {@code -Dforbric.forgeModelData=off} leaves every class as merged.
 */
public final class ForgeModelDataFlowTransformer implements ClassTransformer {
	public static final String PROPERTY = "forbric.forgeModelData";

	static final String BLOCK_STATE_MODEL = "net/minecraft/client/renderer/block/dispatch/BlockStateModel";
	static final String NEO_EXTENSION = "net/neoforged/neoforge/client/extensions/BlockStateModelExtension";
	static final String FORGE_EXTENSION = "net/minecraftforge/client/extensions/IForgeBlockStateModel";
	static final String CLIENT_LEVEL = "net/minecraft/client/multiplayer/ClientLevel";
	static final String LEVEL = "net/minecraft/world/level/Level";
	static final String MODEL_DATA = "net/minecraftforge/client/model/data/ModelData";
	static final String MANAGER = "net/minecraftforge/client/model/data/ModelDataManager";
	static final String HELPER = "net/forbric/kernel/runtime/KernelForgeModelData";

	static final String GETTER = "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;";
	static final String POS = "Lnet/minecraft/core/BlockPos;";
	static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
	static final String MODEL = "L" + BLOCK_STATE_MODEL + ";";
	static final String CONTEXT = GETTER + POS + STATE;
	static final String MATERIAL = "Lnet/minecraft/client/resources/model/sprite/Material$Baked;";
	static final String COLLECT = "(" + CONTEXT + "Lnet/minecraft/util/RandomSource;Ljava/util/List;)V";
	static final String PARTICLE = "(" + CONTEXT + ")" + MATERIAL;
	static final String MANAGER_GETTER = "()L" + MANAGER + ";";
	static final String MANAGER_FIELD = "forbric$forgeModelDataManager";
	/** The edge twin of a model-data overload is named after it, with this suffix; the flag field likewise. */
	public static final String EDGE_SUFFIX = "$forbricForgeEdge";
	static final String EDGE_FLAG = "forbric$forgeEdgeReached";

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() {
		return "forbric-forge-model-data";
	}

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("disabled by -D" + PROPERTY + "=off");
		return AnchorSet.of(
				new AnchorSet.Anchor(BLOCK_STATE_MODEL.replace('/', '.'), AnchorSet.Severity.REQUIRED,
						"a MinecraftForge block model is never asked for its model data: connected textures never connect"),
				new AnchorSet.Anchor(CLIENT_LEVEL.replace('/', '.'), AnchorSet.Severity.REQUIRED,
						"the client level answers MinecraftForge's model-data manager with null: no block entity's model data is kept"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0) return bytes;
		String internal = className.replace('.', '/');
		if (!internal.startsWith("net/minecraft/")) return bytes;
		boolean candidate = internal.equals(BLOCK_STATE_MODEL) || internal.equals(CLIENT_LEVEL) || mentions(bytes, MODEL_DATA);
		if (!candidate) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		boolean changed = false;
		if (internal.equals(BLOCK_STATE_MODEL)) changed |= askTheModelsMinecraftForgesQuestions(node);
		if (internal.equals(CLIENT_LEVEL)) changed |= giveTheLevelMinecraftForgesManager(node);
		changed |= edgeTheModelDataOverloads(node) > 0;
		if (!changed) return bytes;
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** The cheap question asked of every game class: does its constant pool spell MinecraftForge's ModelData at all. */
	private static boolean mentions(byte[] bytes, String internalName) {
		byte[] needle = internalName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		outer:
		for (int i = 0; i + needle.length <= bytes.length; i++) {
			for (int j = 0; j < needle.length; j++) if (bytes[i + j] != needle[j]) continue outer;
			return true;
		}
		return false;
	}

	// ---- the model's questions ---------------------------------------------------------------------------------

	/**
	 * NeoForge's contextual {@code collectParts} and {@code particleMaterial} on the merged {@code BlockStateModel},
	 * answered through MinecraftForge's model-data contract. Only on the interface that extends both extensions; a
	 * {@code particleMaterial} already there is replaced only when it is the conflict resolver's own
	 * ({@code return particleMaterial();}) — anything else is a rebuilt base speaking for itself.
	 */
	static boolean askTheModelsMinecraftForgesQuestions(ClassNode node) {
		if ((node.access & Opcodes.ACC_INTERFACE) == 0 || node.interfaces == null
				|| !node.interfaces.contains(NEO_EXTENSION) || !node.interfaces.contains(FORGE_EXTENSION)) return false;
		boolean changed = false;
		if (method(node, "collectParts", COLLECT) == null) {
			MethodNode collect = new MethodNode(Opcodes.ACC_PUBLIC, "collectParts", COLLECT, null, null);
			for (int slot = 0; slot <= 5; slot++) collect.instructions.add(new VarInsnNode(Opcodes.ALOAD, slot));
			collect.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "collectParts",
					"(" + MODEL + CONTEXT + "Lnet/minecraft/util/RandomSource;Ljava/util/List;)V", false));
			collect.instructions.add(new InsnNode(Opcodes.RETURN));
			collect.maxStack = 6;
			collect.maxLocals = 6;
			node.methods.add(collect);
			changed = true;
		}
		MethodNode particle = method(node, "particleMaterial", PARTICLE);
		if (particle == null || isResolversParticle(node, particle)) {
			InsnList body = new InsnList();
			body.add(new VarInsnNode(Opcodes.ALOAD, 0));
			body.add(new VarInsnNode(Opcodes.ALOAD, 1));
			body.add(new VarInsnNode(Opcodes.ALOAD, 2));
			body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "at", "(" + GETTER + POS + ")L" + MODEL_DATA + ";", false));
			body.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, node.name, "particleMaterial", "(L" + MODEL_DATA + ";)" + MATERIAL, true));
			body.add(new InsnNode(Opcodes.ARETURN));
			if (particle == null) {
				particle = new MethodNode(Opcodes.ACC_PUBLIC, "particleMaterial", PARTICLE, null, null);
				node.methods.add(particle);
			}
			particle.instructions = body;
			particle.tryCatchBlocks = new ArrayList<>();
			particle.localVariables = null;
			particle.maxStack = 3;
			particle.maxLocals = 4;
			changed = true;
		}
		if (changed) {
			ForbricLog.info("[Forbric/ModelData] BlockStateModel answers NeoForge's contextual collectParts and particleMaterial "
					+ "through MinecraftForge's model data — a MinecraftForge model (connected textures) was never asked for "
					+ "its context on the merged client; any other model is asked exactly NeoForge's question");
		}
		return changed;
	}

	/** {@code aload_0; invokeinterface particleMaterial()…; areturn}: the conflict resolver's body, nothing else. */
	private static boolean isResolversParticle(ClassNode owner, MethodNode method) {
		List<AbstractInsnNode> real = real(method);
		return real.size() == 3 && real.get(0) instanceof VarInsnNode self && self.getOpcode() == Opcodes.ALOAD && self.var == 0
				&& real.get(1) instanceof MethodInsnNode call && call.owner.equals(owner.name) && call.name.equals("particleMaterial")
				&& call.desc.equals("()" + MATERIAL) && real.get(2).getOpcode() == Opcodes.ARETURN;
	}

	// ---- the level's manager -----------------------------------------------------------------------------------

	/**
	 * MinecraftForge's {@code getModelDataManager()} on the merged {@code ClientLevel}, answering a manager the
	 * constructor builds as MinecraftForge's {@code ClientLevel} does ({@code new ModelDataManager(this)}). Only where the
	 * class has no such accessor: a rebuilt base that kept MinecraftForge's is left as it is.
	 */
	static boolean giveTheLevelMinecraftForgesManager(ClassNode node) {
		if (method(node, "getModelDataManager", MANAGER_GETTER) != null || field(node, MANAGER_FIELD) != null) return false;
		List<MethodNode> constructors = new ArrayList<>();
		for (MethodNode m : node.methods) if (m.name.equals("<init>") && callsSuperConstructor(node, m)) constructors.add(m);
		if (constructors.isEmpty()) return false;
		node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, MANAGER_FIELD, "L" + MANAGER + ";", null, null));
		for (MethodNode ctor : constructors) {
			for (AbstractInsnNode insn : ctor.instructions.toArray()) {
				if (insn.getOpcode() != Opcodes.RETURN) continue;
				InsnList build = new InsnList();
				build.add(new VarInsnNode(Opcodes.ALOAD, 0));
				build.add(new TypeInsnNode(Opcodes.NEW, MANAGER));
				build.add(new InsnNode(Opcodes.DUP));
				build.add(new VarInsnNode(Opcodes.ALOAD, 0));
				build.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, MANAGER, "<init>", "(L" + LEVEL + ";)V", false));
				build.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, MANAGER_FIELD, "L" + MANAGER + ";"));
				ctor.instructions.insertBefore(insn, build);
			}
			ctor.maxStack = Math.max(ctor.maxStack, 4);
		}
		MethodNode getter = new MethodNode(Opcodes.ACC_PUBLIC, "getModelDataManager", MANAGER_GETTER, null, null);
		getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		getter.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, MANAGER_FIELD, "L" + MANAGER + ";"));
		getter.instructions.add(new InsnNode(Opcodes.ARETURN));
		getter.maxStack = 1;
		getter.maxLocals = 1;
		node.methods.add(getter);
		ForbricLog.info("[Forbric/ModelData] ClientLevel keeps MinecraftForge's ModelDataManager again — NeoForge's manager won "
				+ "the merge, so MinecraftForge's accessor answered null and no block entity's model data was kept");
		return true;
	}

	/** A constructor that initialises the object through the superclass, not through another constructor of its own. */
	private static boolean callsSuperConstructor(ClassNode owner, MethodNode ctor) {
		for (AbstractInsnNode insn : ctor.instructions) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL && call.name.equals("<init>")) {
				if (call.owner.equals(owner.superName)) return true;
				if (call.owner.equals(owner.name)) return false;
			}
		}
		return false;
	}

	// ---- the tessellation entry's edge -------------------------------------------------------------------------

	/** A carrier overload that threads MinecraftForge's model data, and the vanilla signature beside it. */
	record Pair(MethodNode vanilla, MethodNode overload, int model, int level, int pos, int state) {
	}

	/**
	 * Every model-data overload of {@code node} whose vanilla signature carries the live body: an instance method returning
	 * nothing whose last parameter is MinecraftForge's model data, beside a method of the same name whose parameters are
	 * the rest; the vanilla signature does not forward to the overload, nothing in the class calls the overload, and the
	 * vanilla signature takes exactly one model, level, position and state — the inputs MinecraftForge's own caller
	 * computes the overload's model data from.
	 */
	static List<Pair> pairs(ClassNode node) {
		List<Pair> found = new ArrayList<>();
		String data = "L" + MODEL_DATA + ";";
		for (MethodNode overload : node.methods) {
			if ((overload.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0 || !overload.desc.endsWith(data + ")V")) continue;
			String vanillaDesc = overload.desc.substring(0, overload.desc.length() - data.length() - 2) + ")V";
			MethodNode vanilla = method(node, overload.name, vanillaDesc);
			if (vanilla == null || (vanilla.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) continue;
			boolean called = false;
			for (MethodNode m : node.methods) {
				for (AbstractInsnNode insn : m.instructions) {
					if (insn instanceof MethodInsnNode call && call.owner.equals(node.name) && call.name.equals(overload.name)
							&& call.desc.equals(overload.desc)) called = true;
				}
			}
			if (called) continue;
			Type[] params = Type.getArgumentTypes(vanillaDesc);
			int model = slotOf(params, MODEL), level = slotOf(params, GETTER), pos = slotOf(params, POS), state = slotOf(params, STATE);
			if (model < 0 || level < 0 || pos < 0 || state < 0) continue;
			found.add(new Pair(vanilla, overload, model, level, pos, state));
		}
		return found;
	}

	/** The local slot of the one parameter of {@code desc}'s type, or -1 when there is none or more than one. */
	private static int slotOf(Type[] params, String desc) {
		int slot = 1, found = -1;
		for (Type p : params) {
			if (p.getDescriptor().equals(desc)) {
				if (found >= 0) return -1;
				found = slot;
			}
			slot += p.getSize();
		}
		return found;
	}

	/**
	 * The edge twin for each {@link #pairs pair}, entered first thing by the vanilla signature. The vanilla signature keeps
	 * its whole body; in front of it, with nothing but the operand stack, it saves the flag, clears it, enters the twin
	 * with its own arguments and the model data MinecraftForge's caller would have computed
	 * ({@code KernelForgeModelData.of(model, level, pos, state)}), restores the flag and returns at once when the twin was
	 * left before its body — a guest's cancellation. Saving and restoring keep a nested tessellation, run from a guest's
	 * handler, from answering for the outer one.
	 */
	static int edgeTheModelDataOverloads(ClassNode node) {
		List<Pair> pairs = pairs(node);
		int edged = 0;
		for (Pair pair : pairs) {
			String twinName = pair.overload().name + EDGE_SUFFIX;
			if (method(node, twinName, pair.overload().desc) != null) continue;
			AbstractInsnNode first = pair.vanilla().instructions.getFirst();
			for (AbstractInsnNode insn = first; insn != null && insn.getOpcode() < 0; insn = insn.getNext()) {
				if (insn instanceof FrameNode) first = null;    // a branch target at the very start: not a shape this edits
				if (first == null) break;
			}
			if (first == null) continue;
			if (field(node, EDGE_FLAG) == null) node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, EDGE_FLAG, "Z", null, null));

			MethodNode twin = new MethodNode(Opcodes.ACC_PRIVATE, twinName, pair.overload().desc, null, null);
			twin.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			twin.instructions.add(new InsnNode(Opcodes.ICONST_1));
			twin.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, EDGE_FLAG, "Z"));
			twin.instructions.add(new InsnNode(Opcodes.RETURN));
			twin.maxStack = 2;
			twin.maxLocals = Type.getArgumentsAndReturnSizes(pair.overload().desc) >> 2;
			node.methods.add(twin);

			InsnList entry = new InsnList();
			entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
			entry.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, EDGE_FLAG, "Z"));                  // [outer]
			entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
			entry.add(new InsnNode(Opcodes.ICONST_0));
			entry.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, EDGE_FLAG, "Z"));                  // [outer]
			entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
			int slot = 1;
			for (Type p : Type.getArgumentTypes(pair.vanilla().desc)) {
				entry.add(new VarInsnNode(p.getOpcode(Opcodes.ILOAD), slot));
				slot += p.getSize();
			}
			entry.add(new VarInsnNode(Opcodes.ALOAD, pair.model()));
			entry.add(new VarInsnNode(Opcodes.ALOAD, pair.level()));
			entry.add(new VarInsnNode(Opcodes.ALOAD, pair.pos()));
			entry.add(new VarInsnNode(Opcodes.ALOAD, pair.state()));
			entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "of", "(" + MODEL + GETTER + POS + STATE + ")L" + MODEL_DATA + ";", false));
			entry.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, twinName, pair.overload().desc, false)); // [outer]
			entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
			entry.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, EDGE_FLAG, "Z"));                  // [outer, reached]
			entry.add(new InsnNode(Opcodes.SWAP));                                                      // [reached, outer]
			entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
			entry.add(new InsnNode(Opcodes.SWAP));
			entry.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, EDGE_FLAG, "Z"));                  // [reached]
			LabelNode body = new LabelNode();
			entry.add(new JumpInsnNode(Opcodes.IFNE, body));
			entry.add(new InsnNode(Opcodes.RETURN));
			entry.add(body);
			entry.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
			pair.vanilla().instructions.insertBefore(first, entry);
			// this + the arguments + the four inputs of the model data, then the overload's own size covers the call
			int overloadSize = Type.getArgumentsAndReturnSizes(pair.overload().desc) >> 2;
			pair.vanilla().maxStack = Math.max(pair.vanilla().maxStack, 1 + overloadSize + 4);
			edged++;
			ForbricLog.info("[Forbric/ModelData] %s.%s now enters MinecraftForge's model-data entry %s first — the merged game "
					+ "calls only the vanilla signature, so nothing reached the carrier's overload, where MinecraftForge's mods "
					+ "hook a block's rendering", node.name.replace('/', '.'), pair.vanilla().name, twinName);
		}
		return edged;
	}

	// ---- helpers ------------------------------------------------------------------------------------------------

	static MethodNode method(ClassNode owner, String name, String desc) {
		for (MethodNode m : owner.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
		return null;
	}

	private static FieldNode field(ClassNode owner, String name) {
		for (FieldNode f : owner.fields) if (f.name.equals(name)) return f;
		return null;
	}

	private static List<AbstractInsnNode> real(MethodNode method) {
		List<AbstractInsnNode> real = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() >= 0) real.add(insn);
		return real;
	}
}
