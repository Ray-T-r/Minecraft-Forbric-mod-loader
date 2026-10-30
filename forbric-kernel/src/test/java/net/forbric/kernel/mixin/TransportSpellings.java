/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * What the transport spelling tests share: fabric-api's released mixins, the staged merged base, the classes it indexes
 * as the ones a Fabric mod was compiled against, and the few edits the tests make — a selector replaced outright, and a
 * method of the same name declared before or after the one a bare name binds. Everything is read with its debug
 * information (the callback transports read local variable names) and fresh on every call.
 */
final class TransportSpellings {
	static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
	static final String WRAP = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";

	private TransportSpellings() { }

	static Path mergedBase() {
		String configured = System.getProperty("forbric.predicateBase");
		Path base = configured == null || configured.isBlank()
				? TestFixtures.stagedBase() : Path.of(configured);
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(base), "staged merged base required: " + base);
		return base;
	}

	static Path fabricApi() {
		String configured = System.getProperty("forbric.fabricApi");
		Path api = configured == null || configured.isBlank() ? TestFixtures.fabricApi() : Path.of(configured);
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(api), "actual Fabric API required: " + api);
		return api;
	}

	/** A released fabric-api mixin, as shipped in {@code module}'s nested jar. */
	static ClassNode fabricMixin(String module, String name) throws IOException {
		try (ZipFile jar = new ZipFile(fabricApi().toFile())) {
			ZipEntry nested = jar.stream().filter(e -> e.getName().startsWith("META-INF/jars/" + module + "-")).findFirst().orElse(null);
			TestFixtures.require(Fixture.STAGED, nested != null, module + " in the Fabric API fixture");
			try (ZipInputStream inner = new ZipInputStream(jar.getInputStream(nested))) {
				for (ZipEntry entry; (entry = inner.getNextEntry()) != null; )
					if (entry.getName().equals(name + ".class")) return parse(inner.readAllBytes());
			}
		}
		throw new AssertionError(name + " absent from " + module);
	}

	/** {@code name} as the merged base defines it. */
	static ClassNode merged(String name) throws IOException {
		try (ZipFile jar = new ZipFile(mergedBase().toFile())) {
			ZipEntry entry = jar.getEntry(name + ".class");
			TestFixtures.require(Fixture.STAGED, entry != null, name + " in the merged base");
			return parse(jar.getInputStream(entry).readAllBytes());
		}
	}

	/** Like {@link #merged}, null where the merged base does not define it. */
	static ClassNode mergedOrNull(String name) throws IOException {
		try (ZipFile jar = new ZipFile(mergedBase().toFile())) {
			ZipEntry entry = jar.getEntry(name + ".class");
			return entry == null ? null : parse(jar.getInputStream(entry).readAllBytes());
		}
	}

	/** {@code name} as the class a mod of {@code ecosystem} was compiled against: the hash-pinned copy the merged base indexes. */
	static ClassNode nativeClass(Ecosystem ecosystem, String name) throws IOException {
		try (ZipFile jar = new ZipFile(mergedBase().toFile())) {
			NativeGameReferences references = new NativeGameReferences(path -> {
				try {
					ZipEntry entry = jar.getEntry(path);
					return entry == null ? null : jar.getInputStream(entry).readAllBytes();
				} catch (IOException unreadable) {
					return null;
				}
			});
			ClassNode node = references.get(ecosystem, name);
			TestFixtures.require(Fixture.STAGED, node != null, "the hash-pinned native " + name + " the merged base indexes");
			return node;
		}
	}

	static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	static byte[] bytes(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** The one injector handler of {@code mixin} whose injector is {@code desc}. */
	static MethodNode handler(ClassNode mixin, String desc) {
		List<MethodNode> found = mixin.methods.stream().filter(m -> MixinFit.injectorOf(m) != null && MixinFit.injectorOf(m).desc.equals(desc)).toList();
		if (found.size() != 1) throw new AssertionError("premise: one " + desc + " handler in " + mixin.name + ", found " + found.size());
		return found.getFirst();
	}

	static Predicate<MethodNode> injector(String desc) {
		return method -> MixinFit.injectorOf(method) != null && MixinFit.injectorOf(method).desc.equals(desc);
	}

	/** Replaces the handler's {@code method} selectors outright. */
	static void select(MethodNode handler, String... selectors) {
		AnnotationNode injector = MixinFit.injectorOf(handler);
		for (int i = 0; i + 1 < injector.values.size(); i += 2) {
			if ("method".equals(injector.values.get(i))) { injector.values.set(i + 1, new ArrayList<>(List.of(selectors))); return; }
		}
		injector.values.add("method");
		injector.values.add(new ArrayList<>(List.of(selectors)));
	}

	/**
	 * Writes the handler's one fully spelled point as {@code form}, whether or not the method it was written for decides
	 * the shorter form: for the look-alikes, where it does not.
	 */
	static void respell(MethodNode handler, PointRespelling.Form form) {
		AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst();
		MixinFit.Member member = MixinFit.parseMember(MixinFit.asString(MixinFit.value(at, "target")));
		if (member == null || member.owner() == null || member.desc() == null) throw new AssertionError("premise: a fully spelled point");
		MixinPlayerWorldCallbackAdapter.set(at, "target", form.spell().apply(member));
	}

	/**
	 * Declares in {@code owner} another method called {@code name}, of descriptor {@code desc} and with {@code access}, first
	 * among the class's methods or last: a bare name binds the first method of that name a class declares.
	 */
	static MethodNode overload(ClassNode owner, String name, String desc, int access, boolean first) {
		MethodNode method = new MethodNode(access, name, desc, null, null);
		org.objectweb.asm.Type returned = org.objectweb.asm.Type.getReturnType(desc);
		if (name.equals("<init>")) {
			method.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
			method.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESPECIAL, owner.superName, "<init>", "()V", false));
		}
		switch (returned.getSort()) {
			case org.objectweb.asm.Type.VOID -> { }
			case org.objectweb.asm.Type.OBJECT, org.objectweb.asm.Type.ARRAY -> method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
			case org.objectweb.asm.Type.LONG -> method.instructions.add(new InsnNode(Opcodes.LCONST_0));
			case org.objectweb.asm.Type.FLOAT -> method.instructions.add(new InsnNode(Opcodes.FCONST_0));
			case org.objectweb.asm.Type.DOUBLE -> method.instructions.add(new InsnNode(Opcodes.DCONST_0));
			default -> method.instructions.add(new InsnNode(Opcodes.ICONST_0));
		}
		method.instructions.add(new InsnNode(returned.getOpcode(Opcodes.IRETURN)));
		method.maxStack = 2;
		method.maxLocals = org.objectweb.asm.Type.getArgumentsAndReturnSizes(desc) >> 2;
		if (first) owner.methods.addFirst(method); else owner.methods.add(method);
		return method;
	}

	/**
	 * Another method {@code owner} declares, neither {@code avoided} nor a constructor or initializer: one of the given
	 * staticness where the class has one.
	 */
	static MethodNode another(ClassNode owner, String avoided, boolean isStatic) {
		List<MethodNode> others = owner.methods.stream().filter(m -> !m.name.equals(avoided) && !m.name.startsWith("<")
				&& (m.access & Opcodes.ACC_SYNTHETIC) == 0).toList();
		if (others.isEmpty()) throw new AssertionError("premise: another method in " + owner.name);
		return others.stream().filter(m -> ((m.access & Opcodes.ACC_STATIC) != 0) == isStatic).findFirst().orElse(others.getFirst());
	}
}
