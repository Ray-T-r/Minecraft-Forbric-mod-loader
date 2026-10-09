/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * Which changes count as "this platform changed the method", by the kind of change rather than by any class or method
 * name. A hook in the body, a call to a member the platform added to a game class, and a hook inside a captured lambda
 * all count; a hook inside a NAMED helper belongs to that helper, which is merged on its own. When both sides changed a
 * method and no composition applies, one side's change is lost — and the report must always say so.
 */
class MergeEvidenceTest {
	@TempDir Path directory;

	private static final String MOB = "net/minecraft/demo/Mob";
	private static final String CODECS = "net/minecraft/demo/Codecs";
	private static final String FORGE_HOOKS = "net/minecraftforge/event/Hooks";
	private static final String NEO_HOOKS = "net/neoforged/neoforge/event/Hooks";

	@Test
	void aCallToAMemberThePlatformAddedIsThatPlatformsChange() throws Exception {
		Merge merge = merge(MOB, vanillaMob(), forgeMob(), neo -> {
			MethodNode routed = method(neo, "workWith", "(Ljava/lang/Object;)V");
			routed.instructions.add(new InsnNode(Opcodes.RETURN));
			MethodNode tick = method(neo, "tick", "()V");
			tick.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
			tick.instructions.add(call(MOB, "workWith", "(Ljava/lang/Object;)V"));
			tick.instructions.add(new InsnNode(Opcodes.RETURN));
		});
		// A NeoForge extension call is a NeoForge change: both sides changed tick, and the loss is reported, not silent.
		assertEquals(List.of(MOB + ".workWith"), calls(merge.method("tick")));
		assertTrue(merge.report.contains(MOB + "#tick()V (forge hook lost)"), merge.report);
	}

	@Test
	void aHookInACapturedLambdaIsTheCapturingMethodsChange() throws Exception {
		Merge merge = merge(MOB, vanillaMob(), forgeMob(), neo -> {
			MethodNode lambda = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
					"lambda$tick$0", "()V", null, null);
			lambda.instructions.add(call(NEO_HOOKS, "onTick", "()V"));
			lambda.instructions.add(call(MOB, "work", "()V"));
			lambda.instructions.add(new InsnNode(Opcodes.RETURN));
			neo.methods.add(lambda);
			MethodNode tick = method(neo, "tick", "()V");
			tick.instructions.add(new InvokeDynamicInsnNode("run", "()Ljava/lang/Runnable;", new Handle(Opcodes.H_INVOKESTATIC,
					"java/lang/invoke/LambdaMetafactory", "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;"
					+ "Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
					+ "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
					Type.getType("()V"), new Handle(Opcodes.H_INVOKESTATIC, MOB, "lambda$tick$0", "()V", false),
					Type.getType("()V")));
			tick.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true));
			tick.instructions.add(new InsnNode(Opcodes.RETURN));
		});
		assertEquals(List.of("java/lang/Runnable.run"), calls(merge.method("tick")));
		assertTrue(merge.report.contains(MOB + "#tick()V (forge hook lost)"), merge.report);
	}

	@Test
	void aHookInANamedHelperBelongsToTheHelperNotToItsCaller() throws Exception {
		Merge merge = merge(MOB, vanillaMob(), forgeMob(), neo -> {
			MethodNode tick = method(neo, "tick", "()V");
			tick.instructions.add(call(MOB, "work", "()V"));
			tick.instructions.add(new InsnNode(Opcodes.RETURN));
			MethodNode work = neo.methods.stream().filter(m -> m.name.equals("work")).findFirst().orElseThrow();
			work.instructions.insert(call(NEO_HOOKS, "onWork", "()V"));
		});
		// Neo's tick is vanilla's: only Forge changed it, so Forge's body is taken outright, no conflict at all.
		assertEquals(List.of(FORGE_HOOKS + ".onTick", MOB + ".work"), calls(merge.method("tick")));
		assertFalse(merge.report.contains(MOB + "#tick()V"), merge.report);
		// And the helper keeps the hook its own platform put there.
		assertEquals(List.of(NEO_HOOKS + ".onWork"), calls(merge.method("work")));
	}

	@Test
	void anInitializerStaysWithTheBaseWhoseFieldsItSets() throws Exception {
		Consumer<ClassNode> neoChange = neo -> {
			MethodNode routed = method(neo, "makeWith", "(Ljava/lang/Object;)V");
			routed.instructions.add(new InsnNode(Opcodes.RETURN));
			MethodNode clinit = method(neo, "<clinit>", "()V");
			clinit.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
			clinit.instructions.add(call(CODECS, "makeWith", "(Ljava/lang/Object;)V"));
			setField(clinit);
		};
		ClassNode vanilla = codecs(null), forge = codecs(FORGE_HOOKS);
		Merge merge = merge(CODECS, vanilla, forge, neoChange);
		assertEquals(List.of(CODECS + ".makeWith"), calls(merge.method("<clinit>")));
		assertTrue(merge.report.contains(CODECS + "#<clinit>()V (forge hook lost)"), merge.report);
	}

	private static ClassNode vanillaMob() {
		ClassNode mob = node(MOB);
		MethodNode tick = method(mob, "tick", "()V");
		tick.instructions.add(call(MOB, "work", "()V"));
		tick.instructions.add(new InsnNode(Opcodes.RETURN));
		MethodNode work = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "work", "()V", null, null);
		work.instructions.add(new InsnNode(Opcodes.RETURN));
		mob.methods.add(work);
		return mob;
	}

	private static ClassNode forgeMob() {
		ClassNode mob = vanillaMob();
		mob.methods.get(0).instructions.insert(call(FORGE_HOOKS, "onTick", "()V"));
		return mob;
	}

	private static ClassNode codecs(String hookOwner) {
		ClassNode codecs = node(CODECS);
		codecs.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "READY", "I", null, null));
		MethodNode clinit = method(codecs, "<clinit>", "()V");
		if (hookOwner != null) clinit.instructions.add(call(hookOwner, "register", "()V"));
		setField(clinit);
		return codecs;
	}

	private static void setField(MethodNode clinit) {
		clinit.instructions.add(new InsnNode(Opcodes.ICONST_1));
		clinit.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, CODECS, "READY", "I"));
		clinit.instructions.add(new InsnNode(Opcodes.RETURN));
	}

	private Merge merge(String owner, ClassNode vanilla, ClassNode forge, Consumer<ClassNode> neoChange) throws Exception {
		ClassNode neo = copy(vanilla);
		neo.methods.removeIf(m -> m.name.equals("tick") || m.name.equals("<clinit>"));
		neoChange.accept(neo);
		Path v = jar("vanilla.jar", owner, bytes(vanilla)), f = jar("forge.jar", owner, bytes(forge)), n = jar("neo.jar", owner, bytes(neo));
		Path merged = directory.resolve("merged.jar"), report = directory.resolve("report.txt");
		new MergedBaseBuilder().run(v, f, n, merged, report, null, null);
		try (ZipFile zip = new ZipFile(merged.toFile())) {
			ClassNode output = new ClassNode();
			new ClassReader(zip.getInputStream(zip.getEntry(owner + ".class")).readAllBytes()).accept(output, 0);
			return new Merge(output, Files.readString(report));
		}
	}

	private static ClassNode node(String name) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V17;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = name;
		node.superName = "java/lang/Object";
		return node;
	}

	private static MethodNode method(ClassNode owner, String name, String desc) {
		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
		if (name.equals("<clinit>")) method.access = Opcodes.ACC_STATIC;
		owner.methods.add(method);
		return method;
	}

	private static MethodInsnNode call(String owner, String name, String desc) {
		return new MethodInsnNode(Opcodes.INVOKESTATIC, owner, name, desc, false);
	}

	private static ClassNode copy(ClassNode node) {
		ClassNode copy = new ClassNode();
		new ClassReader(bytes(node)).accept(copy, 0);
		return copy;
	}

	private static byte[] bytes(ClassNode node) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	private Path jar(String name, String owner, byte[] bytes) throws Exception {
		Path path = directory.resolve(name);
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
			zip.putNextEntry(new ZipEntry(owner + ".class"));
			zip.write(bytes);
			zip.closeEntry();
		}
		return path;
	}

	private static List<String> calls(MethodNode method) {
		List<String> out = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof MethodInsnNode call) out.add(call.owner + "." + call.name);
		}
		return out;
	}

	private record Merge(ClassNode node, String report) {
		MethodNode method(String name) {
			return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElse(null);
		}
	}

}
