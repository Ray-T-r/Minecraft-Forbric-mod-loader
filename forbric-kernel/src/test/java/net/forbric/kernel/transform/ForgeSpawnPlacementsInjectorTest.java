/*
 * Copyright 2026 The Forbric Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.util.TraceClassVisitor;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;

class ForgeSpawnPlacementsInjectorTest {
	private static final String TARGET = "net.minecraft.world.entity.SpawnPlacements";
	private static final String INTERNAL = TARGET.replace('.', '/');
	private static final String LOADER = ForeignType.FML_MOD_LOADER.internal(Ecosystem.NEOFORGE);
	private static final String EVENT = ForeignType.SPAWN_PLACEMENT_EVENT.internal(Ecosystem.NEOFORGE);
	private static final String DESC = "(Lnet/neoforged/bus/api/Event;)V";
	private static final String RUNTIME = "net/forbric/kernel/runtime/KernelForgeSpawnPlacements";
	private final ForgeSpawnPlacementsInjector injector = new ForgeSpawnPlacementsInjector();
	@BeforeEach @AfterEach void reset() { System.clearProperty("forbric.forgeSpawnPlacements"); }

	@Test void syntheticPostIsOneDescriptorPreservingExchange() throws Exception {
		ClassNode node = fixture(); method(node).instructions.insertBefore(post(node), new LabelNode()); exchange(write(node));
	}
	@Test void realMergedSeedAndWritebackRemainUnchanged() throws Exception {
		byte[] original = staged("merged-base/patched-mc-merged-26.2.jar", INTERNAL);
		assertEquals(15, opcodes(method(parse(original))).size()); exchange(original);
	}
	@Test void repeatedTransformAndDisabledRepairAreIdentityOperations() {
		byte[] original = write(fixture()), changed = injector.transform(TARGET, original, context()); assertNotSame(original, changed);
		assertSame(changed, injector.transform(TARGET, changed, context()));
		assertEquals(TARGET, injector.anchors().anchors().getFirst().binaryName());
		assertEquals(AnchorSet.Severity.REQUIRED, injector.anchors().anchors().getFirst().severity());
		System.setProperty("forbric.forgeSpawnPlacements", "off");
		assertSame(original, injector.transform(TARGET, original, context())); assertSame(changed, injector.transform(TARGET, changed, context()));
		assertTrue(injector.anchors().anchors().isEmpty());
	}
	@Test void unexpectedProducerOrPostShapesStandDown() {
		reject("instance method", n -> method(n).access &= ~Opcodes.ACC_STATIC);
		reject("changed host descriptor", n -> method(n).desc = "(I)V");
		reject("changed post descriptor", n -> post(n).desc = "(Ljava/lang/Object;)V");
		reject("non-static post", n -> post(n).setOpcode(Opcodes.INVOKEVIRTUAL));
		reject("interface post", n -> post(n).itf = true);
		reject("wrong owner", n -> post(n).owner = ForeignType.FML_MOD_LOADER.internal(Ecosystem.FORGE));
		reject("duplicate post", n -> {
            InsnList extra = new InsnList(); extra.add(new InsnNode(Opcodes.ACONST_NULL));
            extra.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LOADER, "postEvent", DESC, false));
            method(n).instructions.insert(extra);
        });
		reject("already partially bridged", n -> method(n).instructions.insert(post(n), new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "postBothFamilies", DESC, false)));
		reject("constructor changed", n -> constructor(n).desc = "()V");
		reject("another event constructor", n -> constructor(n).owner = "example/OtherEvent");
		reject("another allocation", n -> {
			for (var i : method(n).instructions) if (i instanceof TypeInsnNode t && t.desc.equals(EVENT)) t.desc = "example/OtherEvent";
		});
		reject("event no longer directly supplied to post", n -> method(n).instructions.insertBefore(post(n), new InsnNode(Opcodes.NOP)));
		reject("duplicate declaration", n -> n.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "fireSpawnPlacementEvent", "()V", null, null)));
		reject("wrong bytes", n -> n.name = "example/OtherClass");
		byte[] bytes = write(fixture()); assertSame(bytes, injector.transform("example.OtherClass", bytes, context()));
		assertNull(injector.transform(TARGET, null, context())); byte[] empty = new byte[0]; assertSame(empty, injector.transform(TARGET, empty, context()));
	}

	@Test void realCarrierFieldsAndConstructorsMatchTheReadOnlyBridge() throws Exception {
		String predicate = "L" + INTERNAL + "$SpawnPredicate;", placement = "Lnet/minecraft/world/entity/SpawnPlacementType;",
				height = "Lnet/minecraft/world/level/levelgen/Heightmap$Types;";
		ClassNode vanilla = parse(staged("merged-base/patched-mc-merged-26.2.jar", INTERNAL));
		field(vanilla, "DATA_BY_TYPE", "Ljava/util/Map;", true);
		ClassNode data = parse(staged("merged-base/patched-mc-merged-26.2.jar", INTERNAL + "$Data"));
		field(data, "predicate", predicate, false); field(data, "placement", placement, false); field(data, "heightMap", height, false);
		assertNotNull(named(data, "<init>", "(" + height + placement + predicate + ")V"));
		for (Ecosystem family : List.of(Ecosystem.FORGE, Ecosystem.NEOFORGE)) {
			String owner = ForeignType.SPAWN_PLACEMENT_EVENT.internal(family);
			String jar = family == Ecosystem.FORGE ? "forge-runtime/forge-runtime.jar" : "neoforge-runtime/neoforge-runtime.jar";
			ClassNode event = parse(staged(jar, owner)); field(event, "map", "Ljava/util/Map;", false);
			assertTrue((named(event, "<init>", "(Ljava/util/Map;)V").access & Opcodes.ACC_PUBLIC) != 0);
			ClassNode merged = parse(staged(jar, owner + "$MergedSpawnPredicate"));
			field(merged, "originalPredicate", predicate, false); field(merged, "replacementPredicate", predicate, false);
			field(merged, "andPredicates", "Ljava/util/List;", false); field(merged, "orPredicates", "Ljava/util/List;", false);
			field(merged, "spawnType", placement, false); field(merged, "heightmapType", height, false);
			assertTrue((named(merged, "<init>", "(" + predicate + placement + height + ")V").access & Opcodes.ACC_PUBLIC) != 0);
			assertNotNull(named(merged, "build", "()" + predicate));
		}
	}

	private void exchange(byte[] original) throws Exception {
		ClassNode before = parse(original); assertEquals(LOADER, post(before).owner);
		byte[] changed = injector.transform(TARGET, original, context()); assertNotSame(original, changed);
		ClassNode after = parse(changed);
		MethodInsnNode call = Arrays.stream(method(after).instructions.toArray()).filter(i -> i instanceof MethodInsnNode m && m.owner.equals(RUNTIME))
				.map(i -> (MethodInsnNode) i).findFirst().orElseThrow();
		assertEquals("postBothFamilies", call.name); assertEquals(DESC, call.desc); assertEquals(Opcodes.INVOKESTATIC, call.getOpcode()); assertFalse(call.itf);
		assertEquals(opcodes(method(before)), opcodes(method(after))); new Analyzer<>(new BasicVerifier()).analyze(after.name, method(after));
		call.owner = LOADER; call.name = "postEvent"; assertEquals(trace(before), trace(after), "the seed and merged lambda writeback must remain intact");
	}
	private void reject(String reason, Consumer<ClassNode> change) {
		ClassNode node = fixture(); change.accept(node); byte[] bytes = write(node); assertSame(bytes, injector.transform(TARGET, bytes, context()), reason);
	}
	private static ClassNode fixture() {
		ClassNode node = new ClassNode(); node.version = Opcodes.V21; node.access = Opcodes.ACC_PUBLIC; node.name = INTERNAL; node.superName = "java/lang/Object";
		MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "fireSpawnPlacementEvent", "()V", null, null);
		m.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashMap")); m.instructions.add(new InsnNode(Opcodes.DUP));
		m.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false)); m.instructions.add(new VarInsnNode(Opcodes.ASTORE, 0));
		m.instructions.add(new TypeInsnNode(Opcodes.NEW, EVENT)); m.instructions.add(new InsnNode(Opcodes.DUP)); m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		m.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, EVENT, "<init>", "(Ljava/util/Map;)V", false));
		m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LOADER, "postEvent", DESC, false)); m.instructions.add(new InsnNode(Opcodes.RETURN));
		m.maxStack = 3; m.maxLocals = 1; node.methods.add(m); return node;
	}
	private static void field(ClassNode owner, String name, String descriptor, boolean isStatic) {
		FieldNode field = owner.fields.stream().filter(f -> f.name.equals(name)).findFirst().orElseThrow();
		assertEquals(descriptor, field.desc); assertEquals(isStatic, (field.access & Opcodes.ACC_STATIC) != 0);
		assertTrue((field.access & Opcodes.ACC_PRIVATE) != 0, owner.name + "." + name);
	}
	private static MethodNode method(ClassNode node) { return node.methods.stream().filter(m -> m.name.equals("fireSpawnPlacementEvent")).findFirst().orElseThrow(); }
	private static MethodInsnNode post(ClassNode node) { return Arrays.stream(method(node).instructions.toArray()).filter(i -> i instanceof MethodInsnNode m && m.name.equals("postEvent")).map(i -> (MethodInsnNode) i).findFirst().orElseThrow(); }
	private static MethodInsnNode constructor(ClassNode node) { return Arrays.stream(method(node).instructions.toArray()).filter(i -> i instanceof MethodInsnNode m && m.owner.equals(EVENT) && m.name.equals("<init>")).map(i -> (MethodInsnNode) i).findFirst().orElseThrow(); }
	private static MethodNode named(ClassNode node, String name, String desc) { return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElse(null); }
	private static List<Integer> opcodes(MethodNode m) { return Arrays.stream(m.instructions.toArray()).map(i -> i.getOpcode()).filter(i -> i >= 0).toList(); }
	private static ClassNode parse(byte[] bytes) { ClassNode n = new ClassNode(); new ClassReader(bytes).accept(n, 0); return n; }
	private static byte[] write(ClassNode n) { ClassWriter w = new ClassWriter(0); n.accept(w); return w.toByteArray(); }
	private static String trace(ClassNode n) { StringWriter out = new StringWriter(); n.accept(new TraceClassVisitor(new PrintWriter(out))); return out.toString(); }
	private static TransformContext context() { return new TransformContext(EnvType.CLIENT, false, "mojmap"); }
	private static byte[] staged(String jar, String entry) throws Exception {
		String old = System.getenv("FORBRIC_OLD"); Path run = old == null || old.isBlank() ? Path.of("..", "forbric-loader", "run") : Path.of(old, "run");
		Path path = run.resolve(jar); assumeTrue(Files.isRegularFile(path), "staged artifact absent: " + path);
		try (ZipFile zip = new ZipFile(path.toFile())) { assertNotNull(zip.getEntry(entry + ".class")); return zip.getInputStream(zip.getEntry(entry + ".class")).readAllBytes(); }
	}
}
