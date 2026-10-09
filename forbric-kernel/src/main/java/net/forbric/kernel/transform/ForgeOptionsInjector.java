package net.forbric.kernel.transform;

import java.util.Arrays;
import java.util.List;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Keeps saved bindings until Forge's later key-registration window can consume them. */
public final class ForgeOptionsInjector implements ClassTransformer {
	static final String TARGET = "net.minecraft.client.Options";
	static final String OPTIONS = TARGET.replace('.', '/');
	static final String HELPER = "net/forbric/kernel/runtime/KernelForgeOptions";
	static final String WRITE = "(Ljava/util/Map;[Lnet/minecraft/client/KeyMapping;Ljava/io/PrintWriter;)V";

	static final String CLAIM = "forbric-forge-options#keptUnknownKeys";

	@Override public String name() { return "forbric-forge-options"; }
	/**
	 * The outcome is judged by {@link #CLAIM}, not by whether this transformer edited the class: a merged base that
	 * already kept MinecraftForge's load()/save() (load() reaching load(boolean), the map allocated before the
	 * constructor's first load) needs no edit, and that is not a missing repair.
	 */
	@Override public AnchorSet anchors() {
		return AnchorSet.scanned("net.minecraft.client.Options, judged by the " + CLAIM + " claim");
	}
	@Override public List<Claim> claims() {
		if ("off".equalsIgnoreCase(System.getProperty("forbric.forgeClientInit", "on"))) return List.of();
		return List.of(new Claim(CLAIM, AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"saved Forge key bindings are erased before their keys are registered"))));
	}
	@Override public byte[] transform(String className, byte[] bytes, TransformContext context, ClaimReporter reporter) {
		byte[] result = transform(className, bytes, context);
		if (result != bytes || TARGET.equals(className) && keepsUnknownKeys(bytes)) reporter.hit(CLAIM);
		return result;
	}

	/**
	 * Whether {@code bytes} already keep unknown key bindings for the late registration window: load() delegates to
	 * load(boolean), save() reads the map, and every constructor that loads allocates the map before its first load.
	 */
	static boolean keepsUnknownKeys(byte[] bytes) {
		ClassNode node = new ClassNode();
		try { new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES); } catch (RuntimeException unreadable) { return false; }
		if (!OPTIONS.equals(node.name)) return false;
		MethodNode load = method(node, "load", "()V"), save = method(node, "save", "()V");
		if (load == null || save == null || method(node, "load", "(Z)V") == null) return false;
		if (Arrays.stream(load.instructions.toArray()).noneMatch(i -> i instanceof MethodInsnNode c && c.owner.equals(OPTIONS)
				&& c.name.equals("load") && c.desc.equals("(Z)V"))) return false;
		if (Arrays.stream(save.instructions.toArray()).noneMatch(i -> i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD
				&& f.owner.equals(OPTIONS) && f.name.equals("unknownKeys"))) return false;
		boolean anyLoad = false;
		for (MethodNode constructor : node.methods) {
			if (!constructor.name.equals("<init>")) continue;
			List<AbstractInsnNode> insns = instructions(constructor);
			int write = -1, firstLoad = -1;
			for (int i = 0; i < insns.size(); i++) {
				if (write < 0 && insns.get(i) instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.owner.equals(OPTIONS)
						&& f.name.equals("unknownKeys")) write = i;
				if (firstLoad < 0 && insns.get(i) instanceof MethodInsnNode c && c.owner.equals(OPTIONS) && c.name.equals("load")) firstLoad = i;
			}
			if (firstLoad < 0) continue;
			anyLoad = true;
			if (write < 0 || write > firstLoad) return false;
		}
		return anyLoad;
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!TARGET.equals(className) || "off".equalsIgnoreCase(System.getProperty("forbric.forgeClientInit", "on"))) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		if (!OPTIONS.equals(node.name)) return bytes;
		MethodNode load = method(node, "load", "()V"), late = method(node, "load", "(Z)V"), save = method(node, "save", "()V");
		if (load == null || late == null || save == null) return bytes;
		if (node.fields.stream().filter(f -> f.name.equals("unknownKeys") && f.desc.equals("Ljava/util/Map;")
				&& (f.access & Opcodes.ACC_STATIC) == 0).count() != 1) return bytes;
		if (Arrays.stream(load.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c
				&& c.owner.equals(OPTIONS) && c.name.equals("load") && c.desc.equals("(Z)V"))) return bytes;
		if (Arrays.stream(save.instructions.toArray()).anyMatch(i -> i instanceof FieldInsnNode f
				&& f.owner.equals(OPTIONS) && f.name.equals("unknownKeys"))) return bytes;

		List<MethodNode> constructors = node.methods.stream().filter(m -> m.name.equals("<init>")).toList();
		if (constructors.size() != 1) return bytes;
		MethodNode constructor = constructors.getFirst();
		List<AbstractInsnNode> insns = instructions(constructor);
		int write = -1, initialLoad = -1;
		for (int i = 0; i < insns.size(); i++) {
			var instruction = insns.get(i);
			if (instruction instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD
					&& f.owner.equals(OPTIONS) && f.name.equals("unknownKeys") && f.desc.equals("Ljava/util/Map;")) {
				if (write >= 0) return bytes;
				write = i;
			}
			if (instruction instanceof MethodInsnNode c && c.getOpcode() == Opcodes.INVOKEVIRTUAL
					&& c.owner.equals(OPTIONS) && c.name.equals("load") && c.desc.equals("()V")) {
				if (initialLoad >= 0) return bytes;
				initialLoad = i;
			}
		}
		if (write < 4 || initialLoad < 1 || write <= initialLoad
				|| !loadThis(insns.get(write - 4)) || !loadThis(insns.get(initialLoad - 1))
				|| !(insns.get(write - 3) instanceof TypeInsnNode allocation) || allocation.getOpcode() != Opcodes.NEW
				|| !allocation.desc.equals("java/util/HashMap") || insns.get(write - 2).getOpcode() != Opcodes.DUP
				|| !(insns.get(write - 1) instanceof MethodInsnNode init) || init.getOpcode() != Opcodes.INVOKESPECIAL
				|| !init.owner.equals("java/util/HashMap") || !init.name.equals("<init>") || !init.desc.equals("()V")) return bytes;
		// Only move a straight-line expression, never a label/frame/branch target.
		for (int i = write - 4; i < write; i++) if (insns.get(i).getNext() != insns.get(i + 1)) return bytes;
		List<MethodInsnNode> saves = Arrays.stream(save.instructions.toArray())
				.filter(i -> i instanceof MethodInsnNode c && c.getOpcode() == Opcodes.INVOKEVIRTUAL
						&& c.owner.equals(OPTIONS) && c.name.equals("processOptions")
						&& c.desc.equals("(Lnet/minecraft/client/Options$FieldAccess;)V"))
				.map(i -> (MethodInsnNode) i).toList();
		if (saves.size() != 1) return bytes;
		// Derive the writer local from its constructor; do not pin the carrier's local-slot numbering.
		List<AbstractInsnNode> saveInsns = instructions(save);
		int writerSlot = -1;
		for (int i = 0; i + 1 < saveInsns.size(); i++) {
			if (saveInsns.get(i) instanceof MethodInsnNode c && c.getOpcode() == Opcodes.INVOKESPECIAL
					&& c.owner.equals("java/io/PrintWriter") && c.name.equals("<init>") && c.desc.equals("(Ljava/io/Writer;)V")
					&& saveInsns.get(i + 1) instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE) {
				if (writerSlot >= 0 || saveInsns.indexOf(saves.getFirst()) <= i) return bytes;
				writerSlot = store.var;
			}
		}
		if (writerSlot < 0) return bytes;

		InsnList mapInit = new InsnList();
		for (int i = write - 4; i <= write; i++) {
			AbstractInsnNode instruction = insns.get(i);
			constructor.instructions.remove(instruction);
			mapInit.add(instruction);
		}
		constructor.instructions.insertBefore(insns.get(initialLoad - 1), mapInit);
		// Restore Forge's original four-instruction entry. Its load(false) still uses the merged processOptions,
		// so NeoForge's options/key-modifier support remains authoritative for the normal pass.
		load.instructions.clear();
		load.tryCatchBlocks.clear();
		if (load.localVariables != null) load.localVariables.clear();
		load.visibleLocalVariableAnnotations = null;
		load.invisibleLocalVariableAnnotations = null;
		load.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		load.instructions.add(new InsnNode(Opcodes.ICONST_0));
		load.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OPTIONS, "load", "(Z)V", false));
		load.instructions.add(new InsnNode(Opcodes.RETURN));
		load.maxStack = 2;
		load.maxLocals = 1;
		InsnList pending = new InsnList();
		pending.add(new VarInsnNode(Opcodes.ALOAD, 0));
		pending.add(new FieldInsnNode(Opcodes.GETFIELD, OPTIONS, "unknownKeys", "Ljava/util/Map;"));
		pending.add(new VarInsnNode(Opcodes.ALOAD, 0));
		pending.add(new FieldInsnNode(Opcodes.GETFIELD, OPTIONS, "keyMappings", "[Lnet/minecraft/client/KeyMapping;"));
		pending.add(new VarInsnNode(Opcodes.ALOAD, writerSlot));
		pending.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "writePendingKeys", WRITE, false));
		save.instructions.insert(saves.getFirst(), pending);
		save.maxStack = Math.max(save.maxStack, 3);
		ClassWriter output = new ClassWriter(0);
		node.accept(output);
		ForbricLog.info("[Forbric/ForgeClient] preserved pending key bindings across options saves before Forge registration");
		return output.toByteArray();
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		List<MethodNode> matches = node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)
				&& (m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0).toList();
		return matches.size() == 1 ? matches.getFirst() : null;
	}
	private static List<AbstractInsnNode> instructions(MethodNode method) {
		return Arrays.stream(method.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
	}
	private static boolean loadThis(AbstractInsnNode node) {
		return node instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == 0;
	}
}
