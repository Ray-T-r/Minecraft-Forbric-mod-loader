/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Shapes the merged base produced and kernel repairs answered, held absent on NeoForge's base and carrier so a
 * base change that brings one back is caught here rather than in a player's game. Each repair was deleted in C3
 * (830f3a46, 660106a5, and the mixin-side lane's) on one-off measurements; these are the standing checks.
 *
 * <p><b>Lambda handles that disagree with their target.</b> An {@code invokedynamic} whose bootstrap handle names a method
 * of its own class with an {@code H_INVOKESTATIC} tag while the method is an instance method (or the other way round), or
 * names a {@code lambda$} method the class does not have, links into an {@code IncompatibleClassChangeError} or
 * {@code NoSuchMethodError} at the first call. The merge could leave that when two carriers' lambdas collided by name;
 * {@code repairLambdaBootstrapHandles} re-tagged them. Checked on the raw jars and on the view the game's pre-Mixin chain
 * hands Mixin ({@link PreMixinView}: the vanilla twins, which copy vanilla lambdas into NeoForge classes, then the compat
 * repairs), which is where a twin copying a vanilla lambda whose name and descriptor collide with a NeoForge lambda of the
 * other static-ness would show.
 *
 * <p><b>A second {@code Bootstrap.bootStrap()} inside the first.</b> On the merged base MinecraftForge's
 * {@code ForgeRegistries.init} called {@code Bootstrap.bootStrap()} from a class initializer that vanilla's own bootstrap
 * triggered, so the nested call returned early and a mod's TAIL injector on {@code bootStrap} ran before vanilla's
 * registries existed ({@code returnFromANestedBootstrapBeforeItsTail}). On NeoForge's base only the game's entry points
 * call it (measured in C3 by a JFR method trace: one entry per process; D25's {@code -Xlog:class+init} instrument was
 * replaced by that count, which measures the re-entry itself). The set below is every caller in the base and carrier.
 *
 * <p><b>A vanilla body moved behind a builder stub.</b> MinecraftForge kept vanilla's
 * {@code UnbakedCuboidGeometry.bake(List, TextureSlots, ModelBaker, ModelState, ModelDebugName)} as a stub that makes a
 * {@code QuadCollection.Builder}, hands it to a same-name overload and returns what it builds, so a Fabric mod's injector
 * on a call vanilla's body makes (Create's normals wrap on {@code FaceBakery.bakeQuad}) bound a method with no such call;
 * {@code MixinBuilderStubRebind} moved it to the overload. It was deleted in C3: on NeoForge's base every builder stub
 * (new builder, hand it to a same-name void overload, return its product) is one vanilla has too.
 */
class NeoForgeBaseStructureCensusStagedTest {
	private static final String BOOTSTRAP = "net/minecraft/server/Bootstrap";

	@Test void noLambdaHandleDisagreesWithItsTarget() throws Exception {
		List<String> raw = new ArrayList<>(), viewed = new ArrayList<>();
		int classes = 0, changed = 0;
		for (Path jar : StagedGameClasses.jars()) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (ZipEntry entry : Collections.list(zip.entries())) {
					String name = entry.getName();
					if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue;
					byte[] bytes;
					try (InputStream in = zip.getInputStream(entry)) { bytes = in.readAllBytes(); }
					String internal = name.substring(0, name.length() - ".class".length());
					classes++;
					raw.addAll(disagreeingHandles(bytes));
					byte[] view = PreMixinView.of(internal, bytes);
					if (view != bytes) {
						changed++;
						viewed.addAll(disagreeingHandles(view));
					}
				}
			}
		}
		assertTrue(classes > 10_000, "premise: the base and carrier were read (" + classes + " classes)");
		assertTrue(changed > 0, "premise: the pre-Mixin view changed some class (the vanilla twins at least)");
		assertEquals(List.of(), raw, "raw jars");
		assertEquals(List.of(), viewed, "the pre-Mixin view (twins + compat repairs)");
	}

	/** The criterion has teeth: a static lambda re-tagged as an instance handle, and a handle to a missing lambda, are both named. */
	@Test void theLambdaCriterionNamesAMistaggedAndAMissingTarget() {
		ClassNode node = new ClassNode();
		node.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "fixture/Lambdas", null, "java/lang/Object", null);
		MethodNode target = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "lambda$run$0", "()V", null, null);
		target.instructions.add(new InsnNode(Opcodes.RETURN));
		node.methods.add(target);
		MethodNode run = new MethodNode(Opcodes.ACC_PUBLIC, "run", "()Ljava/lang/Runnable;", null, null);
		Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
				"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
						+ "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
		for (Handle handle : List.of(new Handle(Opcodes.H_INVOKEVIRTUAL, "fixture/Lambdas", "lambda$run$0", "()V", false),
				new Handle(Opcodes.H_INVOKESTATIC, "fixture/Lambdas", "lambda$run$1", "()V", false),
				new Handle(Opcodes.H_INVOKESTATIC, "fixture/Lambdas", "lambda$run$0", "()V", false))) {
			run.instructions.add(new InvokeDynamicInsnNode("run", "()Ljava/lang/Runnable;", metafactory,
					Type.getType("()V"), handle, Type.getType("()V")));
			run.instructions.add(new InsnNode(Opcodes.POP));
		}
		run.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
		run.instructions.add(new InsnNode(Opcodes.ARETURN));
		node.methods.add(run);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		List<String> found = disagreeingHandles(writer.toByteArray());
		assertEquals(2, found.size(), found.toString());
		assertTrue(found.get(0).contains("lambda$run$0()V tag 5 on a static method"), found.toString());
		assertTrue(found.get(1).contains("lambda$run$1()V names no method"), found.toString());
	}

	@Test void onlyTheGamesEntryPointsEnterBootstrap() throws Exception {
		TreeSet<String> callers = new TreeSet<>();
		for (Path jar : StagedGameClasses.jars()) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (ZipEntry entry : Collections.list(zip.entries())) {
					if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) continue;
					byte[] bytes;
					try (InputStream in = zip.getInputStream(entry)) { bytes = in.readAllBytes(); }
					if (!new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains(BOOTSTRAP)) continue;
					ClassNode node = new ClassNode();
					new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
					for (MethodNode m : node.methods) {
						for (AbstractInsnNode insn : m.instructions) {
							if (insn instanceof MethodInsnNode call && call.owner.equals(BOOTSTRAP) && call.name.equals("bootStrap")
									&& call.desc.equals("()V")) callers.add(node.name + "#" + m.name);
						}
					}
				}
			}
		}
		assertEquals(new TreeSet<>(List.of(
				"net/minecraft/client/main/Main#lambda$main$0",
				"net/minecraft/data/DataGenerator#<clinit>",
				"net/minecraft/data/structures/SnbtDatafixer#main",
				"net/minecraft/gametest/framework/GameTestMainUtil#runGameTestServer",
				"net/minecraft/server/Main#main",
				"net/neoforged/neoforge/data/loading/DatagenModLoader#begin",
				"net/neoforged/neoforge/junit/JUnitMain#bootstrap")), callers,
				"a caller outside the game's, datagen's, gametest's and junit's entry points can re-enter Bootstrap.bootStrap()");
	}

	@Test void noVanillaBodyMovedBehindABuilderStub() throws Exception {
		Path vanillaJar = net.forbric.kernel.TestFixtures.vanillaJar();
		net.forbric.kernel.TestFixtures.requireFiles(net.forbric.kernel.TestFixtures.Fixture.MC_LIBRARIES, "the vanilla client jar", vanillaJar);
		List<String> raw = new ArrayList<>(), viewed = new ArrayList<>();
		int stubs = 0;
		try (ZipFile vanilla = new ZipFile(vanillaJar.toFile())) {
			for (Path jar : StagedGameClasses.jars()) {
				try (ZipFile zip = new ZipFile(jar.toFile())) {
					for (ZipEntry entry : Collections.list(zip.entries())) {
						String name = entry.getName();
						if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue;
						byte[] bytes;
						try (InputStream in = zip.getInputStream(entry)) { bytes = in.readAllBytes(); }
						String internal = name.substring(0, name.length() - ".class".length());
						ZipEntry original = vanilla.getEntry(name);
						ClassNode vanillaNode = null;
						if (original != null) {
							try (InputStream in = vanilla.getInputStream(original)) { vanillaNode = node(in.readAllBytes()); }
						}
						List<String> found = movedBehindBuilderStubs(node(bytes), vanillaNode);
						raw.addAll(found);
						stubs += builderStubs(node(bytes)).size();
						byte[] view = PreMixinView.of(internal, bytes);
						if (view != bytes) viewed.addAll(movedBehindBuilderStubs(node(view), vanillaNode));
					}
				}
			}
		}
		assertTrue(stubs > 0, "premise: the criterion sees vanilla's own builder stubs (CrashReport.getDetails, StringTag's two)");
		assertEquals(List.of(), raw, "raw jars");
		assertEquals(List.of(), viewed, "the pre-Mixin view (twins + compat repairs)");
	}

	/** The criterion has teeth: a stub over an overload is named when vanilla's method is a body, and not when vanilla's is the same stub. */
	@Test void theBuilderStubCriterionNamesOnlyAStubVanillaDoesNotHave() {
		ClassNode stubbed = geometry(true), body = geometry(false);
		assertEquals(List.of("fixture/Geometry#bake(Ljava/util/List;)Ljava/util/List;"), movedBehindBuilderStubs(stubbed, body));
		assertEquals(List.of(), movedBehindBuilderStubs(stubbed, stubbed), "vanilla's own stub is no move");
		assertEquals(List.of(), movedBehindBuilderStubs(body, body));
	}

	/** {@code fixture/Geometry.bake(List)}: a builder stub over {@code bake(Builder, List)}, or a body that builds the list itself. */
	private static ClassNode geometry(boolean stub) {
		ClassNode node = new ClassNode();
		node.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "fixture/Geometry", null, "java/lang/Object", null);
		MethodNode bake = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "bake", "(Ljava/util/List;)Ljava/util/List;", null, null);
		if (stub) {
			bake.instructions.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW, "fixture/Builder"));
			bake.instructions.add(new InsnNode(Opcodes.DUP));
			bake.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "fixture/Builder", "<init>", "()V", false));
			bake.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ASTORE, 1));
			bake.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1));
			bake.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
			bake.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "fixture/Geometry", "bake", "(Lfixture/Builder;Ljava/util/List;)V", false));
			bake.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1));
			bake.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "fixture/Builder", "build", "()Ljava/util/List;", false));
		} else {
			bake.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
		}
		bake.instructions.add(new InsnNode(Opcodes.ARETURN));
		node.methods.add(bake);
		return node;
	}

	/** Every builder stub of {@code current} whose method of the same name and descriptor in {@code vanilla} is not one. */
	static List<String> movedBehindBuilderStubs(ClassNode current, ClassNode vanilla) {
		List<String> out = new ArrayList<>();
		java.util.Set<String> vanillaStubs = vanilla == null ? java.util.Set.of() : new java.util.HashSet<>(builderStubs(vanilla));
		java.util.Set<String> vanillaMethods = new java.util.HashSet<>();
		if (vanilla != null) for (MethodNode m : vanilla.methods) vanillaMethods.add(m.name + m.desc);
		for (String stub : builderStubs(current)) {
			if (vanillaMethods.contains(stub) && !vanillaStubs.contains(stub)) out.add(current.name + "#" + stub);
		}
		return out;
	}

	/**
	 * The methods of {@code node} that are exactly a builder stub: {@code new B()} stored in a local, then (among loads and
	 * constants) one call of a same-name, different-descriptor, void method of the class that takes a {@code B}, then that
	 * local, one no-argument call on {@code B} returning the method's return type, and the return.
	 */
	static List<String> builderStubs(ClassNode node) {
		List<String> out = new ArrayList<>();
		for (MethodNode m : node.methods) {
			List<AbstractInsnNode> code = new ArrayList<>();
			for (AbstractInsnNode insn : m.instructions) if (insn.getOpcode() >= 0) code.add(insn);
			int n = code.size();
			if (n < 8 || !(code.get(0) instanceof org.objectweb.asm.tree.TypeInsnNode created) || created.getOpcode() != Opcodes.NEW
					|| code.get(1).getOpcode() != Opcodes.DUP || !(code.get(2) instanceof MethodInsnNode init) || !init.name.equals("<init>")
					|| !init.owner.equals(created.desc) || !(code.get(3) instanceof org.objectweb.asm.tree.VarInsnNode stored)
					|| stored.getOpcode() != Opcodes.ASTORE || code.get(n - 1).getOpcode() != Opcodes.ARETURN
					|| !(code.get(n - 2) instanceof MethodInsnNode build) || !build.owner.equals(created.desc) || !build.desc.startsWith("()")
					|| !Type.getReturnType(build.desc).equals(Type.getReturnType(m.desc))
					|| !(code.get(n - 3) instanceof org.objectweb.asm.tree.VarInsnNode reloaded) || reloaded.var != stored.var
					|| !(code.get(n - 4) instanceof MethodInsnNode call) || !call.owner.equals(node.name) || !call.name.equals(m.name)
					|| call.desc.equals(m.desc) || Type.getReturnType(call.desc).getSort() != Type.VOID) continue;
			boolean takesBuilder = false;
			for (Type argument : Type.getArgumentTypes(call.desc)) takesBuilder |= argument.getSort() == Type.OBJECT && argument.getInternalName().equals(created.desc);
			if (takesBuilder) out.add(m.name + m.desc);
		}
		return out;
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
		return node;
	}

	/** Each of the class's own lambda handles whose tag contradicts its target's static-ness, or whose lambda target is missing. */
	static List<String> disagreeingHandles(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
		Map<String, MethodNode> methods = new HashMap<>();
		for (MethodNode m : node.methods) methods.put(m.name + m.desc, m);
		List<String> out = new ArrayList<>();
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode insn : m.instructions) {
				if (!(insn instanceof InvokeDynamicInsnNode indy) || indy.bsmArgs == null) continue;
				for (Object arg : indy.bsmArgs) {
					if (!(arg instanceof Handle h) || !h.getOwner().equals(node.name)) continue;
					MethodNode target = methods.get(h.getName() + h.getDesc());
					String where = node.name + "#" + m.name + m.desc + " -> " + h.getName() + h.getDesc();
					if (target == null) {
						if (h.getName().startsWith("lambda$")) out.add(where + " names no method");
						continue;
					}
					boolean isStatic = (target.access & Opcodes.ACC_STATIC) != 0;
					int tag = h.getTag();
					boolean staticTag = tag == Opcodes.H_INVOKESTATIC;
					boolean memberTag = tag == Opcodes.H_INVOKEVIRTUAL || tag == Opcodes.H_INVOKESPECIAL || tag == Opcodes.H_INVOKEINTERFACE;
					if (staticTag && !isStatic) out.add(where + " tag " + tag + " on an instance method");
					if (memberTag && isStatic) out.add(where + " tag " + tag + " on a static method");
				}
			}
		}
		return out;
	}
}
