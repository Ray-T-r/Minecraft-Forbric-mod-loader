package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.ExpectCaught;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * NeoForge's "Missing FluidModel" check in ClientHooks.gatherFluidModels asks the kernel, which also counts the models
 * fabric-rendering-fluids adds after it — and nothing else changes.
 */
@ResourceLock("system-properties")
class FabricFluidModelsInjectorTest {
	private static final Path NEO_RT = TestFixtures.stagedRoot()
			.resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final String HOOKS = "net/neoforged/neoforge/client/ClientHooks";
	/** fabric-rendering-fluids' public registration API, as a mod calls it. */
	private static final String FABRIC_API = "net/fabricmc/fabric/api/client/render/fluid/v1/FluidRenderingRegistry";
	/** Where a build of it keeps the models: a class and field of no loader's, found from the API. */
	private static final String FABRIC_REGISTRY = "org/quarry/render/Shapes";
	private static final String REGISTER_DESC = "(Lnet/minecraft/world/level/material/Fluid;Lnet/minecraft/client/renderer/block/FluidModel$Unbaked;)V";

	@AfterEach void reset() { System.clearProperty(FabricFluidModelsInjector.PROPERTY); }

	@Test void theWarningsCheckAsksTheKernelAndOnlyIt() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(NEO_RT), NEO_RT + " absent");
		byte[] original = NativeCoremodParityTest.read(NEO_RT, HOOKS);
		byte[] out = new FabricFluidModelsInjector().transform(FabricFluidModelsInjector.HOOKS, original, null);
		assertNotSame(original, out);
		ClassNode node = new ClassNode();
		new ClassReader(out).accept(node, 0);
		MethodNode gather = node.methods.stream().filter(m -> m.name.equals("gatherFluidModels")).findFirst().orElseThrow();
		int hooked = 0, containsKey = 0;
		for (AbstractInsnNode insn : gather.instructions) {
			if (!(insn instanceof MethodInsnNode call)) continue;
			if (call.owner.equals(FabricFluidModelsInjector.HOOK_OWNER) && call.name.equals("hasModel")) hooked++;
			if (call.name.equals("containsKey")) containsKey++;
		}
		assertEquals(1, hooked);
		assertEquals(0, containsKey, "the check had one containsKey, and it is the one replaced");
		new Analyzer<>(new BasicVerifier()).analyze(HOOKS, gather);
		assertSame(out, new FabricFluidModelsInjector().transform(FabricFluidModelsInjector.HOOKS, out, null), "idempotent");
		System.setProperty(FabricFluidModelsInjector.PROPERTY, "off");
		assertSame(original, new FabricFluidModelsInjector().transform(FabricFluidModelsInjector.HOOKS, original, null));
	}

	/** Traveler's Backpack's shape: NeoForge's map lacks the fluid, Fabric's registry has it. */
	@Test void aFluidFabricGivesAModelIsNotReportedMissing() throws Exception {
		try (URLClassLoader cl = runtimeLoader(fabricRegistry(false))) {
			Method hasModel = hook(cl);
			Object potion = "travelersbackpack:potion_still", water = "minecraft:water", stray = "somemod:no_model";
			assertEquals(true, hasModel.invoke(null, Map.of(water, "model"), water), "NeoForge's own answer still counts");
			assertEquals(true, hasModel.invoke(null, Map.of(water, "model"), potion), "Fabric's wrapper adds this one");
			assertEquals(false, hasModel.invoke(null, Map.of(water, "model"), stray), "a real miss is still reported");
		}
	}

	@Test void withoutFabricRenderingFluidsTheCheckIsNeoForgesOwn() throws Exception {
		try (URLClassLoader cl = runtimeLoader(null)) {
			Method hasModel = hook(cl);
			assertEquals(false, hasModel.invoke(null, Map.of(), "travelersbackpack:potion_still"));
			assertEquals(true, hasModel.invoke(null, Map.of("minecraft:water", "model"), "minecraft:water"));
		}
	}

	/**
	 * The check runs in the model bake of a resource reload, where an Error that is not an Exception makes Minecraft
	 * drop every pack and sit on a black screen. A Fabric registry whose class initialisation fails reaches the hook
	 * as an ExceptionInInitializerError, straight out of Method.invoke; the answer is then NeoForge's own.
	 */
	@ExpectCaught("a registry that cannot initialise")
	@Test void aFabricRegistryThatCannotInitialiseLeavesTheCheckToNeoForge() throws Exception {
		try (URLClassLoader cl = runtimeLoader(fabricRegistry(true))) {
			Method hasModel = hook(cl);
			assertEquals(false, hasModel.invoke(null, Map.of(), "travelersbackpack:potion_still"), "no Error escapes the check");
			assertEquals(true, hasModel.invoke(null, Map.of("minecraft:water", "model"), "minecraft:water"));
		}
	}

	private static Method hook(ClassLoader cl) throws Exception {
		return Class.forName(FabricFluidModelsInjector.HOOK_OWNER.replace('/', '.'), true, cl)
				.getMethod("hasModel", Map.class, Object.class);
	}

	/** The compiled game-side hook, plus (when given) a Fabric registry and its public API, in a loader of their own. */
	private static URLClassLoader runtimeLoader(Map<String, byte[]> registry) throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "runtime").normalize();
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(compiled), "the game-side set is not compiled");
		Map<String, byte[]> classes = registry == null ? Map.of() : registry;
		return new URLClassLoader(new URL[] { compiled.toUri().toURL() }, FabricFluidModelsInjectorTest.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException {
				byte[] bytes = classes.get(name.replace('.', '/'));
				if (bytes != null) return defineClass(name, bytes, 0, bytes.length);
				return super.findClass(name);
			}

			@Override
			public java.io.InputStream getResourceAsStream(String path) {
				byte[] bytes = path.endsWith(".class") ? classes.get(path.substring(0, path.length() - 6)) : null;
				return bytes != null ? new java.io.ByteArrayInputStream(bytes) : super.getResourceAsStream(path);
			}
		};
	}

	/**
	 * The public {@code FluidRenderingRegistry.register(fluid, model)} handing both to {@code org.quarry.render.Shapes.add},
	 * which puts them into its static map holding one registered fluid; with {@code broken}, a static initialiser that
	 * throws, so the first read fails its class initialisation instead.
	 */
	private static Map<String, byte[]> fabricRegistry(boolean broken) {
		ClassWriter api = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		api.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, FABRIC_API, null, "java/lang/Object", null);
		MethodVisitor register = api.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "register", REGISTER_DESC, null, null);
		register.visitCode();
		register.visitVarInsn(Opcodes.ALOAD, 0);
		register.visitVarInsn(Opcodes.ALOAD, 1);
		register.visitMethodInsn(Opcodes.INVOKESTATIC, FABRIC_REGISTRY, "add", REGISTER_DESC, false);
		register.visitInsn(Opcodes.RETURN);
		register.visitMaxs(0, 0);
		register.visitEnd();
		api.visitEnd();

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, FABRIC_REGISTRY, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "SHAPES", "Ljava/util/Map;", null, null).visitEnd();
		MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
		clinit.visitCode();
		if (broken) {
			clinit.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
			clinit.visitInsn(Opcodes.DUP);
			clinit.visitLdcInsn("a registry that cannot initialise");
			clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false);
			clinit.visitInsn(Opcodes.ATHROW);
		} else {
			clinit.visitTypeInsn(Opcodes.NEW, "java/util/HashMap");
			clinit.visitInsn(Opcodes.DUP);
			clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
			clinit.visitInsn(Opcodes.DUP);
			clinit.visitLdcInsn("travelersbackpack:potion_still");
			clinit.visitLdcInsn("unbaked");
			clinit.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
			clinit.visitInsn(Opcodes.POP);
			clinit.visitFieldInsn(Opcodes.PUTSTATIC, FABRIC_REGISTRY, "SHAPES", "Ljava/util/Map;");
			clinit.visitInsn(Opcodes.RETURN);
		}
		clinit.visitMaxs(0, 0);
		clinit.visitEnd();
		MethodVisitor add = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "add", REGISTER_DESC, null, null);
		add.visitCode();
		add.visitFieldInsn(Opcodes.GETSTATIC, FABRIC_REGISTRY, "SHAPES", "Ljava/util/Map;");
		add.visitVarInsn(Opcodes.ALOAD, 0);
		add.visitVarInsn(Opcodes.ALOAD, 1);
		add.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
		add.visitInsn(Opcodes.POP);
		add.visitInsn(Opcodes.RETURN);
		add.visitMaxs(0, 0);
		add.visitEnd();
		cw.visitEnd();
		return Map.of(FABRIC_API, api.toByteArray(), FABRIC_REGISTRY, cw.toByteArray());
	}
}
