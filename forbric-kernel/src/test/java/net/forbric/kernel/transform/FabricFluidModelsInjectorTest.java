package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

/**
 * NeoForge's "Missing FluidModel" check in ClientHooks.gatherFluidModels asks the kernel, which also counts the models
 * fabric-rendering-fluids adds after it — and nothing else changes.
 */
@ResourceLock("system-properties")
class FabricFluidModelsInjectorTest {
	private static final Path NEO_RT = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"))
			.resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final String HOOKS = "net/neoforged/neoforge/client/ClientHooks";
	private static final String FABRIC_REGISTRY = "net/fabricmc/fabric/impl/client/rendering/fluid/FluidRenderingRegistryImpl";

	@AfterEach void reset() { System.clearProperty(FabricFluidModelsInjector.PROPERTY); }

	@Test void theWarningsCheckAsksTheKernelAndOnlyIt() throws Exception {
		assumeTrue(Files.isRegularFile(NEO_RT), NEO_RT + " absent");
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
		try (URLClassLoader cl = runtimeLoader(true)) {
			Method hasModel = hook(cl);
			Object potion = "travelersbackpack:potion_still", water = "minecraft:water", stray = "somemod:no_model";
			assertEquals(true, hasModel.invoke(null, Map.of(water, "model"), water), "NeoForge's own answer still counts");
			assertEquals(true, hasModel.invoke(null, Map.of(water, "model"), potion), "Fabric's wrapper adds this one");
			assertEquals(false, hasModel.invoke(null, Map.of(water, "model"), stray), "a real miss is still reported");
		}
	}

	@Test void withoutFabricRenderingFluidsTheCheckIsNeoForgesOwn() throws Exception {
		try (URLClassLoader cl = runtimeLoader(false)) {
			Method hasModel = hook(cl);
			assertEquals(false, hasModel.invoke(null, Map.of(), "travelersbackpack:potion_still"));
			assertEquals(true, hasModel.invoke(null, Map.of("minecraft:water", "model"), "minecraft:water"));
		}
	}

	private static Method hook(ClassLoader cl) throws Exception {
		return Class.forName(FabricFluidModelsInjector.HOOK_OWNER.replace('/', '.'), true, cl)
				.getMethod("hasModel", Map.class, Object.class);
	}

	/** The compiled game-side hook, plus (optionally) a Fabric registry holding one model, in a loader of their own. */
	private static URLClassLoader runtimeLoader(boolean withFabric) throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "runtime").normalize();
		assumeTrue(Files.isDirectory(compiled), "the game-side set is not compiled");
		byte[] registry = fabricRegistry();
		return new URLClassLoader(new URL[] { compiled.toUri().toURL() }, FabricFluidModelsInjectorTest.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException {
				if (withFabric && name.equals(FABRIC_REGISTRY.replace('/', '.'))) return defineClass(name, registry, 0, registry.length);
				return super.findClass(name);
			}
		};
	}

	/** {@code FluidRenderingRegistryImpl.getUnbakedModels()} answering one registered fluid. */
	private static byte[] fabricRegistry() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, FABRIC_REGISTRY, null, "java/lang/Object", null);
		MethodVisitor models = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getUnbakedModels", "()Ljava/util/Map;", null, null);
		models.visitCode();
		models.visitLdcInsn("travelersbackpack:potion_still");
		models.visitLdcInsn("unbaked");
		models.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Map", "of",
				"(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;", true);
		models.visitInsn(Opcodes.ARETURN);
		models.visitMaxs(0, 0);
		models.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}
}
