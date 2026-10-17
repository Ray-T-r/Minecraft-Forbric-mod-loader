package net.forbric.kernel.transform;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * A mod's registry-remap mixin under names no loader knows: {@code org/quarry/registry/mixin/LedgerMixin} targets
 * {@code MappedRegistry}, implements {@code org/quarry/registry/Rebinds}, shadows the registry's {@code toId} and adds
 * {@code rebind(Object2IntMap, Rebinds$Policy)} and its undo {@code forgetAll()} over it. What {@link RegistrySyncParityInjector} reads a remap contract
 * from, with nothing of fabric-api's in it.
 */
final class RemapContractSamples {
	static final String TARGET = "net/minecraft/core/MappedRegistry";
	static final String INTERFACE = "org/quarry/registry/Rebinds";
	static final String POLICY = INTERFACE + "$Policy";
	static final String NAME = "rebind";
	static final String DESC = "(Lit/unimi/dsi/fastutil/objects/Object2IntMap;L" + POLICY + ";)V";
	static final String MIXIN = "org/quarry/registry/mixin/LedgerMixin";
	static final String UNDO = "forgetAll";

	private RemapContractSamples() {
	}

	/** The mixin, as a class node the way the kernel's discovery reads one. */
	static ClassNode mixin() {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT;
		node.name = MIXIN;
		node.superName = "java/lang/Object";
		node.interfaces.add(INTERFACE);
		AnnotationNode mixin = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		mixin.values = new java.util.ArrayList<>(List.of("value", List.of(Type.getObjectType(TARGET))));
		node.invisibleAnnotations = new java.util.ArrayList<>(List.of(mixin));
		FieldNode toId = new FieldNode(Opcodes.ACC_PRIVATE, "toId", "Ljava/util/Map;", null, null);
		toId.invisibleAnnotations = new java.util.ArrayList<>(List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/Shadow;")));
		node.fields.add(toId);
		MethodNode rebind = new MethodNode(Opcodes.ACC_PUBLIC, NAME, DESC, null, null);
		rebind.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		rebind.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, MIXIN, "toId", "Ljava/util/Map;"));
		rebind.instructions.add(new InsnNode(Opcodes.POP));
		rebind.instructions.add(new InsnNode(Opcodes.RETURN));
		rebind.maxStack = 1;
		rebind.maxLocals = 3;
		node.methods.add(rebind);
		// Its undo: argument-free, over the same storage, declared by the same interface.
		MethodNode forget = new MethodNode(Opcodes.ACC_PUBLIC, UNDO, "()V", null, null);
		forget.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		forget.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, MIXIN, "toId", "Ljava/util/Map;"));
		forget.instructions.add(new InsnNode(Opcodes.POP));
		forget.instructions.add(new InsnNode(Opcodes.RETURN));
		forget.maxStack = 1;
		forget.maxLocals = 1;
		node.methods.add(forget);
		// A public void with the same argument that touches no shadowed storage is not a remap.
		MethodNode note = new MethodNode(Opcodes.ACC_PUBLIC, "note", "(Lit/unimi/dsi/fastutil/objects/Object2IntMap;)V", null, null);
		note.instructions.add(new InsnNode(Opcodes.RETURN));
		note.maxLocals = 2;
		node.methods.add(note);
		return node;
	}

	/** {@code org/quarry/registry/Rebinds}: the interface declaring {@code rebind} and {@code note}. */
	static byte[] rebinds() {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, INTERFACE, null, "java/lang/Object", null);
		writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, NAME, DESC, null, null).visitEnd();
		writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "note", "(Lit/unimi/dsi/fastutil/objects/Object2IntMap;)V", null, null).visitEnd();
		writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, UNDO, "()V", null, null).visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	/** The contracts the injector reads off {@link #mixin()}, its interface served from {@code classes} (internal name to bytes). */
	static List<RegistrySyncParityInjector.RemapContract> contracts(Map<String, byte[]> classes) {
		Function<String, byte[]> resources = path -> path.endsWith(".class") ? classes.get(path.substring(0, path.length() - 6)) : null;
		return RegistrySyncParityInjector.remapContracts(mixin(), resources);
	}

	/** The injector's contract lookup over {@code contracts}: by target, every one for {@code null}. */
	static Function<String, List<RegistrySyncParityInjector.RemapContract>> lookup(List<RegistrySyncParityInjector.RemapContract> contracts) {
		return target -> contracts.stream().filter(contract -> target == null || contract.target().equals(target)).toList();
	}
}
