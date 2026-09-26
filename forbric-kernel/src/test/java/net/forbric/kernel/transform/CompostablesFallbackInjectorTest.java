package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import net.forbric.kernel.mixin.MixinFit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * The merged composter makes vanilla's COMPOSTABLES calls again, behind NeoForge's data map, so BCLib's wraps bind and
 * Fabric's CompostableRegistry is read; fabric-transfer's direct read goes to the block's own answer.
 */
@ResourceLock("system-properties")
class CompostablesFallbackInjectorTest {
	private static final Path MERGED = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"),
			"run/merged-base/patched-mc-merged-26.2.jar");
	private static final Path MODS = Path.of("build/compat-inputs/sweep90/mods");
	private static final String COMPOSTER = "net/minecraft/world/level/block/ComposterBlock";
	private static final String INPUT = COMPOSTER + "$InputContainer";
	private static final String MAP = "it/unimi/dsi/fastutil/objects/Object2FloatMap";

	@AfterEach void reset() { System.clearProperty(CompostablesFallbackInjector.PROPERTY); }

	@Test void eachComposterSiteAsksTheVanillaMapRightAfterNeoForge() throws Exception {
		byte[] original = NativeCoremodParityTest.read(MERGED, COMPOSTER);
		byte[] out = new CompostablesFallbackInjector().transform(CompostablesFallbackInjector.COMPOSTER, original, null);
		assertNotSame(original, out);
		ClassNode node = node(out);
		assertEquals(List.of("getValue", "fallback", "getItem", "containsKey", "orContains"), around(method(node, "useItemOn")));
		assertEquals(List.of("getValue", "fallback", "getItem", "containsKey", "orContains"), around(method(node, "insertItem")));
		assertEquals(List.of("getValue", "fallback", "getItem", "getFloat", "orChance"), around(method(node, "addItem")));
		for (String name : List.of("useItemOn", "insertItem", "addItem", "bootStrap")) {
			new Analyzer<>(new BasicVerifier()).analyze(COMPOSTER, method(node, name));
		}
		assertSame(out, new CompostablesFallbackInjector().transform(CompostablesFallbackInjector.COMPOSTER, out, null), "idempotent");
	}

	/**
	 * The JVM's own verifier, frames included, over the staged game: the hopper face initialises; the block is verified
	 * before its initialiser, which off-game stops at the unbootstrapped registries — never at a VerifyError.
	 */
	@Test void theRepairedClassesPassTheJvmVerifier() throws Exception {
		Map<String, byte[]> repaired = new HashMap<>();
		for (String name : List.of(COMPOSTER, INPUT)) {
			byte[] original = NativeCoremodParityTest.read(MERGED, name);
			byte[] out = new CompostablesFallbackInjector().transform(name.replace('/', '.'), original, null);
			assertNotSame(original, out, name);
			repaired.put(name.replace('/', '.'), out);
		}
		try (URLClassLoader game = net.forbric.kernel.runtime.StagedGameClassLoader.create(List.of())) {
			ClassLoader loader = new ClassLoader(game) {
				@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
					byte[] bytes = repaired.get(name);
					if (bytes == null) return super.loadClass(name, resolve);
					synchronized (getClassLoadingLock(name)) {
						Class<?> done = findLoadedClass(name);
						return done != null ? done : defineClass(name, bytes, 0, bytes.length);
					}
				}
			};
			Class<?> input = Class.forName(INPUT.replace('/', '.'), true, loader);
			assertSame(loader, input.getClassLoader());
			ExceptionInInitializerError unbootstrapped = assertThrows(ExceptionInInitializerError.class,
					() -> Class.forName(COMPOSTER.replace('/', '.'), true, loader), "verified, then initialised");
			assertTrue(String.valueOf(unbootstrapped.getCause()).contains("Not bootstrapped"), String.valueOf(unbootstrapped.getCause()));
		}
	}

	@Test void bootStrapRecordsEveryOneOfItsOwnAddsAndNothingElse() throws Exception {
		ClassNode node = node(new CompostablesFallbackInjector().transform(CompostablesFallbackInjector.COMPOSTER,
				NativeCoremodParityTest.read(MERGED, COMPOSTER), null));
		int adds = 0, records = 0;
		for (AbstractInsnNode insn : method(node, "bootStrap").instructions) {
			if (!(insn instanceof MethodInsnNode call)) continue;
			if (call.name.equals("add") && call.owner.equals(COMPOSTER)) {
				adds++;
				MethodInsnNode record = (MethodInsnNode) previousReal(call);
				assertEquals("vanilla", record.name, "each add is recorded right before it runs");
				assertEquals(org.objectweb.asm.Opcodes.DUP2, previousReal(record).getOpcode());
			}
			if (call.name.equals("vanilla")) records++;
		}
		assertTrue(adds > 90, "vanilla's whole table: " + adds);
		assertEquals(adds, records);
		assertEquals(0, count(method(node, "add"), "vanilla"), "add itself is not recorded: BCLib's @Invoker calls it too");
	}

	@Test void theHopperFaceAsksTheVanillaMapToo() throws Exception {
		byte[] original = NativeCoremodParityTest.read(MERGED, INPUT);
		byte[] out = new CompostablesFallbackInjector().transform(CompostablesFallbackInjector.INPUT, original, null);
		ClassNode node = node(out);
		assertEquals(List.of("getValue", "fallback", "getItem", "containsKey", "orContains"), around(method(node, "canPlaceItemThroughFace")));
		new Analyzer<>(new BasicVerifier()).analyze(INPUT, method(node, "canPlaceItemThroughFace"));
	}

	@Test void bclibsFourCompostableWrapsFindTheirCallsOnlyAfterTheRepair() throws Exception {
		byte[] block = bclib("org/betterx/bclib/mixin/common/ComposterBlockMixin");
		byte[] face = bclib("org/betterx/bclib/mixin/common/ComposterInputContainerMixin");
		Function<String, byte[]> merged = resolver(false), repaired = resolver(true);
		MixinFit.Result before = MixinFit.evaluate(block, merged), faceBefore = MixinFit.evaluate(face, merged);
		assertNotEquals(MixinFit.Verdict.FIT, before.verdict(), "the merged composter has no vanilla map call");
		assertEquals(3, before.unresolved().stream().filter(u -> u.contains("containsKey") || u.contains("getFloat")).count(), before.reason());
		assertNotEquals(MixinFit.Verdict.FIT, faceBefore.verdict(), faceBefore.reason());
		MixinFit.Result blockFit = MixinFit.evaluate(block, repaired);
		assertEquals(MixinFit.Verdict.FIT, blockFit.verdict(), blockFit.reason());
		assertEquals(before.total(), blockFit.resolved(), "useItemOn and insertItem containsKey, addItem getFloat now resolve");
		MixinFit.Result faceFit = MixinFit.evaluate(face, repaired);
		assertEquals(MixinFit.Verdict.FIT, faceFit.verdict(), faceFit.reason());
	}

	@Test void fabricTransfersComposterReadsTheBlocksOwnAnswer() throws Exception {
		byte[] original = fabricApi("fabric-transfer-api-v1", "net/fabricmc/fabric/impl/transfer/item/ComposterWrapper$TopStorage");
		byte[] out = new CompostablesFallbackInjector().transform(CompostablesFallbackInjector.TOP_STORAGE, original, null);
		ClassNode node = node(out);
		MethodNode insert = node.methods.stream().filter(m -> m.name.equals("insert") && m.desc.contains("ItemVariant")).findFirst().orElseThrow();
		for (AbstractInsnNode insn : insert.instructions) {
			assertFalse(insn instanceof FieldInsnNode field && field.name.equals("COMPOSTABLES"), "the direct vanilla-map read is gone");
		}
		assertEquals(1, count(insert, "effective"));
		assertEquals(1, count(insert, "getFloat"), "fabric-transfer still asks getFloat, of the block's answer");
		new Analyzer<>(new BasicVerifier()).analyze(node.name, insert);
	}

	@Test void aShapeItDoesNotKnowIsLeftAsMerged() throws Exception {
		ClassNode node = node(NativeCoremodParityTest.read(MERGED, COMPOSTER));
		MethodNode add = method(node, "addItem");
		for (AbstractInsnNode insn : add.instructions) {
			if (insn instanceof MethodInsnNode call && call.name.equals("getValue") && call.owner.equals(COMPOSTER)) {
				// a second `getValue(stack)`, its answer discarded
				add.instructions.insert(call, new InsnNode(org.objectweb.asm.Opcodes.POP));
				add.instructions.insert(call, new MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC, call.owner, call.name, call.desc, false));
				add.instructions.insert(call, new VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 4));
				break;
			}
		}
		assertEquals(-1, CompostablesFallbackInjector.repairComposter(node), "two getValue calls: which one is vanilla's is a guess");
		assertEquals(0, count(method(node, "bootStrap"), "vanilla"), "nothing is half-done: bootStrap is not recorded either");
	}

	@Test void offLeavesAllThreeClassesAsMerged() throws Exception {
		System.setProperty(CompostablesFallbackInjector.PROPERTY, "off");
		for (String name : List.of(COMPOSTER, INPUT)) {
			byte[] original = NativeCoremodParityTest.read(MERGED, name);
			assertSame(original, new CompostablesFallbackInjector().transform(name.replace('/', '.'), original, null));
		}
		byte[] storage = fabricApi("fabric-transfer-api-v1", "net/fabricmc/fabric/impl/transfer/item/ComposterWrapper$TopStorage");
		assertSame(storage, new CompostablesFallbackInjector().transform(CompostablesFallbackInjector.TOP_STORAGE, storage, null));
	}

	private static List<String> around(MethodNode method) {
		List<String> calls = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.name.equals("getValue") && call.owner.equals(COMPOSTER)) {
				assertEquals(org.objectweb.asm.Opcodes.DUP, previousReal(call).getOpcode(), "the stack keeps its ItemStack for getItem");
				for (AbstractInsnNode at = call; at != null && calls.size() < 5; at = at.getNext()) {
					if (at instanceof MethodInsnNode c) calls.add(c.name);
				}
			}
		}
		int vanilla = 0;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode c && c.owner.equals(MAP)) vanilla++;
		}
		assertEquals(1, vanilla, "exactly one vanilla-map call in " + method.name);
		return calls;
	}

	private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
		AbstractInsnNode at = insn.getPrevious();
		while (at != null && at.getOpcode() < 0) at = at.getPrevious();
		return at;
	}

	private static int count(MethodNode method, String name) {
		int n = 0;
		for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode c && c.name.equals(name)) n++;
		return n;
	}

	private static Function<String, byte[]> resolver(boolean repaired) {
		return file -> {
			String internal = file.endsWith(".class") ? file.substring(0, file.length() - ".class".length()) : file;
			try (ZipFile zip = new ZipFile(MERGED.toFile())) {
				ZipEntry entry = zip.getEntry(internal + ".class");
				if (entry == null) return null;
				byte[] bytes = zip.getInputStream(entry).readAllBytes();
				return repaired ? new CompostablesFallbackInjector().transform(internal.replace('/', '.'), bytes, null) : bytes;
			} catch (java.io.IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		};
	}

	static byte[] bclib(String internal) throws Exception {
		return entry(MODS.resolve("bclib-26.201.2.jar"), internal);
	}

	static byte[] entry(Path jar, String internal) throws Exception {
		Assumptions.assumeTrue(Files.isRegularFile(jar) && Files.isRegularFile(MERGED), "sweep pack or merged base absent");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(internal + ".class");
			assertNotNull(entry, internal + " in " + jar);
			return zip.getInputStream(entry).readAllBytes();
		}
	}

	/** A class from one of fabric-api 0.161's nested modules, as the sweep ships it. */
	static byte[] fabricApi(String module, String internal) throws Exception {
		Path api = MODS.resolve("fabric-api-0.161.0+26.2.jar");
		Assumptions.assumeTrue(Files.isRegularFile(api), "sweep pack absent");
		try (ZipFile zip = new ZipFile(api.toFile())) {
			ZipEntry nested = zip.stream().filter(e -> e.getName().startsWith("META-INF/jars/" + module + "-")).findFirst().orElseThrow();
			try (ZipInputStream inner = new ZipInputStream(new ByteArrayInputStream(zip.getInputStream(nested).readAllBytes()))) {
				for (ZipEntry e; (e = inner.getNextEntry()) != null; ) if (e.getName().equals(internal + ".class")) return inner.readAllBytes();
			}
		}
		throw new AssertionError(internal + " not in " + module);
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name) {
		return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
	}
}
