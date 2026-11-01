/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * No record on NeoForge's base leaves an {@code Optional} component null.
 *
 * <p>The byte merge gave vanilla's {@code ServerStatus} MinecraftForge's extra {@code Optional forgeData} component, and
 * vanilla's own constructors (the five-argument one delegates to the six-argument one) never set it: a status built the
 * vanilla way, as LPLM built one, carried a null where the codec expected an Optional and threw on encoding.
 * {@code MergedRecordOptionalDefaults} filled such components with {@code Optional.empty()} until Stage 4 deleted it.
 * This holds the repair's own defect criterion over the whole base and the NeoForge carrier: a record constructor that
 * does not delegate to another of its own and leaves an {@code Optional} field unset or stores {@code null} into it. The
 * merged base had one (ServerStatus's six-argument constructor, forgeData); NeoForge's base has none. And it builds
 * NeoForge's {@code ServerStatus} through every constructor it has, vanilla's five-argument one among them, and reads
 * every Optional field back.
 */
class NeoForgeBaseRecordOptionalsStagedTest {
	private static final Path STAGED = TestFixtures.stagedRoot();
	private static final Path BASE = STAGED.resolve(TestFixtures.BASE_JAR);
	private static final Path NEO_CARRIER = STAGED.resolve(TestFixtures.NEO_CARRIER_JAR);
	private static final String STATUS = "net/minecraft/network/protocol/status/ServerStatus";
	private static final String OPTIONAL = "Ljava/util/Optional;";
	/** Vanilla's constructor: description, players, version, favicon, enforcesSecureChat. */
	private static final String VANILLA_CTOR = "(Lnet/minecraft/network/chat/Component;" + OPTIONAL + OPTIONAL + OPTIONAL + "Z)V";

	@Test void noRecordInTheBaseOrTheCarrierLeavesAnOptionalComponentNull() throws Exception {
		List<String> left = new ArrayList<>();
		int records = 0;
		for (Path jar : List.of(BASE, NEO_CARRIER)) {
			TestFixtures.requireFile(Fixture.STAGED, jar, jar + " absent");
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();) {
					ZipEntry entry = e.nextElement();
					if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) continue;
					try (InputStream in = zip.getInputStream(entry)) {
						ClassNode node = node(in.readAllBytes());
						if (!"java/lang/Record".equals(node.superName)) continue;
						records++;
						left.addAll(optionalsLeftNull(node));
					}
				}
			}
		}
		assertTrue(records > 1_000, "read every record of the base and carrier, not " + records);
		assertEquals(List.of(), left, "a record constructor leaves an Optional component null");
	}

	/** ServerStatus built the vanilla way (and every other way it has) holds an Optional, never null, in each such field. */
	@Test void serverStatusBuiltThroughEachConstructorHoldsNoNullOptional() throws Exception {
		TestFixtures.require(Fixture.JAVA_25, Runtime.version().feature() >= 25, "the base is class-file 69, which only Java 25 links");
		for (Path jar : List.of(BASE, NEO_CARRIER)) TestFixtures.requireFile(Fixture.STAGED, jar, jar + " absent");
		ClassNode status = node(NativeCoremodParityTest.read(BASE, STATUS));
		assertTrue(status.methods.stream().anyMatch(m -> m.name.equals("<init>") && m.desc.equals(VANILLA_CTOR)),
				"vanilla's five-argument ServerStatus constructor");
		try (URLClassLoader loader = loader(withoutInitializer(NativeCoremodParityTest.read(BASE, STATUS)))) {
			Class<?> type = Class.forName(STATUS.replace('/', '.'), false, loader);
			List<String> nulls = new ArrayList<>();
			int built = 0;
			for (Constructor<?> ctor : type.getDeclaredConstructors()) {
				Class<?>[] params = ctor.getParameterTypes();
				Object[] args = new Object[params.length];
				for (int i = 0; i < params.length; i++) {
					args[i] = params[i] == Optional.class ? Optional.empty() : params[i] == boolean.class ? Boolean.FALSE : null;
				}
				ctor.setAccessible(true);
				Object instance = ctor.newInstance(args);
				built++;
				for (Field field : type.getDeclaredFields()) {
					if (Modifier.isStatic(field.getModifiers()) || field.getType() != Optional.class) continue;
					field.setAccessible(true);
					if (field.get(instance) == null) nulls.add(field.getName() + " after " + ctor);
				}
			}
			assertTrue(built >= 2, "built ServerStatus through its constructors, " + built + " of them");
			assertEquals(List.of(), nulls, "an Optional field is null");
		}
	}

	/** Control: the criterion finds the merged ServerStatus's shape, so an empty answer above is not a blind scanner. */
	@Test void controlTheCriterionFindsAConstructorThatLeavesAnOptionalUnset() {
		assertEquals(List.of("fixture/MergedStatus.<init>(Z)V leaves extra"), optionalsLeftNull(node(mergedShapedRecord("fixture/MergedStatus"))));
	}

	static List<String> optionalsLeftNull(ClassNode record) {
		List<String> optionals = new ArrayList<>();
		for (FieldNode field : record.fields) {
			if ((field.access & Opcodes.ACC_STATIC) == 0 && field.desc.equals(OPTIONAL)) optionals.add(field.name);
		}
		List<String> left = new ArrayList<>();
		if (optionals.isEmpty()) return left;
		for (MethodNode ctor : record.methods) {
			if (!ctor.name.equals("<init>")) continue;
			boolean delegates = false;
			Set<String> stored = new HashSet<>();
			Set<String> nulled = new HashSet<>();
			for (AbstractInsnNode insn : ctor.instructions) {
				if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL && call.name.equals("<init>")
						&& call.owner.equals(record.name)) {
					delegates = true;
				}
				if (insn instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTFIELD && put.owner.equals(record.name)) {
					stored.add(put.name);
					AbstractInsnNode before = put.getPrevious();
					while (before != null && before.getOpcode() < 0) before = before.getPrevious();
					if (before != null && before.getOpcode() == Opcodes.ACONST_NULL) nulled.add(put.name);
				}
			}
			if (delegates) continue;
			for (String optional : optionals) {
				if (!stored.contains(optional) || nulled.contains(optional)) left.add(record.name + "." + ctor.name + ctor.desc + " leaves " + optional);
			}
		}
		return left;
	}

	/** A record with a vanilla component it stores and an extra Optional component its one constructor never sets. */
	private static byte[] mergedShapedRecord(String name) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, name, null, "java/lang/Record", null);
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "modded", "Z", null, null).visitEnd();
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "extra", OPTIONAL, null, null).visitEnd();
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Z)V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Record", "<init>", "()V", false);
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitVarInsn(Opcodes.ILOAD, 1);
		mv.visitFieldInsn(Opcodes.PUTFIELD, name, "modded", "Z");
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** The base, the NeoForge carrier and the libraries ServerStatus's signatures name; ServerStatus as given. */
	private static URLClassLoader loader(byte[] status) throws Exception {
		List<URL> urls = new ArrayList<>(List.of(BASE.toUri().toURL(), NEO_CARRIER.toUri().toURL()));
		for (String pattern : List.of("com/mojang/datafixerupper", "com/mojang/brigadier", "com/google/guava/guava",
				"it/unimi/dsi/fastutil", "org/slf4j/slf4j-api", "io/netty/netty-buffer", "io/netty/netty-common")) {
			Path library = newestUnder(pattern);
			TestFixtures.require(Fixture.MC_LIBRARIES, library != null, "no " + pattern + " jar in the local Minecraft libraries");
			urls.add(library.toUri().toURL());
		}
		String name = STATUS.replace('/', '.');
		return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader()) {
			@Override protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
				if (!className.equals(name)) return super.loadClass(className, resolve);
				synchronized (getClassLoadingLock(className)) {
					Class<?> done = findLoadedClass(className);
					return done != null ? done : defineClass(className, status, 0, status.length);
				}
			}
		};
	}

	/** Its codecs need a bootstrapped game; the constructors read none of them. */
	private static byte[] withoutInitializer(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		node.methods.removeIf(m -> m.name.equals("<clinit>"));
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
		return node;
	}

	private static Path newestUnder(String pattern) throws java.io.IOException {
		Path under = TestFixtures.minecraftDir().resolve("libraries").resolve(pattern);
		if (!Files.isDirectory(under)) return null;
		try (var stream = Files.walk(under)) {
			return stream.filter(f -> f.toString().endsWith(".jar") && !f.toString().contains("sources") && !f.toString().contains("natives"))
					.sorted(Comparator.comparing(f -> f.getFileName().toString())).reduce((a, b) -> b).orElse(null);
		}
	}
}
