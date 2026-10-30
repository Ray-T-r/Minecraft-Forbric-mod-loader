package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.kernel.TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The merged {@code Holder.Reference.getData} answers "no data" for a value not registered yet, on the staged game's
 * own bytes: the edit, the JVM running it, and the census that makes this one method the sink of every holder-based
 * data-map lookup in the merged base and NeoForge.
 */
@ResourceLock("system-properties")
class UnboundHolderDataInjectorTest {
	private static final Path RUN = TestFixtures.stagedRoot();
	private static final Path MERGED = RUN.resolve(TestFixtures.BASE_JAR);
	private static final Path NEO = RUN.resolve(TestFixtures.NEO_CARRIER_JAR);
	private static final String REFERENCE = UnboundHolderDataInjector.OWNER;

	@AfterEach void reset() { System.clearProperty(UnboundHolderDataInjector.PROPERTY); }

	@Test void getDataOpensWithTheUnboundGuardIntoItsOwnNullReturn() throws Exception {
		byte[] original = NativeCoremodParityTest.read(MERGED, REFERENCE);
		byte[] out = new UnboundHolderDataInjector().transform(UnboundHolderDataInjector.TARGET, original, null);
		assertNotSame(original, out, "the staged Holder.Reference is the reviewed shape");
		MethodNode getData = getData(node(out));
		List<AbstractInsnNode> real = real(getData);
		List<AbstractInsnNode> merged = real(getData(node(original)));
		assertTrue(load0(real.get(0)) && field(real.get(1), "key"), real.get(1) + "");
		JumpInsnNode bound = (JumpInsnNode) real.get(2);
		assertEquals(Opcodes.IFNONNULL, bound.getOpcode());
		assertTrue(load0(real.get(3)) && field(real.get(4), "value") && load0(real.get(5)) && field(real.get(6), "owner"));
		MethodInsnNode hook = (MethodInsnNode) real.get(7);
		assertEquals(UnboundHolderDataInjector.HOOK + "." + UnboundHolderDataInjector.HOOK_NAME + UnboundHolderDataInjector.HOOK_DESC,
				hook.owner + "." + hook.name + hook.desc);
		JumpInsnNode noData = (JumpInsnNode) real.get(8);
		assertEquals(Opcodes.GOTO, noData.getOpcode());
		assertEquals(real.get(9), firstReal(bound.label), "a bound holder goes on to the method as merged");
		assertEquals(merged.get(0).getOpcode(), real.get(9).getOpcode());
		AbstractInsnNode target = firstReal(noData.label);
		assertEquals(Opcodes.ACONST_NULL, target.getOpcode(), "the unbound path lands on the method's own `return null`");
		assertEquals(real.size() - 2, real.indexOf(target), "which is its last two instructions");
		assertEquals(merged.size() + 9, real.size(), "nine instructions added, nothing else");
		assertTrue(UnboundHolderDataInjector.guarded(getData));

		assertSame(out, new UnboundHolderDataInjector().transform(UnboundHolderDataInjector.TARGET, out, null), "edited once");
		System.setProperty(UnboundHolderDataInjector.PROPERTY, "off");
		assertSame(original, new UnboundHolderDataInjector().transform(UnboundHolderDataInjector.TARGET, original, null), "off: as merged");
	}

	/**
	 * The JVM's own verifier (frames included) and the real class running off-game: an intrusive holder never
	 * registered answers {@code null} where as merged it threw, and a holder with a key still asks its registry by that
	 * key — the path NeoForge's loaded data maps take is the one it always took.
	 */
	@Test void theRepairedReferenceRunsOnTheStagedGame() throws Throwable {
		TestFixtures.requireForgeCarrierWhereTheBaseNamesIt("the base classes this defines");
		byte[] original = NativeCoremodParityTest.read(MERGED, REFERENCE);
		byte[] repaired = new UnboundHolderDataInjector().transform(UnboundHolderDataInjector.TARGET, original, null);
		assertNotSame(original, repaired);
		try (URLClassLoader game = net.forbric.kernel.runtime.StagedGameClassLoader.create(List.of(bootSide()))) {
			Class<?> lookup = Class.forName("net.minecraft.core.HolderLookup$RegistryLookup", false, game);
			List<Object> asked = Collections.synchronizedList(new ArrayList<>());
			Object registry = Proxy.newProxyInstance(game, new Class<?>[] { lookup }, (proxy, method, args) -> {
				if (method.getName().equals("getData") && args != null && args.length == 2) {
					asked.add(args[1]);
					return "listed";
				}
				if (method.getName().equals("toString")) return "Registry[test]";
				if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
				if (method.getName().equals("equals")) return proxy == args[0];
				throw new UnsupportedOperationException(method.toString());
			});

			Class<?> merged = define(game, original), fixed = define(game, repaired);
			InvocationTargetException threw = assertThrows(InvocationTargetException.class,
					() -> getData(intrusive(merged, registry, "ladder")), "premise: as merged, an unbound holder throws");
			assertTrue(threw.getCause() instanceof IllegalStateException
					&& threw.getCause().getMessage().startsWith("Trying to access unbound value 'ladder'"), String.valueOf(threw.getCause()));

			assertNull(getData(intrusive(fixed, registry, "ladder")), "repaired: no data-map entry");
			assertEquals(List.of(), asked, "and the registry is not asked with a key it cannot have");

			Object key = key(game, "block", "minecraft", "copper_block");
			Method standAlone = fixed.getMethod("createStandAlone", Class.forName("net.minecraft.core.HolderOwner", false, game),
					Class.forName("net.minecraft.resources.ResourceKey", false, game));
			assertEquals("listed", getData(standAlone.invoke(null, registry, key)), "a holder with a key still asks its registry");
			assertEquals(List.of(key), asked, "by that key");
		}
	}

	/**
	 * What the guard reports is judged on the staged registry's own flag. A new intrusive {@code MappedRegistry} is
	 * open: a value made against it and not registered yet is the expected case. Its {@code freeze()} refuses to close
	 * over that value — and leaves {@code frozen} set as it throws, the state a caller that swallowed the throw goes on
	 * in. From then on the registry is closed over a value nobody registered (only an {@code unfreeze()} would take it
	 * now), and the guard's report calls the registry closed.
	 */
	@Test void theReportJudgesTheStagedRegistrysOwnFrozenFlag() throws Throwable {
		TestFixtures.requireForgeCarrierWhereTheBaseNamesIt("the base classes this defines");
		byte[] repaired = new UnboundHolderDataInjector().transform(UnboundHolderDataInjector.TARGET,
				NativeCoremodParityTest.read(MERGED, REFERENCE), null);
		try (URLClassLoader game = net.forbric.kernel.runtime.StagedGameClassLoader.create(List.of(bootSide()))) {
			Class<?> keys = Class.forName("net.minecraft.resources.ResourceKey", true, game);
			Class<?> lifecycles = Class.forName("com.mojang.serialization.Lifecycle", true, game);
			Class<?> mapped = Class.forName("net.minecraft.core.MappedRegistry", true, game);
			Object registry = mapped.getConstructor(keys, lifecycles, boolean.class)
					.newInstance(registryKey(game, "forbric_test"), lifecycles.getMethod("stable").invoke(null), true);
			Method closed = Class.forName(UnboundHolderDataInjector.HOOK.replace('/', '.'), true, game).getDeclaredMethod("closed", Object.class);
			closed.setAccessible(true);

			Object pending = new Object();
			mapped.getMethod("createIntrusiveHolder", Object.class).invoke(registry, pending);
			Class<?> fixed = define(game, repaired);
			Object unbound = intrusive(fixed, registry, "pending");
			assertEquals(Boolean.FALSE, closed.invoke(null, registry), "a registry still taking registrations is open");
			assertNull(getData(unbound), "and the lookup answers no data");

			InvocationTargetException refused = assertThrows(InvocationTargetException.class, () -> mapped.getMethod("freeze").invoke(registry));
			assertTrue(String.valueOf(refused.getCause().getMessage()).startsWith("Some intrusive holders were not registered"),
					"premise: the merged freeze() names the value it closes over: " + refused.getCause());
			assertEquals(Boolean.TRUE, closed.invoke(null, registry), "the flag the failed freeze() left set reads closed");
			assertNull(getData(unbound), "the lookup still answers no data, and the report does not throw");
			assertNull(closed.invoke(null, "not a registry"), "an owner without a frozen flag is not judged");
		}
	}

	/**
	 * Every {@code getData(DataMapType)} body in the merged base and NeoForge either hands the call to another one or
	 * answers {@code null} — except {@code Holder.Reference}'s, the only one that reaches a registry by key. So the
	 * guard there covers each lookup NeoForge put where vanilla read its own maps (oxidation, waxing, stripping,
	 * compostables, parrots, vibrations, villagers). A second sink would turn this red.
	 */
	@Test void holderReferenceIsTheOneDataMapLookupThatReadsAKey() throws Exception {
		NativeCoremodParityTest.read(MERGED, REFERENCE);
		TestFixtures.requireFiles(TestFixtures.Fixture.STAGED, "the staged NeoForge runtime is absent", NEO);
		TreeSet<String> sinks = new TreeSet<>();
		int delegates = 0, nulls = 0;
		for (Path jar : List.of(MERGED, NEO)) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (ZipEntry entry : Collections.list(zip.entries())) {
					if (!entry.getName().endsWith(".class")) continue;
					ClassNode node = new ClassNode();
					new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, ClassReader.SKIP_FRAMES);
					for (MethodNode method : node.methods) {
						if (!method.name.equals("getData") || !method.desc.equals(UnboundHolderDataInjector.GET_DATA_DESC)) continue;
						if ((method.access & Opcodes.ACC_ABSTRACT) != 0) continue;
						List<AbstractInsnNode> real = real(method);
						if (real.size() == 2 && real.get(0).getOpcode() == Opcodes.ACONST_NULL) nulls++;
						else if (real.stream().filter(insn -> insn instanceof MethodInsnNode call && call.name.equals("getData")
								&& call.desc.equals(UnboundHolderDataInjector.GET_DATA_DESC)).count() == 1
								&& real.stream().noneMatch(insn -> insn instanceof MethodInsnNode call && call.name.equals("key"))) delegates++;
						else sinks.add(node.name);
					}
				}
			}
		}
		assertEquals(new TreeSet<>(List.of(REFERENCE)), sinks);
		assertTrue(delegates > 100 && nulls >= 1, "the census read the jars: " + delegates + " delegates, " + nulls + " null answers");
	}

	/** The boot-side classes, where the guard's report lives: the game reaches them through its parent, here directly. */
	private static java.net.URL bootSide() throws Exception {
		return net.forbric.kernel.util.KernelUnboundHolderData.class.getProtectionDomain().getCodeSource().getLocation();
	}

	private static Object registryKey(ClassLoader game, String path) throws Exception {
		Class<?> keys = Class.forName("net.minecraft.resources.ResourceKey", true, game);
		Class<?> ids = Class.forName("net.minecraft.resources.Identifier", true, game);
		return keys.getMethod("createRegistryKey", ids).invoke(null, ids.getMethod("fromNamespaceAndPath", String.class, String.class)
				.invoke(null, "minecraft", path));
	}

	private static Object key(ClassLoader game, String registry, String namespace, String path) throws Exception {
		Class<?> keys = Class.forName("net.minecraft.resources.ResourceKey", true, game);
		Class<?> ids = Class.forName("net.minecraft.resources.Identifier", true, game);
		Object id = ids.getMethod("fromNamespaceAndPath", String.class, String.class).invoke(null, namespace, path);
		return keys.getMethod("create", keys, ids).invoke(null, registryKey(game, registry), id);
	}

	private static boolean load0(AbstractInsnNode insn) {
		return insn instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 0;
	}

	private static boolean field(AbstractInsnNode insn, String name) {
		return insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD && field.name.equals(name)
				&& field.owner.equals(REFERENCE);
	}

	private static AbstractInsnNode firstReal(AbstractInsnNode from) {
		AbstractInsnNode insn = from;
		while (insn != null && insn.getOpcode() < 0) insn = insn.getNext();
		return insn;
	}

	private static Object intrusive(Class<?> reference, Object owner, String value) throws Exception {
		Method create = reference.getMethod("createIntrusive", Class.forName("net.minecraft.core.HolderOwner", false, reference.getClassLoader()),
				Object.class);
		return create.invoke(null, owner, value);
	}

	private static Object getData(Object holder) throws Exception {
		Method getData = holder.getClass().getMethod("getData", Class.forName("net.neoforged.neoforge.registries.datamaps.DataMapType", false,
				holder.getClass().getClassLoader()));
		return getData.invoke(holder, (Object) null);
	}

	/** {@code bytes} as {@code Holder$Reference} in a loader of its own over the staged game, linked and verified. */
	private static Class<?> define(ClassLoader game, byte[] bytes) throws ClassNotFoundException {
		String name = UnboundHolderDataInjector.TARGET;
		ClassLoader loader = new ClassLoader(game) {
			@Override protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
				if (!className.equals(name)) return super.loadClass(className, resolve);
				synchronized (getClassLoadingLock(className)) {
					Class<?> done = findLoadedClass(className);
					return done != null ? done : defineClass(className, bytes, 0, bytes.length);
				}
			}
		};
		return Class.forName(name, true, loader);
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode getData(ClassNode node) {
		return node.methods.stream().filter(m -> m.name.equals("getData") && m.desc.equals(UnboundHolderDataInjector.GET_DATA_DESC))
				.findFirst().orElseThrow();
	}

	private static List<AbstractInsnNode> real(MethodNode method) {
		List<AbstractInsnNode> real = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() >= 0) real.add(insn);
		return real;
	}
}
