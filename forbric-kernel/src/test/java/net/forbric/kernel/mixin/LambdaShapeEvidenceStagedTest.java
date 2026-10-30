/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import net.fabricmc.api.EnvType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.ExpectCaught;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.transform.ChainReferences;
import net.forbric.kernel.transform.DuplicateLambdaPruneInjector;
import net.forbric.kernel.transform.TransformChain;
import net.forbric.kernel.transform.TransformContext;

/**
 * {@link LambdaShapeEvidence} on the real bases: on the merged base the native reference decides nothing the pruner's
 * record did not, nor anything the code before it decided, and on NeoForge's genuine base — no pruner record at all — it
 * is what still shims a handler written for vanilla's lambda.
 *
 * <p>Decision equality is measured, not argued. The corpus is every guest mixin in the local packs (under {@code run/}
 * and {@code build/compat-inputs/}) with a lambda selector, and every one that targets {@code LevelRenderer} — where the
 * mods Stage 2.4 names (iris, sodium, sodium-extra, litematica, fabric-api) inject, and which the test checks are there.
 * Each target class is what KernelBoot's pre-Mixin chain hands Mixin, read off KernelBoot's own registrations
 * ({@link ChainReferences#preMixinChain}; the few that need KernelBoot's runtime state are left out and named in the
 * output), under the pruner on (the record full) and off (no record). Each guest goes through the four consumers with
 * the reference on and off: what MixinHandlerShim wraps and where it lands, what InsertedLambdaArgumentShim moves, and
 * MixinFit's verdict, which decides whether a mixin stays in its config; and what MixinOverloadPin and MixinFit say about
 * a selector that does not bind, which reaches the player as a warning and a compatibility reason. Both must be the same.
 * The corpus is what this machine has: on another, the named mods' absence skips these two tests rather than proving
 * less, and the guest mixins themselves are not re-woven here (gate-m9-client boots the merged pack end to end).
 *
 * <p>The code before the reference is measured too: {@code lambda-decisions-b37e6ae0.tsv} records what this lane's parent,
 * b37e6ae0, decided and said over the same inputs, keyed by a digest of them, and every row this machine reproduces must
 * match.
 */
// The staged jars and the test classpath carry no fastutil, which the frame recomputer's hierarchy walk reaches from some
// targets; it abstains there, as the chain's last step, after everything a lambda decision reads (as in
// ForgeTransferShapeAuditChainStagedTest). On the class: whichever merged test runs first measures the corpus for both.
@ExpectCaught("cannot prove a frame repair")
@ResourceLock("native-game-references")
@ResourceLock("system-properties")
class LambdaShapeEvidenceStagedTest {
	private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String LEVEL_RENDERER = "net/minecraft/client/renderer/LevelRenderer";
	private static final String SKY = "lambda$addSkyPass$0";
	private static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
	private static final String VANILLA_SKY_ARGS = "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lnet/minecraft/client/renderer/state/level/SkyRenderState;";
	/** DuplicateLambdaPruneInjector's switch: {@code off} keeps the orphaned bodies, as a boot without the pruner would. */
	private static final String PRUNER = "forbric.pruneDuplicateLambdas";
	/** The decisions b37e6ae0 made over the corpus, one row per guest and pruner arm. */
	static final String PARENT_TABLE = "lambda-decisions-b37e6ae0.tsv";

	/**
	 * The mods Stage 2.4 names as relying on these decisions through their {@code LevelRenderer} mixins, each recognised by
	 * the innermost jar a guest came from: sodium nests fabric-api's rendering modules, and those are fabric-api's.
	 */
	static final Map<String, Predicate<String>> NAMED = namedMods();

	private static Map<String, Predicate<String>> namedMods() {
		Map<String, Predicate<String>> named = new LinkedHashMap<>();
		named.put("iris", jar -> innermost(jar).contains("iris-"));
		named.put("sodium", jar -> innermost(jar).contains("sodium-fabric-") || innermost(jar).contains("sodium-neoforge-"));
		named.put("sodium-extra", jar -> innermost(jar).contains("sodium-extra-"));
		named.put("litematica", jar -> innermost(jar).contains("litematica-"));
		named.put("fabric-api", jar -> innermost(jar).startsWith("fabric-api-") || innermost(jar).matches("fabric-[a-z-]+-v\\d+-.*"));
		return named;
	}

	/** {@code outer.jar!META-INF/jars/inner.jar} -> {@code inner.jar}; a jar that is not nested is its own. */
	private static String innermost(String jar) {
		String inner = jar.substring(jar.lastIndexOf('!') + 1);
		return inner.substring(inner.lastIndexOf('/') + 1);
	}

	/** The pruner's process-wide record as this test found it: every pass below clears or fills it. */
	private Map<String, List<String>> droppedBefore;

	@BeforeEach void save() {
		droppedBefore = DuplicateLambdaPruneInjector.droppedForTest();
	}

	@AfterEach void reset() {
		System.clearProperty(LambdaShapeEvidence.PROPERTY);
		System.clearProperty(PRUNER);
		bind(null);
		NativeGameReferences.configure(null);
		DuplicateLambdaPruneInjector.restoreDroppedForTest(droppedBefore);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The merged base: the reference changes no decision, and no decision is not the parent's
	// ---------------------------------------------------------------------------------------------------------------

	@Test void onTheMergedBaseTheReferenceChangesNoDecisionWithThePrunerOnOrOff() throws Exception {
		Measured measured = merged();
		List<String> different = new ArrayList<>(), reworded = new ArrayList<>();
		int wraps = 0;
		for (Row row : measured.rows()) {
			wraps += row.withReference().wrapped();
			if (!row.recordOnly().decided().equals(row.withReference().decided())) {
				different.add(row + "\n    record only:    " + row.recordOnly().decided() + "\n    with reference: " + row.withReference().decided());
			}
			if (!row.recordOnly().explained().equals(row.withReference().explained())) {
				reworded.add(row + "\n    record only:    " + row.recordOnly().explained() + "\n    with reference: " + row.withReference().explained());
			}
		}
		System.out.println("[LambdaShapeEvidence] " + measured.guests().size() + " guest mixins (lambda selectors, and LevelRenderer), "
				+ measured.rows().size() + " decided twice, " + wraps + " wraps; named mods: " + measured.named());
		assertEquals(List.of(), different, "the native reference must decide nothing on the merged base the pruner's record did not");
		assertEquals(List.of(), reworded, "nor say anything there the record did not: no new warning, no new compatibility reason");
		assertTrue(wraps > 0, "the corpus exercises a wrap (Apoli's sky-skip): equality over no decisions would prove nothing");
	}

	/**
	 * Every row this machine reproduces — same guest, same arm, same digest of the classes the chain handed Mixin and of
	 * the record the pruner left — decides and says exactly what b37e6ae0 did, with the reference on and off. A row whose
	 * inputs moved (a rebuilt base, a chain transformer that changed since) cannot be compared and is counted, not judged.
	 */
	@Test void onTheMergedBaseEveryDecisionIsTheOneTheParentMade() throws Exception {
		Measured measured = merged();
		Map<String, String> parent = parentTable();
		List<String> different = new ArrayList<>();
		TreeSet<String> comparedNamed = new TreeSet<>();
		int compared = 0, unmatched = 0;
		for (Row row : measured.rows()) {
			String recorded = parent.get(row.key());
			if (recorded == null) {
				unmatched++;
				continue;
			}
			compared++;
			for (String mod : NAMED.keySet()) if (NAMED.get(mod).test(row.guest().jar())) comparedNamed.add(mod);
			for (Decisions now : List.of(row.recordOnly(), row.withReference())) {
				if (!recorded.equals(now.digest())) different.add(row + " (" + row.key() + "): b37e6ae0 " + recorded + ", now " + now.digest()
						+ "\n    " + now.decided() + "\n    " + now.explained());
			}
		}
		System.out.println("[LambdaShapeEvidence] against b37e6ae0: " + compared + " of " + measured.rows().size() + " rows compared, "
				+ unmatched + " with inputs the table does not record; named mods compared: " + comparedNamed);
		TestFixtures.require(Fixture.THIRD_PARTY, compared > 0, "no row of " + PARENT_TABLE + " matches this machine's corpus and base");
		assertEquals(List.of(), different, "decisions or diagnoses that are not what b37e6ae0 made over the same inputs");
	}

	/** On the merged base no census row names a lambda the pruner dropped, so asking the census first changes nothing. */
	private static void assertCensusAndRecordAreDisjoint(Map<String, byte[]> classes) throws IOException {
		try (InputStream in = MixinHandlerShim.class.getResourceAsStream(MixinHandlerShim.TABLE)) {
			assertNotNull(in, MixinHandlerShim.TABLE);
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				MixinHandlerShim.Permutation row = line.isBlank() || line.startsWith("#") ? null : MixinHandlerShim.Permutation.parse(line);
				if (row == null || !classes.containsKey(row.owner())) continue;
				assertEquals(List.of(), DuplicateLambdaPruneInjector.droppedDescriptors(row.owner(), row.name()), line);
			}
		}
	}

	/** One guest under one pruner arm: its decisions with the reference off and on, and the digest of what they read. */
	record Row(Guest guest, boolean pruner, String inputs, Decisions recordOnly, Decisions withReference) {
		String key() {
			return (pruner ? "on" : "off") + "\t" + guest.mixin().name + "\t" + inputs;
		}

		@Override public String toString() {
			return (pruner ? "pruner on: " : "pruner off: ") + guest;
		}
	}

	/** The corpus measured once per JVM: both merged tests read it. */
	record Measured(List<Guest> guests, List<Row> rows, Map<String, List<String>> named) {
	}

	private static Measured measured;

	/**
	 * Measures the corpus on the merged base, once: the targets through the pre-Mixin chain under each pruner arm, then each
	 * guest's decisions with the reference off and on. Leaves the pruner's record as the last arm left it; the caller's
	 * {@link #reset} puts back what it found.
	 */
	private static synchronized Measured merged() throws Exception {
		if (measured != null) return measured;
		Path merged = TestFixtures.stagedBase().normalize();
		TestFixtures.requireFiles(Fixture.STAGED, "the merged base", merged);
		List<Guest> guests = corpus(merged);
		TestFixtures.require(Fixture.THIRD_PARTY, !guests.isEmpty(), "no guest mixin with a lambda selector in the local packs");
		Map<String, List<String>> named = new LinkedHashMap<>();
		for (String mod : NAMED.keySet()) {
			List<String> mixins = new ArrayList<>();
			for (Guest guest : guests) {
				if (NAMED.get(mod).test(guest.jar()) && guest.targets().contains(LEVEL_RENDERER)) mixins.add(guest.mixin().name);
			}
			named.put(mod, mixins);
		}
		List<String> absent = named.entrySet().stream().filter(e -> e.getValue().isEmpty()).map(Map.Entry::getKey).toList();
		TestFixtures.require(Fixture.THIRD_PARTY, absent.isEmpty(), "Stage 2.4's named mods with no LevelRenderer mixin in the local packs: "
				+ absent + " (present: " + named + ")");

		List<Path> jars = ChainReferences.stagedJars();
		List<Row> rows = new ArrayList<>();
		try (ForbricClassLoader loader = new ForbricClassLoader(jars.stream().map(LambdaShapeEvidenceStagedTest::url).toArray(URL[]::new),
				LambdaShapeEvidenceStagedTest.class.getClassLoader())) {
			bind(loader);
			List<String> registered = new ArrayList<>(), skipped = new ArrayList<>();
			TransformChain chain = ChainReferences.preMixinChain(jars, registered, skipped);
			assertTrue(registered.contains(DuplicateLambdaPruneInjector.class.getSimpleName()), "premise: the chain prunes: " + registered);
			System.out.println("[LambdaShapeEvidence] the pre-Mixin chain: " + registered.size() + " transformer(s); not reproducible here: " + skipped);
			TreeSet<String> targets = new TreeSet<>();
			for (Guest guest : guests) for (String target : guest.targets()) if (resource(jars, target) != null) targets.add(target);
			// The pruner off first; each arm starts from no record.
			for (boolean pruner : new boolean[] { false, true }) {
				DuplicateLambdaPruneInjector.forgetDroppedForTest();
				if (!pruner) System.setProperty(PRUNER, "off");
				Map<String, byte[]> classes = new TreeMap<>();
				for (String target : targets) {
					classes.put(target, chain.applyBeforeMixin(target.replace('/', '.'), resource(jars, target),
							new TransformContext(EnvType.CLIENT, false, "named")));
				}
				System.clearProperty(PRUNER);
				if (pruner) assertCensusAndRecordAreDisjoint(classes);
				Map<String, List<String>> dropped = DuplicateLambdaPruneInjector.droppedForTest();
				for (Guest guest : guests) {
					System.setProperty(LambdaShapeEvidence.PROPERTY, "off");
					Decisions recordOnly = decide(guest, classes);
					System.clearProperty(LambdaShapeEvidence.PROPERTY);
					Decisions withReference = decide(guest, classes);
					rows.add(new Row(guest, pruner, inputs(guest, classes, dropped), recordOnly, withReference));
				}
				dumpInputs(jars, guests, pruner, classes, dropped, rows);
			}
		} finally {
			bind(null);
		}
		measured = new Measured(guests, rows, named);
		return measured;
	}

	/**
	 * Writes one arm's inputs and decisions under {@code $FORBRIC_LAMBDA_DUMP}, when it is set: what {@link #PARENT_TABLE}'s
	 * rows are made from. {@code jars.txt} (the jars to bind), {@code guests.tsv} and {@code guests/<n>.class} (index, jar,
	 * ecosystem, class file), and per arm {@code arm-<off|on>/classes/} (the chain's output), {@code dropped.tsv} (the
	 * pruner's record), {@code rows.tsv} (index, mixin, inputs, the two digests) and {@code new-<n>.txt} (what was decided
	 * and said, to diff against an older run). The generator that reads them at another commit is
	 * {@code lambda-decisions-generator.java.txt} beside the table.
	 */
	private static void dumpInputs(List<Path> jars, List<Guest> guests, boolean pruner, Map<String, byte[]> classes,
			Map<String, List<String>> dropped, List<Row> rows) throws IOException {
		String dir = System.getenv("FORBRIC_LAMBDA_DUMP");
		if (dir == null || dir.isBlank()) return;
		Path out = Path.of(dir), arm = out.resolve(pruner ? "arm-on" : "arm-off");
		Files.createDirectories(out.resolve("guests"));
		StringBuilder jarList = new StringBuilder(), guestList = new StringBuilder(), record = new StringBuilder(), rowList = new StringBuilder();
		for (Path jar : jars) jarList.append(jar.toAbsolutePath()).append('\n');
		Files.writeString(out.resolve("jars.txt"), jarList);
		for (int i = 0; i < guests.size(); i++) {
			Guest guest = guests.get(i);
			Files.write(out.resolve("guests/" + i + ".class"), guest.raw());
			guestList.append(i).append('\t').append(guest.jar()).append('\t').append(guest.ecosystem() == null ? "-" : guest.ecosystem().name())
					.append('\n');
		}
		Files.writeString(out.resolve("guests.tsv"), guestList);
		for (Map.Entry<String, byte[]> target : classes.entrySet()) {
			Path file = arm.resolve("classes/" + target.getKey() + ".class");
			Files.createDirectories(file.getParent());
			Files.write(file, target.getValue());
		}
		for (Map.Entry<String, List<String>> entry : new TreeMap<>(dropped).entrySet()) {
			for (String desc : entry.getValue()) record.append(entry.getKey()).append('\t').append(desc).append('\n');
		}
		Files.writeString(arm.resolve("dropped.tsv"), record);
		for (Row row : rows) {
			if (row.pruner() != pruner) continue;
			int index = guests.indexOf(row.guest());
			rowList.append(index).append('\t').append(row.guest().mixin().name).append('\t').append(row.inputs()).append('\t')
					.append(row.recordOnly().digest()).append('\t').append(row.withReference().digest()).append('\n');
			Files.writeString(arm.resolve("new-" + index + ".txt"), String.join("\n", row.withReference().decided()) + "\n--\n"
					+ String.join("\n", row.withReference().explained()));
		}
		Files.writeString(arm.resolve("rows.tsv"), rowList);
	}

	/**
	 * What a guest's decisions read: its own class file, each of its targets as the chain left it, and what the pruner
	 * recorded for those targets. Sixteen hex digits of a SHA-256 over them, in a fixed order.
	 */
	static String inputs(Guest guest, Map<String, byte[]> classes, Map<String, List<String>> dropped) {
		MessageDigest digest = sha256();
		digest.update(guest.raw());
		for (String target : new TreeSet<>(guest.targets())) {
			digest.update(target.getBytes(StandardCharsets.UTF_8));
			byte[] bytes = classes.get(target);
			if (bytes != null) digest.update(bytes);
			for (Map.Entry<String, List<String>> entry : new TreeMap<>(dropped).entrySet()) {
				if (!entry.getKey().startsWith(target + "#")) continue;
				digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
				for (String desc : entry.getValue()) digest.update(desc.getBytes(StandardCharsets.UTF_8));
			}
		}
		return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
	}

	/** {@link #PARENT_TABLE}: {@code arm<TAB>mixin<TAB>inputs} to the digest of what b37e6ae0 decided and said. */
	private static Map<String, String> parentTable() throws IOException {
		Map<String, String> table = new TreeMap<>();
		try (InputStream in = LambdaShapeEvidenceStagedTest.class.getResourceAsStream(PARENT_TABLE)) {
			assertNotNull(in, PARENT_TABLE);
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				if (line.isBlank() || line.startsWith("#")) continue;
				String[] part = line.split("\t");
				assertEquals(4, part.length, line);
				assertNull(table.put(part[0] + "\t" + part[1] + "\t" + part[2], part[3]), "a row twice: " + line);
			}
		}
		return table;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The genuine base: the reference is what still shims
	// ---------------------------------------------------------------------------------------------------------------

	/**
	 * Apoli's phasing-blindness sky-skip on NeoForge's own base. There is one {@code lambda$addSkyPass$0}, NeoForge's,
	 * nothing is pruned, and the record is empty; vanilla's shape comes from the FABRIC reference, which the launch reads
	 * from the vanilla-reference sidecar the installer's own writer builds for this base, through the sources
	 * KernelBoot configures. With it the handler is wrapped onto the live lambda, MixinFit sees where it lands, and a
	 * selector that cannot bind is told as the carrier reshape it is; without the sidecar the base answers for NEOFORGE
	 * alone, and the mixin would lose its injector to "Invalid descriptor".
	 */
	@Test void onTheGenuineBaseAHandlerWrittenForVanillasSkyLambdaIsStillShimmed() throws Exception {
		List<Path> genuine = ChainReferences.genuineJars();
		Path sidecar = TestFixtures.vanillaReference(genuine.get(0));
		DuplicateLambdaPruneInjector.forgetDroppedForTest();    // the genuine base carries no name twice: nothing is pruned
		byte[] levelRenderer = ChainReferences.read(genuine, LEVEL_RENDERER);
		ClassNode target = MixinFit.parse(levelRenderer);
		assertEquals(1, target.methods.stream().filter(m -> m.name.equals(SKY)).count(), "premise: one lambda of the name, NeoForge's");
		MethodNode live = target.methods.stream().filter(m -> m.name.equals(SKY)).findFirst().orElseThrow();
		assertNotEquals("(" + VANILLA_SKY_ARGS + ")V", live.desc, "premise: and not vanilla's shape");
		Function<String, ClassNode> targets = name -> name.equals(LEVEL_RENDERER) ? MixinFit.parse(levelRenderer) : null;

		NativeReferenceSources sources = NativeReferenceSources.open(genuine.get(0), sidecar);
		assertTrue(sources.describe().contains("FABRIC from the vanilla-reference sidecar") && sources.describe().contains("FORGE none"),
				"premise: KernelBoot's sources for this launch: " + sources.describe());
		NativeGameReferences.configure(sources);
		try (ForbricClassLoader loader = new ForbricClassLoader(genuine.stream().map(LambdaShapeEvidenceStagedTest::url).toArray(URL[]::new),
				getClass().getClassLoader())) {
			bind(loader);
			assertEquals(List.of(new LambdaShapeEvidence.Shape("(" + VANILLA_SKY_ARGS + ")V", false)),
					LambdaShapeEvidence.vanished(target, SKY), "vanilla's shape, known from the reference alone");

			for (ClassNode mixin : skySkips()) {
				MethodNode handler = mixin.methods.stream().filter(m -> injected(m) != null).findFirst().orElseThrow();
				String name = handler.name;
				assertNotNull(MixinHandlerShim.destination(handler, target), mixin.name + ": MixinFit sees the injector land");
				assertEquals(MixinFit.Verdict.FIT, MixinFit.evaluate(bytes(mixin), path -> path.equals(LEVEL_RENDERER + ".class")
						? levelRenderer : null).verdict(), mixin.name);
				MixinOverloadPin.clearReasons();
				assertTrue(MixinOverloadPin.explain(mixin, handler, SKY, List.of(LEVEL_RENDERER), targets), mixin.name + " is diagnosed");
				assertTrue(String.valueOf(MixinOverloadPin.reasonFor(mixin.name)).contains("whose captures a carrier patch changed from vanilla's"),
						"as the carrier reshape it is: " + MixinOverloadPin.reasonFor(mixin.name));
				assertEquals(1, MixinHandlerShim.adapt(mixin, targets), mixin.name + " is wrapped");
				MethodNode outer = mixin.methods.stream().filter(m -> m.name.equals(name) && injected(m) != null).findFirst().orElseThrow();
				assertEquals(live.desc.replace(")V", CI + ")V"), outer.desc, "the wrapper takes the live lambda's arguments");
			}

			System.setProperty(LambdaShapeEvidence.PROPERTY, "off");
			for (ClassNode mixin : skySkips()) {
				assertEquals(0, MixinHandlerShim.adapt(mixin, targets), "without the reference there is no evidence on this base");
			}
		}

		// The control: the same launch without the sidecar. The base answers for NEOFORGE, and nothing for FABRIC.
		System.clearProperty(LambdaShapeEvidence.PROPERTY);
		NativeGameReferences.configure(NativeReferenceSources.open(genuine.get(0), null));
		try (ForbricClassLoader loader = new ForbricClassLoader(genuine.stream().map(LambdaShapeEvidenceStagedTest::url).toArray(URL[]::new),
				getClass().getClassLoader())) {
			bind(loader);
			assertEquals(List.of(), LambdaShapeEvidence.vanished(target, SKY), "no sidecar, no vanilla shape");
			for (ClassNode mixin : skySkips()) assertEquals(0, MixinHandlerShim.adapt(mixin, targets), mixin.name);
		}
	}

	/** Apoli's real WorldRendererMixin where the local packs have Origins-Legacy, and always a fixture of its shape. */
	private static List<ClassNode> skySkips() throws IOException {
		List<ClassNode> mixins = new ArrayList<>(List.of(skySkipFixture()));
		Path origins = Path.of("run/client-popular/mods/Origins-Legacy-1.12.18+26.2.jar");
		if (Files.isRegularFile(origins)) {
			for (Guest guest : guests(Files.readAllBytes(origins), origins.getFileName().toString(), null)) {
				if (guest.mixin().name.equals("io/github/apace100/apoli/mixin/WorldRendererMixin")) mixins.add(onlySkyHandlers(guest.mixin()));
			}
			assertEquals(2, mixins.size(), "Origins-Legacy carries Apoli's WorldRendererMixin");
		}
		return mixins;
	}

	/** The mixin with only its handlers of {@link #SKY}, so the count of wraps is the sky-skip's alone. */
	private static ClassNode onlySkyHandlers(ClassNode mixin) {
		mixin.methods.removeIf(m -> injected(m) != null && !List.of(SKY).equals(MixinFit.stringList(MixinFit.value(injected(m), "method"))));
		return mixin;
	}

	/** {@code @Inject(method = "lambda$addSkyPass$0") static void skip(GpuBufferSlice, SkyRenderState, CallbackInfo)}. */
	private static ClassNode skySkipFixture() {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.access = Opcodes.ACC_PUBLIC;
		mixin.name = "fixture/SkySkipMixin";
		mixin.superName = "java/lang/Object";
		AnnotationNode at = new AnnotationNode(MIXIN_DESC);
		at.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(LEVEL_RENDERER)))));
		mixin.visibleAnnotations = new ArrayList<>(List.of(at));
		MethodNode handler = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "skip",
				"(" + VANILLA_SKY_ARGS + CI + ")V", null, null);
		AnnotationNode inject = new AnnotationNode(INJECT);
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(SKY)), "at",
				new ArrayList<>(List.of(atHead()))));
		handler.visibleAnnotations = new ArrayList<>(List.of(inject));
		handler.instructions.add(new InsnNode(Opcodes.RETURN));
		mixin.methods = new ArrayList<>(List.of(handler));
		return mixin;
	}

	private static AnnotationNode atHead() {
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "HEAD"));
		return at;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The decisions
	// ---------------------------------------------------------------------------------------------------------------

	/** One guest mixin: its class, its class file as the jar holds it, the jar it came from, the platform it was built for. */
	record Guest(ClassNode mixin, byte[] raw, String jar, Ecosystem ecosystem) {
		List<String> targets() {
			return MixinOverloadPin.targetsOf(mixin);
		}

		@Override public String toString() {
			return mixin.name + " (" + jar + ")";
		}
	}

	/**
	 * What the consumers decided for one guest, and separately what MixinOverloadPin and MixinFit said about it. The
	 * strings are the ones the parent table was recorded from: keep them as they are, or the table no longer compares.
	 */
	record Decisions(List<String> decided, List<String> explained, int wrapped) {
		/** Sixteen hex digits of a SHA-256 over both lists, as the parent table records them. */
		String digest() {
			MessageDigest digest = sha256();
			digest.update(String.join("\n", decided).getBytes(StandardCharsets.UTF_8));
			digest.update("\n--\n".getBytes(StandardCharsets.UTF_8));
			digest.update(String.join("\n", explained).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
		}
	}

	private static Decisions decide(Guest guest, Map<String, byte[]> classes) {
		Function<String, ClassNode> targets = name -> classes.get(name) == null ? null : MixinFit.parse(classes.get(name));
		Function<String, byte[]> resolver = path -> path.endsWith(".class") ? classes.get(path.substring(0, path.length() - 6)) : null;
		Function<String, ClassNode> natives = owner -> guest.ecosystem() == null ? null : NativeGameReferences.reference(guest.ecosystem(), owner);
		List<String> decided = new ArrayList<>(), explained = new ArrayList<>();

		for (MethodNode handler : guest.mixin().methods) {
			if (injected(handler) == null) continue;
			for (String target : guest.targets()) {
				ClassNode node = targets.apply(target);
				MethodNode lands = node == null ? null : MixinHandlerShim.destination(handler, node);
				decided.add("destination " + handler.name + handler.desc + " in " + target + " = " + (lands == null ? "-" : lands.name + lands.desc));
			}
			for (String selector : MixinFit.stringList(MixinFit.value(injected(handler), "method"))) {
				if (!selector.startsWith("lambda$") || selector.indexOf('(') >= 0) continue;
				MixinOverloadPin.clearReasons();
				boolean said = MixinOverloadPin.explain(guest.mixin(), handler, selector, guest.targets(), targets);
				explained.add(handler.name + " " + selector + " " + said + " " + MixinOverloadPin.reasonFor(guest.mixin().name));
			}
		}
		ClassNode shimmed = copy(guest.mixin());
		int wrapped = MixinHandlerShim.adapt(shimmed, targets);
		decided.add("MixinHandlerShim wraps " + wrapped + ": " + signature(shimmed));
		ClassNode expanded = copy(guest.mixin());
		decided.add("InsertedLambdaArgumentShim moves " + InsertedLambdaArgumentShim.adapt(expanded, targets, natives) + ": " + signature(expanded));
		MixinFit.Result fit = MixinFit.evaluate(bytes(guest.mixin()), resolver);
		decided.add("MixinFit " + fit.verdict() + " " + fit.resolved() + "/" + fit.total());
		explained.add("MixinFit unresolved " + fit.unresolved() + " rejected " + fit.rejected());
		return new Decisions(decided, explained, wrapped);
	}

	private static AnnotationNode injected(MethodNode method) {
		AnnotationNode injector = MixinFit.injectorOf(method);
		return injector == null || MixinFit.value(injector, "method") == null ? null : injector;
	}

	/** Every method with its descriptor and every selector it carries: what a consumer's edit of the class amounts to. */
	private static String signature(ClassNode mixin) {
		List<String> out = new ArrayList<>();
		for (MethodNode m : mixin.methods) {
			AnnotationNode injector = injected(m);
			out.add(m.name + m.desc + (injector == null ? "" : " " + MixinFit.stringList(MixinFit.value(injector, "method"))));
		}
		out.sort(null);
		return String.join("; ", out);
	}

	private static ClassNode copy(ClassNode mixin) {
		return MixinFit.parse(bytes(mixin));
	}

	private static byte[] bytes(ClassNode mixin) {
		ClassWriter writer = new ClassWriter(0);
		mixin.accept(writer);
		return writer.toByteArray();
	}

	/** {@code internal}'s class file from the first of {@code jars} that holds it, or null. */
	private static byte[] resource(List<Path> jars, String internal) {
		for (Path jar : jars) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				ZipEntry entry = zip.getEntry(internal + ".class");
				if (entry == null) continue;
				try (InputStream in = zip.getInputStream(entry)) {
					return in.readAllBytes();
				}
			} catch (IOException unreadable) {
				throw new java.io.UncheckedIOException(unreadable);
			}
		}
		return null;
	}

	private static void bind(ForbricClassLoader loader) {
		NativeGameReferences.bind(loader);
	}

	private static URL url(Path jar) {
		try {
			return jar.toUri().toURL();
		} catch (java.net.MalformedURLException impossible) {
			throw new AssertionError(impossible);
		}
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (java.security.NoSuchAlgorithmException impossible) {
			throw new AssertionError(impossible);
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The corpus
	// ---------------------------------------------------------------------------------------------------------------

	/** The packs a machine has: the local run/ packs and the compat inputs, as MixinFitLivenessCensusStagedTest reads them. */
	static List<Path> packs() {
		List<Path> packs = new ArrayList<>();
		for (String pack : List.of("client-merged-pack/mods", "client-popular/mods", "client-neo-pack/mods", "client-kernel/mods",
				"carry-on", "modmenu")) packs.add(Path.of("run", pack));
		for (String pack : List.of("sweep90", "mix80", "popular", "random")) packs.add(Path.of("build/compat-inputs", pack, "mods"));
		return packs;
	}

	/**
	 * Every guest mixin in {@link #packs()} with a bare lambda selector, or targeting {@code LevelRenderer}, on a class
	 * {@code base} carries; one per class name, the first in pack and file order.
	 */
	private static List<Guest> corpus(Path base) throws IOException {
		Map<String, Guest> byName = new TreeMap<>();
		try (ZipFile game = new ZipFile(base.toFile())) {
			for (Path pack : packs()) {
				if (!Files.isDirectory(pack)) continue;
				try (Stream<Path> jars = Files.list(pack)) {
					for (Path jar : jars.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
						for (Guest guest : guests(Files.readAllBytes(jar), jar.getFileName().toString(), null)) {
							if (guest.targets().stream().anyMatch(t -> game.getEntry(t + ".class") != null)) byName.putIfAbsent(guest.mixin().name, guest);
						}
					}
				}
			}
		}
		return new ArrayList<>(byName.values());
	}

	/**
	 * The guest mixins of one jar and of every jar nested in it whose injectors select a lambda by name, or that target
	 * {@code LevelRenderer}.
	 */
	private static List<Guest> guests(byte[] jar, String name, Ecosystem inherited) throws IOException {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
			for (ZipEntry e; (e = zip.getNextEntry()) != null;) if (!e.isDirectory()) entries.put(e.getName(), zip.readAllBytes());
		}
		Ecosystem ecosystem = entries.containsKey("fabric.mod.json") ? Ecosystem.FABRIC
				: entries.containsKey("META-INF/neoforge.mods.toml") ? Ecosystem.NEOFORGE
				: entries.containsKey("META-INF/mods.toml") ? Ecosystem.FORGE : inherited;
		List<Guest> out = new ArrayList<>();
		for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
			if (entry.getKey().endsWith(".jar")) {
				out.addAll(guests(entry.getValue(), name + "!" + entry.getKey(), ecosystem));
				continue;
			}
			if (!entry.getKey().endsWith(".class")) continue;
			ClassNode node = new ClassNode();
			try {
				new ClassReader(entry.getValue()).accept(node, 0);
			} catch (RuntimeException unreadable) {
				continue;    // not a class file ASM reads; no mixin of interest
			}
			if (!isMixin(node)) continue;
			boolean lambda = node.methods.stream().anyMatch(m -> injected(m) != null
					&& MixinFit.stringList(MixinFit.value(injected(m), "method")).stream().anyMatch(s -> s.trim().startsWith("lambda$")));
			if (lambda || MixinOverloadPin.targetsOf(node).contains(LEVEL_RENDERER)) out.add(new Guest(node, entry.getValue(), name, ecosystem));
		}
		return out;
	}

	private static boolean isMixin(ClassNode node) {
		for (List<AnnotationNode> list : Arrays.asList(node.visibleAnnotations, node.invisibleAnnotations)) {
			if (list != null && list.stream().anyMatch(a -> a.desc.equals(MIXIN_DESC))) return true;
		}
		return false;
	}
}
