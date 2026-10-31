package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * A guest mixin's private method named like a default of an interface its target implements on this base: merged under its
 * own name it hides the default from every subclass at run time (HotSpot's AbstractMethodError, measured: the 97-mod pack's
 * integrated server on its first axolotl tick, fabric-entity-events' self() against NeoForge's ILivingEntityExtension).
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class MixinPrivateDefaultCollisionTest {
	private static final String MIXIN = "net/fabricmc/fabric/mixin/entity/event/effect/LivingEntityMixin";
	private static final String SELF = "self()Lnet/minecraft/world/entity/LivingEntity;";

	/** The base and the NeoForge carrier, as the game reads classes: whichever holds the class. */
	private static Function<String, ClassNode> genuine() {
		Path base = TestFixtures.stagedBase();
		Path carrier = TestFixtures.neoCarrier();
		TestFixtures.requireFiles(Fixture.STAGED, "the staged base and its NeoForge carrier", base, carrier);
		Map<String, ClassNode> cache = new HashMap<>();
		return name -> cache.computeIfAbsent(name, n -> {
			for (Path jar : List.of(base, carrier)) {
				try (ZipFile z = new ZipFile(jar.toFile())) {
					var e = z.getEntry(n + ".class");
					if (e != null) return MixinFit.parse(z.getInputStream(e).readAllBytes());
				} catch (Exception unreadable) {
					throw new AssertionError(unreadable);
				}
			}
			return null;
		});
	}

	private static Function<String, ClassNode> vanilla() {
		Path jar = TestFixtures.vanillaJar();
		TestFixtures.requireFile(Fixture.MC_LIBRARIES, jar, "the vanilla game");
		return n -> {
			try (ZipFile z = new ZipFile(jar.toFile())) {
				var e = z.getEntry(n + ".class");
				return e == null ? null : MixinFit.parse(z.getInputStream(e).readAllBytes());
			} catch (Exception unreadable) {
				throw new AssertionError(unreadable);
			}
		};
	}

	private static List<String> calls(ClassNode c, String name) {
		return c.methods.stream().flatMap(m -> java.util.stream.StreamSupport.stream(m.instructions.spliterator(), false))
				.filter(i -> i instanceof MethodInsnNode call && call.owner.equals(c.name) && call.name.startsWith(name))
				.map(i -> ((MethodInsnNode) i).name).toList();
	}

	@Test void fabricEntityEventsSelfIsRenamedAgainstNeoForgesLivingEntityExtension() throws Exception {
		System.clearProperty(MixinPrivateDefaultCollision.PROPERTY);
		ClassNode mixin = StagedFabricMixinFixture.mixin("fabric-entity-events-v1", MIXIN);
		assertTrue(mixin.methods.stream().anyMatch(m -> (m.name + m.desc).equals(SELF) && (m.access & Opcodes.ACC_PRIVATE) != 0),
				"premise: the mixin declares a private self()");
		List<String> before = calls(mixin, "self");
		assertFalse(before.isEmpty(), "premise: its handlers call it");

		assertEquals(1, MixinPrivateDefaultCollision.adapt(mixin, genuine()));

		assertTrue(mixin.methods.stream().noneMatch(m -> (m.name + m.desc).equals(SELF)), "no method named self() is merged any more");
		String renamed = mixin.methods.stream().filter(m -> m.name.startsWith("self$forbric$")).findFirst().orElseThrow().name;
		assertEquals(before.stream().map(n -> renamed).toList(), calls(mixin, "self"), "every call the mixin makes follows the new name");
	}

	@Test void onVanillaNoInterfaceDefaultsSelf() throws Exception {
		System.clearProperty(MixinPrivateDefaultCollision.PROPERTY);
		ClassNode mixin = StagedFabricMixinFixture.mixin("fabric-entity-events-v1", MIXIN);
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		assertEquals(0, MixinPrivateDefaultCollision.adapt(mixin, vanilla()));
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin));
	}

	@Test void theOffSwitchKeepsTheName() throws Exception {
		ClassNode mixin = StagedFabricMixinFixture.mixin("fabric-entity-events-v1", MIXIN);
		System.setProperty(MixinPrivateDefaultCollision.PROPERTY, "off");
		try {
			assertEquals(0, MixinPrivateDefaultCollision.adapt(mixin, genuine()));
		} finally {
			System.clearProperty(MixinPrivateDefaultCollision.PROPERTY);
		}
	}

	// --- the run-time failure itself, on synthetic classes: interface I defaults self(), A implements I, B extends A ---

	private static byte[] write(ClassNode c) {
		ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		c.accept(w);
		return w.toByteArray();
	}

	private static MethodNode returnsThis(int access, String desc) {
		MethodNode m = new MethodNode(access, "self", desc, null, null);
		m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		m.instructions.add(new InsnNode(Opcodes.ARETURN));
		return m;
	}

	/** Defines I (default self()), A (implements I, carrying {@code merged} as its own method) and B extends A; calls I.self() on a B. */
	private static Object callThroughTheInterface(MethodNode merged) throws Throwable {
		ClassNode i = new ClassNode();
		i.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, "probe/I", null, "java/lang/Object", null);
		i.methods.add(returnsThis(Opcodes.ACC_PUBLIC, "()Lprobe/I;"));
		ClassNode a = new ClassNode();
		a.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "probe/A", null, "java/lang/Object", new String[] {"probe/I"});
		MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
		init.instructions.add(new InsnNode(Opcodes.RETURN));
		a.methods.add(init);
		a.methods.add(merged);
		ClassNode b = new ClassNode();
		b.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "probe/B", null, "probe/A", null);
		MethodNode binit = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		binit.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		binit.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "probe/A", "<init>", "()V", false));
		binit.instructions.add(new InsnNode(Opcodes.RETURN));
		b.methods.add(binit);
		Map<String, byte[]> defs = Map.of("probe.I", write(i), "probe.A", write(a), "probe.B", write(b));
		ClassLoader loader = new ClassLoader(null) {
			@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
				byte[] bytes = defs.get(name);
				if (bytes == null) throw new ClassNotFoundException(name);
				return defineClass(name, bytes, 0, bytes.length);
			}
		};
		Object instance = loader.loadClass("probe.B").getConstructor().newInstance();
		try {
			return loader.loadClass("probe.I").getMethod("self").invoke(instance);
		} catch (java.lang.reflect.InvocationTargetException e) {
			throw e.getCause();
		}
	}

	@Test void aPrivateMethodNamedLikeTheDefaultHidesItAndTheRenamedOneDoesNot() throws Throwable {
		assertThrows(AbstractMethodError.class, () -> callThroughTheInterface(returnsThis(Opcodes.ACC_PRIVATE, "()Lprobe/I;")),
				"premise: the JVM this runs on hides the default behind the private method, as the game did");
		MethodNode renamed = returnsThis(Opcodes.ACC_PRIVATE, "()Lprobe/I;");
		renamed.name = "self$forbric$probe";
		assertNotNull(callThroughTheInterface(renamed), "renamed, the interface's default answers");
	}
}
