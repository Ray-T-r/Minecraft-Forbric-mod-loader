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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.classloading.LoaderProbePolicy.Family;
import net.forbric.kernel.fabric.KernelFabricLauncher;

/**
 * Pins {@link EnvironmentStripTransformer}: Fabric Loader's {@code @Environment} stripping, for Fabric-arbitrated
 * guest classes only.
 *
 * <p>The load-bearing case is CreativeCore's {@code CreativeFabricLoader}: a private {@code @Environment(CLIENT)}
 * method hands an object to a registry whose parameter type lives in a client-only fabric-api module. The JVM
 * verifies that call when it LINKS the class, whether or not the method ever runs, so on a server the whole class
 * fails with {@code NoClassDefFoundError} unless the method is gone first. {@link #methodOnly} is that shape with
 * synthetic names, and the "without the strip" halves prove the failure is real rather than assumed.
 */
class EnvironmentStripTransformerTest {

	private static final String PKG = "forbrictest/envstrip/";
	private static final String MISSING_ITF = "forbrictest/missing/Iface";
	private static final String MISSING_REGISTRY = "forbrictest/missing/Registry";
	private static final String IMPL = PKG + "Impl";

	private static final TransformContext SERVER = new TransformContext(EnvType.SERVER, false, "named");
	private static final TransformContext CLIENT = new TransformContext(EnvType.CLIENT, false, "named");

	private static final Function<String, Family> ALL_FABRIC = name -> Family.FABRIC;

	// ------------------------------------------------------------------------------------------------ synthesis

	private static void environment(AnnotationVisitor av, String side) {
		av.visitEnum("value", "Lnet/fabricmc/api/EnvType;", side);
		av.visitEnd();
	}

	private static void environmentInterface(AnnotationVisitor av, String side, String itf) {
		av.visitEnum("value", "Lnet/fabricmc/api/EnvType;", side);
		av.visit("itf", Type.getObjectType(itf));
		av.visitEnd();
	}

	private static void constructor(ClassWriter cw, java.util.function.Consumer<MethodVisitor> body) {
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		body.accept(init);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
	}

	/** {@code @Environment(side) private void client() { Registry.add(new Impl()); }} -- CreativeFabricLoader's shape. */
	private static void clientOnlyRegistration(ClassWriter cw, String side) {
		MethodVisitor client = cw.visitMethod(Opcodes.ACC_PRIVATE, "client", "()V", null, null);
		environment(client.visitAnnotation(EnvironmentStripTransformer.ENVIRONMENT, false), side);
		client.visitCode();
		client.visitTypeInsn(Opcodes.NEW, IMPL);
		client.visitInsn(Opcodes.DUP);
		client.visitMethodInsn(Opcodes.INVOKESPECIAL, IMPL, "<init>", "()V", false);
		client.visitMethodInsn(Opcodes.INVOKESTATIC, MISSING_REGISTRY, "add", "(L" + MISSING_ITF + ";)V", false);
		client.visitInsn(Opcodes.RETURN);
		client.visitMaxs(0, 0);
		client.visitEnd();
	}

	private static void serverMethod(ClassWriter cw) {
		MethodVisitor server = cw.visitMethod(Opcodes.ACC_PUBLIC, "server", "()I", null, null);
		server.visitCode();
		server.visitIntInsn(Opcodes.BIPUSH, 7);
		server.visitInsn(Opcodes.IRETURN);
		server.visitMaxs(0, 0);
		server.visitEnd();
	}

	/** Only a client-only method naming a type the server does not have. */
	static byte[] methodOnly(String internalName) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null);
		constructor(cw, init -> { });
		clientOnlyRegistration(cw, "CLIENT");
		serverMethod(cw);
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** The client-only implementation the registration hands over; exists, but its interface does not on a server. */
	static byte[] impl() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, IMPL, null, "java/lang/Object",
				new String[] {MISSING_ITF});
		constructor(cw, init -> { });
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * Every member rule at once: a client-only interface, an instance field initialised in the constructor, a
	 * two-slot static field initialised in {@code <clinit>}, a client-only method, and a kept field of each kind so
	 * the rewritten initialisers are shown to leave everything else alone.
	 */
	static byte[] mixed() {
		String name = PKG + "Mixed";
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object",
				new String[] {MISSING_ITF, "java/lang/Runnable"});
		environmentInterface(cw.visitAnnotation(EnvironmentStripTransformer.ENVIRONMENT_INTERFACE, false), "CLIENT",
				MISSING_ITF);

		FieldVisitor f = cw.visitField(Opcodes.ACC_PUBLIC, "clientField", "Ljava/lang/Object;", null, null);
		environment(f.visitAnnotation(EnvironmentStripTransformer.ENVIRONMENT, false), "CLIENT");
		f.visitEnd();
		FieldVisitor s = cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "clientStatic", "J", null, null);
		environment(s.visitAnnotation(EnvironmentStripTransformer.ENVIRONMENT, false), "CLIENT");
		s.visitEnd();
		cw.visitField(Opcodes.ACC_PUBLIC, "kept", "I", null, null).visitEnd();
		cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "keptStatic", "J", null, null).visitEnd();

		constructor(cw, init -> {
			init.visitVarInsn(Opcodes.ALOAD, 0);
			init.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
			init.visitInsn(Opcodes.DUP);
			init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
			init.visitFieldInsn(Opcodes.PUTFIELD, name, "clientField", "Ljava/lang/Object;");
			init.visitVarInsn(Opcodes.ALOAD, 0);
			init.visitInsn(Opcodes.ICONST_3);
			init.visitFieldInsn(Opcodes.PUTFIELD, name, "kept", "I");
		});
		MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
		clinit.visitCode();
		clinit.visitLdcInsn(5L);
		clinit.visitFieldInsn(Opcodes.PUTSTATIC, name, "clientStatic", "J");
		clinit.visitLdcInsn(9L);
		clinit.visitFieldInsn(Opcodes.PUTSTATIC, name, "keptStatic", "J");
		clinit.visitInsn(Opcodes.RETURN);
		clinit.visitMaxs(0, 0);
		clinit.visitEnd();

		MethodVisitor run = cw.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
		run.visitCode();
		run.visitInsn(Opcodes.RETURN);
		run.visitMaxs(0, 0);
		run.visitEnd();
		clientOnlyRegistration(cw, "CLIENT");
		serverMethod(cw);
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** {@code @EnvironmentInterfaces} holding one interface per side. */
	static byte[] container() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, PKG + "Container", null, "java/lang/Object",
				new String[] {MISSING_ITF, "java/io/Serializable"});
		AnnotationVisitor holder = cw.visitAnnotation(EnvironmentStripTransformer.ENVIRONMENT_INTERFACES, false);
		AnnotationVisitor array = holder.visitArray("value");
		environmentInterface(array.visitAnnotation(null, EnvironmentStripTransformer.ENVIRONMENT_INTERFACE), "CLIENT",
				MISSING_ITF);
		environmentInterface(array.visitAnnotation(null, EnvironmentStripTransformer.ENVIRONMENT_INTERFACE), "SERVER",
				"java/io/Serializable");
		array.visitEnd();
		holder.visitEnd();
		constructor(cw, init -> { });
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * A constructor that stores a client-only field and THEN branches, written with computed frames: the swap of
	 * {@code PUTFIELD} for pops must leave the existing StackMapTable valid, which {@link #mixed} (no branches, so no
	 * frames) cannot show. {@code new Branching(flag)} keeps {@code kept} at 0 or sets it to 3.
	 */
	static byte[] branching() {
		String name = PKG + "Branching";
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
		FieldVisitor f = cw.visitField(Opcodes.ACC_PUBLIC, "clientField", "J", null, null);
		environment(f.visitAnnotation(EnvironmentStripTransformer.ENVIRONMENT, false), "CLIENT");
		f.visitEnd();
		cw.visitField(Opcodes.ACC_PUBLIC, "kept", "I", null, null).visitEnd();

		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Z)V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitLdcInsn(11L);
		init.visitFieldInsn(Opcodes.PUTFIELD, name, "clientField", "J");
		Label skip = new Label();
		init.visitVarInsn(Opcodes.ILOAD, 1);
		init.visitJumpInsn(Opcodes.IFEQ, skip);
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitInsn(Opcodes.ICONST_3);
		init.visitFieldInsn(Opcodes.PUTFIELD, name, "kept", "I");
		init.visitLabel(skip);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** An {@code @EnvironmentInterface(CLIENT)} naming an interface the class does not implement. */
	static byte[] unlistedInterface() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, PKG + "Unlisted", null, "java/lang/Object",
				new String[] {"java/lang/Runnable"});
		environmentInterface(cw.visitAnnotation(EnvironmentStripTransformer.ENVIRONMENT_INTERFACE, false), "CLIENT",
				MISSING_ITF);
		constructor(cw, init -> { });
		MethodVisitor run = cw.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
		run.visitCode();
		run.visitInsn(Opcodes.RETURN);
		run.visitMaxs(0, 0);
		run.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A class marked {@code @Environment(side)} as a whole. */
	static byte[] wholeClass(String internalName, String side) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null);
		environment(cw.visitAnnotation(EnvironmentStripTransformer.ENVIRONMENT, false), side);
		constructor(cw, init -> { });
		clientOnlyRegistration(cw, "CLIENT");
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** Loads synthetic classes on demand, so a class that is never offered is exactly as absent as on a server. */
	private static final class Defining extends ClassLoader {
		private final Map<String, byte[]> offered = new HashMap<>();

		Defining() {
			super(EnvironmentStripTransformerTest.class.getClassLoader());
		}

		Defining offer(byte[] bytes) {
			offered.put(new ClassReader(bytes).getClassName().replace('/', '.'), bytes);
			return this;
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = offered.get(name);
			if (bytes == null) throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}
	}

	private static EnvironmentStripTransformer strip(Function<String, Family> familyOf) {
		return new EnvironmentStripTransformer(familyOf, EnvironmentStripTransformer.Mode.ON);
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static Set<String> methods(byte[] bytes) {
		Set<String> out = new TreeSet<>();
		for (MethodNode m : node(bytes).methods) out.add(m.name + m.desc);
		return out;
	}

	private static Set<String> fields(byte[] bytes) {
		Set<String> out = new TreeSet<>();
		for (FieldNode f : node(bytes).fields) out.add(f.name + f.desc);
		return out;
	}

	// ------------------------------------------------------------------------------------------------ the rules

	@Test
	void theDescriptorsAreTheAnnotationsFabricModsAreCompiledAgainst() {
		// The transformer names them as strings so it never loads them; the vendored copies are the same types.
		assertEquals(Type.getDescriptor(net.fabricmc.api.Environment.class), EnvironmentStripTransformer.ENVIRONMENT);
		assertEquals(Type.getDescriptor(net.fabricmc.api.EnvironmentInterface.class),
				EnvironmentStripTransformer.ENVIRONMENT_INTERFACE);
		assertEquals(Type.getDescriptor(net.fabricmc.api.EnvironmentInterfaces.class),
				EnvironmentStripTransformer.ENVIRONMENT_INTERFACES);
	}

	@Test
	void withoutTheStripAClientOnlyMethodKillsTheWholeClassOnAServer() {
		// The premise, measured rather than assumed: the method never runs, and linking the class still needs
		// the interface, because the verifier must know whether Impl is assignable to it.
		Defining loader = new Defining().offer(methodOnly(PKG + "MethodOnly")).offer(impl());
		NoClassDefFoundError error = assertThrows(NoClassDefFoundError.class,
				() -> Class.forName("forbrictest.envstrip.MethodOnly", true, loader));
		assertTrue(String.valueOf(error.getMessage()).contains(MISSING_ITF), error.toString());
	}

	@Test
	void onTheServerTheClientOnlyMethodIsGoneAndTheClassLinksAndRuns() throws Exception {
		byte[] raw = methodOnly(PKG + "MethodOnly");
		byte[] out = strip(ALL_FABRIC).transform("forbrictest.envstrip.MethodOnly", raw, SERVER);

		assertFalse(methods(out).contains("client()V"), "the @Environment(CLIENT) method must be removed");
		assertEquals(Set.of("<init>()V", "server()I"), methods(out));

		Class<?> linked = Class.forName("forbrictest.envstrip.MethodOnly", true, new Defining().offer(out).offer(impl()));
		Object instance = linked.getConstructor().newInstance();
		assertEquals(7, linked.getMethod("server").invoke(instance));
	}

	@Test
	void everyMemberRuleOfFabricsClassStripperHolds() throws Exception {
		byte[] out = strip(ALL_FABRIC).transform("forbrictest.envstrip.Mixed", mixed(), SERVER);

		assertEquals(List.of("java/lang/Runnable"), node(out).interfaces, "the client-only interface must go");
		assertEquals(Set.of("kept" + "I", "keptStatic" + "J"), fields(out));
		assertFalse(methods(out).contains("client()V"));

		// Neither initialiser may still store into a field that no longer exists.
		for (MethodNode m : node(out).methods) {
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof FieldInsnNode field) {
					assertFalse(field.name.startsWith("client"), m.name + " still stores " + field.name);
				}
			}
		}

		// And the class really does define, initialise and construct with the stores replaced by pops -- the two-slot
		// static included, which a plain POP would leave half on the stack.
		Class<?> mixed = Class.forName("forbrictest.envstrip.Mixed", true, new Defining().offer(out).offer(impl()));
		Object instance = mixed.getConstructor().newInstance();
		Field kept = mixed.getField("kept");
		Field keptStatic = mixed.getField("keptStatic");
		assertEquals(3, kept.getInt(instance));
		assertEquals(9L, keptStatic.getLong(null));
		assertTrue(instance instanceof Runnable);
	}

	@Test
	void withoutTheStripTheMixedClassCannotEvenBeDefined() {
		Defining loader = new Defining().offer(mixed()).offer(impl());
		assertThrows(NoClassDefFoundError.class, () -> Class.forName("forbrictest.envstrip.Mixed", true, loader));
	}

	@Test
	void theContainerAnnotationStripsPerSide() {
		EnvironmentStripTransformer strip = strip(ALL_FABRIC);

		assertEquals(List.of("java/io/Serializable"),
				node(strip.transform("forbrictest.envstrip.Container", container(), SERVER)).interfaces);
		assertEquals(List.of(MISSING_ITF),
				node(strip.transform("forbrictest.envstrip.Container", container(), CLIENT)).interfaces);
	}

	@Test
	void anInterfaceTheClassDoesNotImplementIsNothingToStrip() {
		// Fabric would rewrite the class to the same interface list; counting it made the class look edited and the
		// log say "removed interface" for one that was never there.
		byte[] raw = unlistedInterface();
		assertSame(raw, strip(ALL_FABRIC).transform("forbrictest.envstrip.Unlisted", raw, SERVER));
		assertTrue(EnvironmentStripTransformer.Plan.read(raw, "SERVER").isEmpty());
	}

	@Test
	void runningTheStripOnItsOwnOutputChangesNothing() {
		// The chain can see a class twice (Mixin's view, then the definition once the soft cache let go). The second
		// pass must be a no-op: the removed interface is no longer listed, so there is nothing left to remove.
		EnvironmentStripTransformer strip = strip(ALL_FABRIC);
		byte[] once = strip.transform("forbrictest.envstrip.Mixed", mixed(), SERVER);
		assertSame(once, strip.transform("forbrictest.envstrip.Mixed", once, SERVER));
	}

	@Test
	void anExistingStackMapTableSurvivesTheStoreBecomingPops() throws Exception {
		byte[] raw = branching();
		byte[] out = strip(ALL_FABRIC).transform("forbrictest.envstrip.Branching", raw, SERVER);

		assertEquals(Set.of("kept" + "I"), fields(out));
		boolean framed = false;
		for (MethodNode m : node(out).methods) {
			for (AbstractInsnNode insn : m.instructions) framed |= insn instanceof FrameNode;
		}
		assertTrue(framed, "the fixture must carry a frame for this to prove anything");

		// The verifier checks every frame when it links the class; both branches then run.
		Class<?> branching = Class.forName("forbrictest.envstrip.Branching", true, new Defining().offer(out));
		Field kept = branching.getField("kept");
		assertEquals(3, kept.getInt(branching.getConstructor(boolean.class).newInstance(true)));
		assertEquals(0, kept.getInt(branching.getConstructor(boolean.class).newInstance(false)));
	}

	@Test
	void onTheMatchingSideTheSameArrayComesBack() {
		// Byte identity, not equality: the chain, the pre-mixin cache and the ledger all read "same array" as "no edit".
		byte[] raw = mixed();
		assertSame(raw, strip(ALL_FABRIC).transform("forbrictest.envstrip.Mixed", raw, CLIENT));
	}

	@Test
	void onlyFabricArbitratedClassesAreStripped() {
		// NeoForge and MinecraftForge strip nothing, so a class they own must reach the game as its jar has it -- a
		// universal jar arbitrated to NeoForge included. The merged base, carriers and libraries answer null.
		for (Family family : new Family[] {Family.NEOFORGE, Family.FORGE, null}) {
			byte[] raw = methodOnly(PKG + "MethodOnly");
			assertSame(raw, strip(name -> family).transform("forbrictest.envstrip.MethodOnly", raw, SERVER),
					"stripped a class owned by " + family);
		}
	}

	@Test
	void aClassThatNamesNoEnvironmentAnnotationIsNeverLookedUp() {
		// The family lookup is a findResource; the byte scan in front of it is what keeps it off the ~30 000 classes
		// that carry no annotation.
		AtomicInteger lookups = new AtomicInteger();
		EnvironmentStripTransformer strip = strip(name -> {
			lookups.incrementAndGet();
			return Family.FABRIC;
		});
		byte[] plain = impl();

		assertSame(plain, strip.transform("forbrictest.envstrip.Impl", plain, SERVER));
		assertEquals(0, lookups.get());
	}

	@Test
	void minecraftsOwnPackagesAreLeftAsFabricLeavesThemOutsideDevelopment() {
		EnvironmentStripTransformer strip = strip(ALL_FABRIC);
		byte[] asGame = methodOnly("net/minecraft/world/FakeFromAMod");
		assertSame(asGame, strip.transform("net.minecraft.world.FakeFromAMod", asGame, SERVER));
		byte[] defaultPackage = methodOnly("NoPackage");
		assertSame(defaultPackage, strip.transform("NoPackage", defaultPackage, SERVER));

		TransformContext dev = new TransformContext(EnvType.SERVER, true, "named");
		assertNotSame(asGame, strip.transform("net.minecraft.world.FakeFromAMod", asGame, dev),
				"in a development environment Fabric strips the game too");
	}

	@Test
	void aWholeClassForTheOtherSideLoadsUnchangedAndIsReportedOnce() {
		EnvironmentStripTransformer strip = strip(ALL_FABRIC);
		byte[] raw = wholeClass(PKG + "WholeClient", "CLIENT");

		assertSame(raw, strip.transform("forbrictest.envstrip.WholeClient", raw, SERVER));
		assertSame(raw, strip.transform("forbrictest.envstrip.WholeClient", raw, SERVER));
		assertEquals(Set.of("forbrictest.envstrip.WholeClient"), strip.wholeClassMismatches());

		// On its own side it is an ordinary class, member rules and all.
		assertSame(raw, strip.transform("forbrictest.envstrip.WholeClient", raw, CLIENT));
	}

	@Test
	void theWholeClassReportNamesTheSideAndDoesNotClaimTheClassWorks() {
		// CreativeCore's CreativeHudElement is whole-class CLIENT and implements HudElement, which no server has:
		// the old wording's "the merged base carries the types it names" was false for the very case at hand.
		ByteArrayOutputStream log = new ByteArrayOutputStream();
		PrintStream err = System.err;
		try {
			System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
			byte[] raw = wholeClass(PKG + "WholeReported", "CLIENT");
			strip(ALL_FABRIC).transform("forbrictest.envstrip.WholeReported", raw, SERVER);
		} finally {
			System.setErr(err);
		}
		String text = log.toString(StandardCharsets.UTF_8);
		assertTrue(text.contains("forbrictest.envstrip.WholeReported is marked @Environment(CLIENT) but this is the "
				+ "SERVER; Forbric loads it unchanged instead of refusing it as Fabric Loader does "
				+ "(-Dforbric.envStrip=strict refuses it)"), text);
		assertFalse(text.contains("carries the types"), text);
	}

	@Test
	void strictRefusesAWholeClassWithFabricsOwnMessage() {
		EnvironmentStripTransformer strict =
				new EnvironmentStripTransformer(ALL_FABRIC, EnvironmentStripTransformer.Mode.STRICT);
		byte[] raw = wholeClass(PKG + "WholeClient", "CLIENT");

		IllegalStateException refused = assertThrows(IllegalStateException.class,
				() -> strict.transform("forbrictest.envstrip.WholeClient", raw, SERVER));
		assertEquals("Cannot load class forbrictest.envstrip.WholeClient in environment type SERVER",
				refused.getMessage());
		// Strict changes nothing else.
		assertFalse(methods(strict.transform("forbrictest.envstrip.Mixed", mixed(), SERVER)).contains("client()V"));
	}

	@Test
	void offIsANoOp() {
		byte[] raw = mixed();
		EnvironmentStripTransformer off = new EnvironmentStripTransformer(ALL_FABRIC, EnvironmentStripTransformer.Mode.OFF);
		assertSame(raw, off.transform("forbrictest.envstrip.Mixed", raw, SERVER));
	}

	// ------------------------------------------------------------------------------------------------ the switch

	private static EnvironmentStripTransformer.Mode modeWith(String envStrip, String fabricDisable) {
		String oldEnv = System.getProperty(EnvironmentStripTransformer.SWITCH);
		String oldFabric = System.getProperty(EnvironmentStripTransformer.FABRIC_SWITCH);
		try {
			set(EnvironmentStripTransformer.SWITCH, envStrip);
			set(EnvironmentStripTransformer.FABRIC_SWITCH, fabricDisable);
			return EnvironmentStripTransformer.configuredMode();
		} finally {
			set(EnvironmentStripTransformer.SWITCH, oldEnv);
			set(EnvironmentStripTransformer.FABRIC_SWITCH, oldFabric);
		}
	}

	private static void set(String key, String value) {
		if (value == null) System.clearProperty(key);
		else System.setProperty(key, value);
	}

	@Test
	void theSwitchReadsOffOnAndStrict() {
		assertEquals(EnvironmentStripTransformer.Mode.ON, modeWith(null, null), "on by default");
		assertEquals(EnvironmentStripTransformer.Mode.OFF, modeWith("off", null));
		assertEquals(EnvironmentStripTransformer.Mode.STRICT, modeWith("strict", null));
		assertEquals(EnvironmentStripTransformer.Mode.ON, modeWith("sideways", null), "an unknown value is on");
	}

	@Test
	void fabricsOwnSwitchIsAnAliasForOffReadTheWayFabricReadsIt() {
		// SystemProperties.isSet: present and not "false". A bare -Dfabric.disableEnvironmentStrip is "".
		assertEquals(EnvironmentStripTransformer.Mode.OFF, modeWith(null, ""));
		assertEquals(EnvironmentStripTransformer.Mode.OFF, modeWith(null, "true"));
		assertEquals(EnvironmentStripTransformer.Mode.OFF, modeWith("strict", "true"), "it only ever means off");
		assertEquals(EnvironmentStripTransformer.Mode.ON, modeWith(null, "false"));
	}

	@Test
	void switchedOffNothingIsRegistered() {
		String old = System.getProperty(EnvironmentStripTransformer.SWITCH);
		try {
			System.setProperty(EnvironmentStripTransformer.SWITCH, "off");
			assertNull(EnvironmentStripTransformer.configured(ALL_FABRIC));
			System.setProperty(EnvironmentStripTransformer.SWITCH, "on");
			assertEquals(EnvironmentStripTransformer.Mode.ON, EnvironmentStripTransformer.configured(ALL_FABRIC).mode());
		} finally {
			set(EnvironmentStripTransformer.SWITCH, old);
		}
	}

	// ------------------------------------------------------------------------------------------------ the chain

	@Test
	void inTheChainItRunsBeforeTheAccessPhase() {
		// On Fabric the strip and the access widener run in one pass with the strip first, so a widener naming a
		// stripped member matches nothing. ENV_STRIP before ACCESS keeps that.
		List<Set<String>> seenByAccess = new ArrayList<>();
		TransformChain chain = new TransformChain();
		chain.register(TransformPhase.ACCESS, (name, bytes, context) -> {
			seenByAccess.add(methods(bytes));
			return bytes;
		});
		chain.register(TransformPhase.ENV_STRIP, strip(ALL_FABRIC));

		chain.applyBeforeMixin("forbrictest.envstrip.Mixed", mixed(), SERVER);

		assertEquals(1, seenByAccess.size());
		assertFalse(seenByAccess.get(0).contains("client()V"), "ACCESS saw the class before the strip");
	}

	@Test
	void kernelBootRegistersItIntoTheEnvStripPhase() throws Exception {
		// Read from the compiled class, as TransformerRegistrationOrderTest does: KernelBoot.java has defeated grep.
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
				"net", "forbric", "kernel", "boot", "KernelBoot.class");
		assumeTrue(Files.isRegularFile(compiled), "KernelBoot not compiled yet");
		ClassNode boot = node(Files.readAllBytes(compiled));

		boolean phase = false;
		boolean factory = false;
		boolean byResource = false;
		for (MethodNode m : boot.methods) {
			if (!m.name.equals("launch")) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof FieldInsnNode field && field.owner.equals("net/forbric/kernel/transform/TransformPhase")
						&& field.name.equals("ENV_STRIP")) phase = true;
				if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
						&& call.owner.equals("net/forbric/kernel/transform/EnvironmentStripTransformer")
						&& call.name.equals("configured")) factory = true;
				// loader::familyOfResource, the lookup Mixin's view and the definition share. loader::familyOfClass
				// compiles and passes every other test here while leaving Mixin's view, and every mixin class,
				// unstripped.
				if (insn instanceof InvokeDynamicInsnNode indy) {
					for (Object arg : indy.bsmArgs) {
						if (arg instanceof Handle handle
								&& handle.getOwner().equals("net/forbric/kernel/classloading/ForbricClassLoader")
								&& handle.getName().equals("familyOfResource")) byResource = true;
					}
				}
			}
		}
		assertTrue(factory, "KernelBoot.launch no longer builds the environment strip");
		assertTrue(phase, "KernelBoot.launch no longer registers anything into ENV_STRIP");
		assertTrue(byResource, "KernelBoot.launch no longer hands the strip ForbricClassLoader::familyOfResource");
	}

	// ------------------------------------------------------------------------------------------------ the loader

	private static Path jar(Path file, byte[]... classes) throws Exception {
		try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
			for (byte[] bytes : classes) {
				zip.putNextEntry(new ZipEntry(new ClassReader(bytes).getClassName() + ".class"));
				zip.write(bytes);
				zip.closeEntry();
			}
		}
		return file;
	}

	@Test
	void throughTheRealLoaderMixinsViewAndTheDefinedClassAreBothStripped(@TempDir Path dir) throws Exception {
		Path fabric = jar(dir.resolve("creative-fabric.jar"), methodOnly(PKG + "MethodOnly"), impl());
		Path neo = jar(dir.resolve("universal-won-by-neoforge.jar"), methodOnly(PKG + "NeoOnly"));
		Path unowned = jar(dir.resolve("merged-base.jar"), methodOnly(PKG + "Unowned"));

		try (ForbricClassLoader loader = new ForbricClassLoader(new URL[] {
				fabric.toUri().toURL(), neo.toUri().toURL(), unowned.toUri().toURL()}, getClass().getClassLoader())) {
			loader.setJarFamilies(Map.of(fabric, Family.FABRIC, neo, Family.NEOFORGE));
			TransformChain chain = new TransformChain();
			chain.register(TransformPhase.ENV_STRIP, strip(loader::familyOfResource));
			loader.setTransformer((name, bytes) -> chain.applyBeforeMixin(name, bytes, SERVER));

			// Mixin asks before anything is defined; familyOfClass has no answer yet, familyOfResource does.
			assertNull(loader.familyOfClass("forbrictest.envstrip.MethodOnly"));
			assertEquals(Family.FABRIC, loader.familyOfResource("forbrictest.envstrip.MethodOnly"));
			assertEquals(Family.NEOFORGE, loader.familyOfResource("forbrictest.envstrip.NeoOnly"));
			assertNull(loader.familyOfResource("forbrictest.envstrip.Unowned"));
			assertNull(loader.familyOfResource("forbrictest.envstrip.Nowhere"));

			assertFalse(methods(loader.getPreMixinClassBytes("forbrictest.envstrip.MethodOnly")).contains("client()V"),
					"Mixin's view of the class kept the client-only method");

			Class<?> linked = Class.forName("forbrictest.envstrip.MethodOnly", true, loader);
			assertEquals(7, linked.getMethod("server").invoke(linked.getConstructor().newInstance()));

			// The NeoForge-owned and unowned copies reach the game as their jars have them, failure included.
			try (ZipFile raw = new ZipFile(unowned.toFile())) {
				byte[] original = raw.getInputStream(raw.getEntry(PKG + "Unowned.class")).readAllBytes();
				assertArrayEquals(original, loader.getPreMixinClassBytes("forbrictest.envstrip.Unowned"));
			}
			assertThrows(NoClassDefFoundError.class, () -> Class.forName("forbrictest.envstrip.NeoOnly", true, loader));
		}
	}

	@Test
	void switchedOffTheRealLoaderFailsTheWayEveryServerDid(@TempDir Path dir) throws Exception {
		// The differential: the same jar, the same loader, the strip off -- CreativeCore's NoClassDefFoundError.
		Path fabric = jar(dir.resolve("creative-fabric.jar"), methodOnly(PKG + "MethodOnly"), impl());
		try (ForbricClassLoader loader = new ForbricClassLoader(new URL[] {fabric.toUri().toURL()},
				getClass().getClassLoader())) {
			loader.setJarFamilies(Map.of(fabric, Family.FABRIC));
			TransformChain chain = new TransformChain();
			chain.register(TransformPhase.ENV_STRIP,
					new EnvironmentStripTransformer(loader::familyOfResource, EnvironmentStripTransformer.Mode.OFF));
			loader.setTransformer((name, bytes) -> chain.applyBeforeMixin(name, bytes, SERVER));

			assertThrows(NoClassDefFoundError.class, () -> Class.forName("forbrictest.envstrip.MethodOnly", true, loader));
		}
	}

	/** Adds a jar through Fabric's launcher API, as CustomSkinLoader does, and asks what the strip sees it as. */
	private static Family runtimeJarFamily(Path dir, String runtimeJarsSwitch, boolean define) throws Exception {
		Path owned = jar(dir.resolve("some-fabric-mod.jar"), impl());
		Path added = jar(dir.resolve("unpacked-at-prelaunch.jar"), methodOnly(PKG + "AddedLater"));
		FabricLauncher before = FabricLauncherBase.getLauncher();
		String old = System.getProperty(KernelFabricLauncher.RUNTIME_JARS_SWITCH);
		try (ForbricClassLoader loader = new ForbricClassLoader(new URL[] {owned.toUri().toURL()},
				EnvironmentStripTransformerTest.class.getClassLoader())) {
			set(KernelFabricLauncher.RUNTIME_JARS_SWITCH, runtimeJarsSwitch);
			loader.setJarFamilies(Map.of(owned, Family.FABRIC));
			TransformChain chain = new TransformChain();
			chain.register(TransformPhase.ENV_STRIP, strip(loader::familyOfResource));
			loader.setTransformer((name, bytes) -> chain.applyBeforeMixin(name, bytes, SERVER));
			KernelFabricLauncher.install(loader, EnvType.SERVER);

			// CustomSkinLoader's Fabric bootstrap: FabricLauncherBase.getLauncher().addToClassPath(commonJar, ...).
			FabricLauncherBase.getLauncher().addToClassPath(added, "forbrictest.");

			Family family = loader.familyOfResource("forbrictest.envstrip.AddedLater");
			if (define) {
				Class<?> linked = Class.forName("forbrictest.envstrip.AddedLater", true, loader);
				assertEquals(7, linked.getMethod("server").invoke(linked.getConstructor().newInstance()));
				// Not what this changes: the probe answer still reads the class as unowned.
				assertNull(loader.familyOfClass("forbrictest.envstrip.AddedLater"));
			}
			return family;
		} finally {
			FabricLauncherBase.setLauncher(before);
			set(KernelFabricLauncher.RUNTIME_JARS_SWITCH, old);
		}
	}

	@Test
	void aJarAFabricModAddsAtRuntimeIsStrippedAsKnotStripsIt(@TempDir Path dir) throws Exception {
		// Knot strips every class it loads, a jar added through its own addToClassPath included; left unowned here,
		// a client-only member in such a jar would fail its class on a server the way CreativeCore's did.
		assertEquals(Family.FABRIC, runtimeJarFamily(dir, null, true));
	}

	@Test
	void switchedOffARuntimeJarIsLeftAsItWas(@TempDir Path dir) throws Exception {
		assertNull(runtimeJarFamily(dir, "off", false));
	}

	// ------------------------------------------------------------------------------------------------ the real jar

	@Test
	void creativeCoresLoaderLosesExactlyTheOneMethodNativeFabricRemoves() throws Exception {
		Path jar = Path.of("build/compat-inputs/sweep90/mods/CreativeCore_FABRIC_v2.14.16_mc26.2.jar");
		assumeTrue(Files.isRegularFile(jar), "sweep pack absent");
		byte[] raw;
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			raw = zip.getInputStream(zip.getEntry("team/creative/creativecore/CreativeFabricLoader.class")).readAllBytes();
		}

		byte[] out = strip(ALL_FABRIC).transform("team.creative.creativecore.CreativeFabricLoader", raw, SERVER);

		Set<String> expected = new TreeSet<>(methods(raw));
		assertTrue(expected.remove("registerHudElement(Ljava/util/function/Consumer;)V"));
		assertEquals(expected, methods(out));
		assertEquals(fields(raw), fields(out));
		assertEquals(node(raw).interfaces, node(out).interfaces);
		assertSame(raw, strip(ALL_FABRIC).transform("team.creative.creativecore.CreativeFabricLoader", raw, CLIENT));
	}
}
