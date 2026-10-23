package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.LateInjectionIsolation;

/**
 * {@link LateInjectionIsolation} through the real weave: one mod's MixinExtras late injector fails on a class another
 * mod also mixes into, and the other mod's mixin must still be in the class.
 *
 * <p>Two mods, neither named by the kernel: a lantern mod and a sconce mod, each with a config that is {@code required}
 * with {@code defaultRequire: 1}, as spruceui's and fabric-screen-api's are. The sconce mod's mixin adds an interface
 * and a callback to {@code Lamp}, and fits. The lantern mod's mixin has an ordinary callback and a late
 * {@code @ModifyExpressionValue} that fit, and a {@code @WrapOperation} that cannot land, in one of the two ways a late
 * injector fails after the rest of the class is woven:
 * <ul>
 * <li><b>missing</b>: the wrapped call is not in the method (the merged base's lost {@code ClientHooks.extractScreen}),
 *     with {@code mixin.debug.countInjections} on, so Mixin's {@code expect} check throws in {@code latePostInject};
 * <li><b>mismatch</b>: a descriptor-less target binds a call the handler was not written for, and the wrap's own
 *     handler check throws in {@code lateInject}, at any setting.
 * </ul>
 * Isolated, the lamp carries both mods: the lantern's head callback and its brightness bump, the sconce's callback and
 * its interface; the lantern mod is reported and the sconce mod is not. Switched off, upstream MixinExtras hands back the
 * lamp unwoven, and the probe's cast to the sconce's interface throws the ClassCastException the game died on. A config
 * the kernel does not relax stays required, and its late failure still stops the class either way.
 *
 * <p>Four more shapes, each with its control:
 * <ul>
 * <li><b>newvalue</b>: the failing late injector is a {@code @ModifyExpressionValue} on the {@code new} inside one branch
 *     of {@code kindle()}, its handler written for the wrong type. That injector has a path that inserts a {@code DUP}
 *     behind a {@code new} before it checks its handler; on javac's {@code new} it leaves nothing behind (one
 *     {@code DUP} after the {@code new}, javac's own), so the lamp analyses and is kept, as in the mismatch. Off,
 *     unwoven.
 * <li><b>partial</b>: a descriptor-less wrap in {@code flare()} matches brightness(3) and then brightness(); it wraps the
 *     first and its handler check fails at the second, while the sconce mod's {@code @ModifyExpressionValue} sits on the
 *     first. The lamp is kept: the first call wrapped and bracketed (21, times ten, plus a hundred), the second plain,
 *     the class verifying. Off, unwoven.
 * <li><b>kindle</b>: the mismatch, with the lantern config's plugin leaving a {@code DUP} behind the {@code new} in one
 *     branch of {@code kindle()} before the late injections run — a stand-in for an injector that changes code before it
 *     checks, which no MixinExtras 0.5.4 injector is known to do on javac-compiled code — so that when the wrap fails
 *     the two branches no longer meet with the same stack. The isolation does not keep that: it hands the lamp back
 *     unwoven, as upstream, and says why.
 * <li><b>takeover</b>: the lantern mod bundles a newer MixinExtras under its own package and starts it from its config
 *     plugin, after the kernel installed the isolation. The kernel's applicator is gone, the newer one has its own queue,
 *     and late injections fail the upstream way: the isolation says so once, and the lamp is handed back unwoven, which
 *     is what the line says. Off, the same lamp and no line. The bundled copy is the real MixinExtras jar relocated,
 *     with a version one above it, so it wins the takeover as a newer release does.
 * </ul>
 */
class LateInjectionIsolationWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/lateinjection");
	private static final String MISSING = "lanterns-missing.mixins.json";
	private static final String MISMATCH = "lanterns-mismatch.mixins.json";
	private static final String PARTIAL = "lanterns-partial.mixins.json";
	private static final String KINDLE = "lanterns-kindle.mixins.json";
	private static final String TAKEOVER = "lanterns-takeover.mixins.json";
	private static final String NEW_VALUE = "lanterns-newvalue.mixins.json";
	private static final String SCONCES = "sconces.mixins.json";
	private static final String LANTERN_MOD = "lanterns";
	private static final String SCONCE_MOD = "sconces";
	private static final String TARGET = "fixture/lateinjection/Lamp";

	private static final String BOTH_MODS = WeaveHarnessMain.DONE + " glow 15 | [lantern-head, light, sconce-return] | wall";
	private static final String UNWOVEN = WeaveHarnessMain.THREW + "java.lang.ClassCastException";
	/** The flare's first brightness wrapped (21 times ten) and bracketed by the sconce (plus a hundred); its second plain. */
	private static final String PARTIAL_KEPT = WeaveHarnessMain.DONE
			+ " glow 14 | flare 310/1 | [lantern-head, light, sconce-return] | wall";
	private static final String HANDED_BACK = "is handed back unwoven, as upstream MixinExtras does: after "
			+ "org.example.lanterns.mixin.LanternMismatchMixin's late injection failed";
	private static final String KINDLE_METHOD = "kindle(Z)Ljava/lang/String;";
	/** The real MixinExtras, relocated where the lantern mod bundles it. */
	private static final String OURS = "com/llamalad7/mixinextras/";
	private static final String BUNDLED = "org/example/lanterns/shadow/mixinextras/";
	private static final String NEWER_APPLICATOR = BUNDLED.replace('/', '.') + "injector.LateInjectionApplicatorExtension";
	private static final String COUNT = "mixin.debug.countInjections";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result missing;
	private static WeaveHarness.Result missingOff;
	private static WeaveHarness.Result mismatch;
	private static WeaveHarness.Result mismatchOff;
	private static WeaveHarness.Result strict;
	private static WeaveHarness.Result strictOff;
	private static WeaveHarness.Result partial;
	private static WeaveHarness.Result partialOff;
	private static WeaveHarness.Result kindle;
	private static WeaveHarness.Result kindleOff;
	private static WeaveHarness.Result takeover;
	private static WeaveHarness.Result takeoverOff;
	private static WeaveHarness.Result newValue;
	private static WeaveHarness.Result newValueOff;

	@BeforeAll static void weave() throws Exception {
		fixture = WeaveHarness.fixture(work, "lateinjection", sources(), Map.of(
				MISSING, SOURCES.resolve(MISSING), MISMATCH, SOURCES.resolve(MISMATCH), SCONCES, SOURCES.resolve(SCONCES),
				PARTIAL, SOURCES.resolve(PARTIAL), KINDLE, SOURCES.resolve(KINDLE), TAKEOVER, SOURCES.resolve(TAKEOVER),
				NEW_VALUE, SOURCES.resolve(NEW_VALUE)));
		missing = run("missing", MISSING, Map.of(COUNT, "true"));
		missingOff = run("missing-off", MISSING, Map.of(COUNT, "true", LateInjectionIsolation.PROPERTY, "off"));
		mismatch = run("mismatch", MISMATCH, Map.of());
		mismatchOff = run("mismatch-off", MISMATCH, Map.of(LateInjectionIsolation.PROPERTY, "off"));
		strict = run("strict", MISMATCH, Map.of("forbric.relaxGuestMixins", "off"));
		strictOff = run("strict-off", MISMATCH, Map.of("forbric.relaxGuestMixins", "off", LateInjectionIsolation.PROPERTY, "off"));
		partial = run("partial", PARTIAL, FLARE_PROBE, Map.of());
		partialOff = run("partial-off", PARTIAL, FLARE_PROBE, Map.of(LateInjectionIsolation.PROPERTY, "off"));
		kindle = run("kindle", KINDLE, Map.of());
		kindleOff = run("kindle-off", KINDLE, Map.of(LateInjectionIsolation.PROPERTY, "off"));
		newValue = run("newvalue", NEW_VALUE, Map.of());
		newValueOff = run("newvalue-off", NEW_VALUE, Map.of(LateInjectionIsolation.PROPERTY, "off"));
		String bundled = newerMixinExtras().toString();
		takeover = run("takeover", TAKEOVER, Map.of(WeaveHarnessMain.LIBRARIES, bundled));
		takeoverOff = run("takeover-off", TAKEOVER, Map.of(WeaveHarnessMain.LIBRARIES, bundled,
				LateInjectionIsolation.PROPERTY, "off"));
	}

	@Test void aWrapThatFindsNothingUnderCountInjectionsCostsOnlyItself() throws Exception {
		assertTrue(isolated(missing), missing.describe() + "\nfindings " + missing.findings());
		WeaveHarness.assertWovenAndVerified(missing, TARGET, fixture);
	}

	@Test void aWrapWhoseHandlerCheckFailsCostsOnlyItself() throws Exception {
		assertTrue(isolated(mismatch), mismatch.describe() + "\nfindings " + mismatch.findings());
		WeaveHarness.assertWovenAndVerified(mismatch, TARGET, fixture);
	}

	@Test void switchedOffTheClassIsHandedBackUnwovenForBothMods() {
		assertTrue(unwoven(missingOff), missingOff.describe());
		assertTrue(unwoven(mismatchOff), mismatchOff.describe());
	}

	/** A config that stays required keeps upstream's answer: its late failure is not set aside, on or off. */
	@Test void aRequiredConfigsLateFailureStillStopsTheClass() {
		for (WeaveHarness.Result run : List.of(strict, strictOff)) {
			assertTrue(run.printed(WeaveHarnessMain.THREW), run.describe());
			assertFalse(run.printed(BOTH_MODS), run.describe());
			assertFalse(run.printed("one late injection failed"), run.describe());
		}
	}

	/**
	 * A wrap that rewrote one call and failed at the next is kept: the call it rewrote does what the wrap and the sconce's
	 * value modifier on it asked, the other call is untouched, and the lamp verifies with the bridge the rewritten call
	 * needs.
	 */
	@Test void aWrapThatFailsHalfWayKeepsTheCallItRewroteWhole() throws Exception {
		assertTrue(keptPartial(partial), partial.describe() + "\nfindings " + partial.findings());
		WeaveHarness.assertWovenAndVerified(partial, TARGET, fixture);
		assertEquals(1, bridges(partial.defined(TARGET), "flare"), "one rewritten call, one bridge:\n" + partial.describe());
		assertTrue(unwovenFlare(partialOff), partialOff.describe());
	}

	/**
	 * A value modifier on a javac {@code new} whose handler check fails leaves nothing behind it: the lamp is kept and
	 * verifies, and its {@code kindle()} has the one {@code DUP} javac wrote. A later MixinExtras that inserted its own
	 * before the check would turn this red: the branches would no longer meet and the lamp would be handed back.
	 */
	@Test void aValueModifierOnANewThatFailsItsCheckLeavesNothingBehind() throws Exception {
		assertTrue(isolated(newValue) && !newValue.printed("is handed back unwoven"),
				newValue.describe() + "\nfindings " + newValue.findings());
		// What failed is the value modifier's own handler check at the new, or this run tests nothing about it.
		assertTrue(newValue.printed("Lamp::lanterns$rekindle") && newValue.printed("Found unexpected argument type "
				+ "java.lang.String at index 0, expected java.lang.StringBuilder"), newValue.describe());
		WeaveHarness.assertWovenAndVerified(newValue, TARGET, fixture);
		assertEquals(1, dups(newValue.defined(TARGET), "kindle"), "javac's DUP and nothing else:\n" + newValue.describe());
		assertTrue(unwoven(newValueOff), newValueOff.describe());
	}

	/** A class left inconsistent when a late injection fails is not kept: the lamp goes back unwoven, as upstream. */
	@Test void aFailureThatLeavesTheClassInconsistentHandsItBack() {
		assertTrue(handedBack(kindle), kindle.describe());
		assertTrue(unwoven(kindleOff) && !kindleOff.printed(HANDED_BACK), kindleOff.describe());
	}

	/** A newer MixinExtras that took over after install strands the isolation; it says so once, and the line is true. */
	@Test void aNewerMixinExtrasTakingOverIsSaidOnce() {
		assertTrue(takeover.printed("Initializing MixinExtras via " + BUNDLED.replace('/', '.')), "the bundled copy must "
				+ "be the one that initialised, or this run tests nothing:\n" + takeover.describe());
		assertTrue(stranded(takeover), takeover.describe());
		assertTrue(unwoven(takeoverOff) && occurrences(takeoverOff, LateInjectionIsolation.STRANDED) == 0, takeoverOff.describe());
	}

	/** Each predicate must fail on the other run, or the control proves nothing about the isolation. */
	@Test void theControlFlipsEveryAssertion() {
		for (WeaveHarness.Result on : List.of(missing, mismatch, newValue)) assertTrue(isolated(on) && !unwoven(on), on.label());
		for (WeaveHarness.Result off : List.of(missingOff, mismatchOff, newValueOff)) {
			assertTrue(unwoven(off) && !isolated(off), off.label());
		}
		assertTrue(keptPartial(partial) && !unwovenFlare(partial), partial.label());
		assertTrue(unwovenFlare(partialOff) && !keptPartial(partialOff), partialOff.label());
		assertTrue(handedBack(kindle) && !handedBack(kindleOff), kindle.label());
		assertTrue(stranded(takeover) && !stranded(takeoverOff), takeover.label());
		// Nothing strands the isolation where nothing took over.
		for (WeaveHarness.Result run : List.of(missing, mismatch, partial, kindle, newValue)) {
			assertEquals(0, occurrences(run, LateInjectionIsolation.STRANDED), run.describe());
		}
	}

	private static boolean keptPartial(WeaveHarness.Result run) {
		return run.printed(PARTIAL_KEPT) && run.printed("one late injection failed")
				&& !losses(run, LANTERN_MOD).isEmpty() && losses(run, SCONCE_MOD).isEmpty();
	}

	private static boolean unwovenFlare(WeaveHarness.Result run) {
		return unwoven(run) && !run.printed(PARTIAL_KEPT);
	}

	private static boolean handedBack(WeaveHarness.Result run) {
		return unwoven(run) && occurrences(run, HANDED_BACK) == 1 && run.printed(KINDLE_METHOD) && run.printed("Incompatible stack heights")
				&& !losses(run, LANTERN_MOD).isEmpty();
	}

	private static boolean stranded(WeaveHarness.Result run) {
		return unwoven(run) && occurrences(run, LateInjectionIsolation.STRANDED) == 1 && run.printed(NEWER_APPLICATOR + " is active instead");
	}

	private static int occurrences(WeaveHarness.Result run, String text) {
		int n = 0;
		for (int at = run.output().indexOf(text); at >= 0; at = run.output().indexOf(text, at + 1)) n++;
		return n;
	}

	/** How many {@code DUP}s the woven {@code method}'s body has. */
	private static long dups(byte[] woven, String method) {
		ClassNode node = new ClassNode();
		new ClassReader(woven).accept(node, 0);
		MethodNode body = node.methods.stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow();
		long dups = 0;
		for (AbstractInsnNode insn : body.instructions) {
			if (insn.getOpcode() == Opcodes.DUP) dups++;
		}
		return dups;
	}

	/** How many of MixinExtras' operation bridges the woven {@code method}'s body reaches through invokedynamic. */
	private static long bridges(byte[] woven, String method) {
		ClassNode node = new ClassNode();
		new ClassReader(woven).accept(node, 0);
		MethodNode body = node.methods.stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow();
		long reached = 0;
		for (AbstractInsnNode insn : body.instructions) {
			if (insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy) {
				for (Object arg : indy.bsmArgs) {
					if (arg instanceof org.objectweb.asm.Handle handle && handle.getName().startsWith("mixinextras$bridge$")) {
						assertTrue(node.methods.stream().anyMatch(m -> m.name.equals(handle.getName()) && m.desc.equals(handle.getDesc())),
								"the bridge " + handle.getName() + " a rewritten call reaches is not in the class");
						reached++;
					}
				}
			}
		}
		return reached;
	}

	private static boolean isolated(WeaveHarness.Result run) {
		return run.printed(BOTH_MODS) && run.printed("one late injection failed")
				&& !losses(run, LANTERN_MOD).isEmpty() && losses(run, SCONCE_MOD).isEmpty();
	}

	private static boolean unwoven(WeaveHarness.Result run) {
		return run.printed(UNWOVEN) && !run.printed(BOTH_MODS) && !run.printed("one late injection failed");
	}

	/** What the run reported against {@code mod}: a mixin that failed to apply, or an injector that did not attach. */
	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run, String mod) {
		return run.findings().stream().filter(f -> f.modId().equals(mod) && f.id().startsWith("mixin")).toList();
	}

	private static final String PROBE = "fixture.lateinjection.Probe";
	private static final String FLARE_PROBE = "fixture.lateinjection.FlareProbe";

	private static WeaveHarness.Result run(String label, String lanterns, Map<String, String> properties) throws Exception {
		return run(label, lanterns, PROBE, properties);
	}

	private static WeaveHarness.Result run(String label, String lanterns, String probe, Map<String, String> properties)
			throws Exception {
		return WeaveHarness.run(work, label, fixture, List.of(
				new WeaveHarness.Config(lanterns, LANTERN_MOD, Ecosystem.FABRIC),
				new WeaveHarness.Config(SCONCES, SCONCE_MOD, Ecosystem.FABRIC)),
				List.of(), EnvType.CLIENT, probe, "probe", properties);
	}

	/**
	 * The MixinExtras jar the runs weave with, every class moved under {@link #BUNDLED} the way a mod shades it, and its
	 * service's version raised by one so that it is the newer copy and takes over from the kernel's.
	 */
	private static Path newerMixinExtras() throws Exception {
		Path jar = work.resolve("lanterns-bundled-mixinextras.jar");
		Remapper relocate = new Remapper() {
			@Override
			public String map(String internalName) {
				return internalName.startsWith(OURS) ? BUNDLED + internalName.substring(OURS.length()) : internalName;
			}
		};
		int raised = 0;
		try (ZipFile source = new ZipFile(WeaveHarness.mixinExtrasJar());
				JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
			for (ZipEntry entry : Collections.list(source.entries())) {
				if (entry.isDirectory() || !entry.getName().startsWith(OURS) || !entry.getName().endsWith(".class")) continue;
				ClassNode node = new ClassNode();
				try (var in = source.getInputStream(entry)) {
					new ClassReader(in.readAllBytes()).accept(new ClassRemapper(node, relocate), 0);
				}
				if (node.name.equals(BUNDLED + "service/MixinExtrasServiceImpl")) {
					for (MethodNode method : node.methods) {
						if (!method.name.equals("getVersion") || !method.desc.equals("()I")) continue;
						for (AbstractInsnNode insn : method.instructions.toArray()) {
							if (insn.getOpcode() != Opcodes.IRETURN) continue;
							InsnList plusOne = new InsnList();
							plusOne.add(new InsnNode(Opcodes.ICONST_1));
							plusOne.add(new InsnNode(Opcodes.IADD));
							method.instructions.insertBefore(insn, plusOne);
							raised++;
						}
						method.maxStack += 1;
					}
				}
				ClassWriter writer = new ClassWriter(0);
				node.accept(writer);
				out.putNextEntry(new JarEntry(node.name + ".class"));
				out.write(writer.toByteArray());
				out.closeEntry();
			}
		}
		assertEquals(1, raised, "the relocated MixinExtras service's getVersion() is not the one return this raises");
		return jar;
	}

	private static List<Path> sources() throws Exception {
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
	}
}
