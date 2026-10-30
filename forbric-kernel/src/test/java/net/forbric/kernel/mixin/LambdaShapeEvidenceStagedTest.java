/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.transform.ChainReferences;
import net.forbric.kernel.transform.DuplicateLambdaPruneInjector;

/**
 * {@link LambdaShapeEvidence} on the real bases: on the merged base the native reference decides nothing the pruner's
 * record did not, and on NeoForge's genuine base — no pruner record at all — it is what still shims a handler written for
 * vanilla's lambda.
 *
 * <p>Decision equality is measured, not argued. Every lambda selector of every guest mixin in the local corpus (the
 * packs under {@code run/} and {@code build/compat-inputs/}) goes through the four consumers with the reference on and
 * off, under the pruner on (pruned bases, the record full) and off (raw bases, no record): what MixinHandlerShim wraps
 * and where it lands, what InsertedLambdaArgumentShim moves, and MixinFit's verdict, which decides whether a mixin stays
 * in its config. Only MixinOverloadPin's diagnosis may differ, and only in what it says: it is reported, not decided on.
 */
@ResourceLock("native-game-references")
@ResourceLock("system-properties")
class LambdaShapeEvidenceStagedTest {
	private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String LEVEL_RENDERER = "net/minecraft/client/renderer/LevelRenderer";
	private static final String SKY = "lambda$addSkyPass$0";
	private static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
	private static final String VANILLA_SKY_ARGS = "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lnet/minecraft/client/renderer/state/level/SkyRenderState;";

	@AfterEach void reset() {
		System.clearProperty(LambdaShapeEvidence.PROPERTY);
		bind(null);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The merged base: the reference changes no decision
	// ---------------------------------------------------------------------------------------------------------------

	@Test void onTheMergedBaseTheReferenceChangesNoDecisionWithThePrunerOnOrOff() throws Exception {
		Path merged = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar").normalize();
		TestFixtures.requireFiles(Fixture.STAGED, "the merged base", merged);
		List<Guest> guests = corpus(merged);
		TestFixtures.require(Fixture.THIRD_PARTY, !guests.isEmpty(), "no guest mixin with a lambda selector in the local packs");

		try (ForbricClassLoader loader = new ForbricClassLoader(new URL[] { merged.toUri().toURL() }, getClass().getClassLoader());
				ZipFile base = new ZipFile(merged.toFile())) {
			bind(loader);
			StringBuilder report = new StringBuilder();
			int wraps = 0, decisions = 0;
			List<String> different = new ArrayList<>();
			// The pruner off first: each pass starts from no record, and the last leaves the record a real boot would.
			for (boolean pruner : new boolean[] { false, true }) {
				DuplicateLambdaPruneInjector.forgetDroppedForTest();
				Map<String, byte[]> classes = new HashMap<>();
				for (Guest guest : guests) for (String target : guest.targets()) classes.computeIfAbsent(target, t -> baseClass(base, t, pruner));
				if (pruner) assertCensusAndRecordAreDisjoint(classes);
				for (Guest guest : guests) {
					System.setProperty(LambdaShapeEvidence.PROPERTY, "off");
					Decisions recordOnly = decide(guest, classes);
					System.clearProperty(LambdaShapeEvidence.PROPERTY);
					Decisions withReference = decide(guest, classes);
					decisions++;
					wraps += withReference.wrapped();
					if (!recordOnly.decided().equals(withReference.decided())) {
						different.add((pruner ? "pruner on: " : "pruner off: ") + guest + "\n    record only:    " + recordOnly.decided()
								+ "\n    with reference: " + withReference.decided());
					}
					if (!recordOnly.explained().equals(withReference.explained())) {
						report.append(pruner ? "pruner on: " : "pruner off: ").append(guest).append(" — diagnosis ")
								.append(recordOnly.explained()).append(" -> ").append(withReference.explained()).append('\n');
					}
				}
			}
			System.out.println("[LambdaShapeEvidence] " + guests.size() + " guest mixins with lambda selectors, " + decisions
					+ " decided twice; " + wraps + " wraps; diagnoses that changed only in wording:\n" + report);
			assertEquals(List.of(), different, "the native reference must decide nothing on the merged base the pruner's record did not");
			assertTrue(wraps > 0, "the corpus exercises a wrap (Apoli's sky-skip): equality over no decisions would prove nothing");
		}
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

	// ---------------------------------------------------------------------------------------------------------------
	// The genuine base: the reference is what still shims
	// ---------------------------------------------------------------------------------------------------------------

	/**
	 * Apoli's phasing-blindness sky-skip on NeoForge's own base. There is one {@code lambda$addSkyPass$0}, NeoForge's,
	 * nothing is pruned, and the record is empty; vanilla's shape comes from the FABRIC reference (served here from the
	 * vanilla jar, the way the sidecar Stage 2.2 adds serves it). With it the handler is wrapped onto the live lambda and
	 * MixinFit sees where it lands; without it the mixin would lose its injector to "Invalid descriptor".
	 */
	@Test void onTheGenuineBaseAHandlerWrittenForVanillasSkyLambdaIsStillShimmed(@TempDir Path work) throws Exception {
		List<Path> genuine = ChainReferences.genuineJars();
		DuplicateLambdaPruneInjector.forgetDroppedForTest();    // the genuine base carries no name twice: nothing is pruned
		byte[] levelRenderer = ChainReferences.read(genuine, LEVEL_RENDERER);
		ClassNode target = MixinFit.parse(levelRenderer);
		assertEquals(1, target.methods.stream().filter(m -> m.name.equals(SKY)).count(), "premise: one lambda of the name, NeoForge's");
		MethodNode live = target.methods.stream().filter(m -> m.name.equals(SKY)).findFirst().orElseThrow();
		assertNotEquals("(" + VANILLA_SKY_ARGS + ")V", live.desc, "premise: and not vanilla's shape");
		Function<String, ClassNode> targets = name -> name.equals(LEVEL_RENDERER) ? MixinFit.parse(levelRenderer) : null;

		Path sidecar = vanillaReferenceSidecar(work, LEVEL_RENDERER);
		try (ForbricClassLoader loader = new ForbricClassLoader(new URL[] { genuine.get(0).toUri().toURL(), sidecar.toUri().toURL() },
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
				assertEquals(1, MixinHandlerShim.adapt(mixin, targets), mixin.name + " is wrapped");
				MethodNode outer = mixin.methods.stream().filter(m -> m.name.equals(name) && injected(m) != null).findFirst().orElseThrow();
				assertEquals(live.desc.replace(")V", CI + ")V"), outer.desc, "the wrapper takes the live lambda's arguments");
			}

			System.setProperty(LambdaShapeEvidence.PROPERTY, "off");
			for (ClassNode mixin : skySkips()) {
				assertEquals(0, MixinHandlerShim.adapt(mixin, targets), "without the reference there is no evidence on this base");
			}
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

	/**
	 * A jar serving the FABRIC native reference for {@code owners} from the vanilla jar, in the layout the merged base embeds
	 * and the Stage 2.2 sidecar ships: {@code index.tsv} pinning each class by SHA-256, and the class beside it.
	 */
	private static Path vanillaReferenceSidecar(Path dir, String... owners) throws Exception {
		Path vanilla = TestFixtures.vanillaJar();
		TestFixtures.requireFiles(Fixture.MC_LIBRARIES, "Minecraft 26.2's own client jar, the FABRIC reference", vanilla);
		Path sidecar = dir.resolve("vanilla-reference.jar");
		String prefix = "META-INF/forbric/native-reference/" + Ecosystem.FABRIC.name() + "/";
		StringBuilder index = new StringBuilder("# forbric-native-reference-v1\n");
		try (ZipFile jar = new ZipFile(vanilla.toFile()); OutputStream file = Files.newOutputStream(sidecar);
				ZipOutputStream out = new ZipOutputStream(file)) {
			for (String owner : owners) {
				byte[] bytes;
				try (InputStream in = jar.getInputStream(jar.getEntry(owner + ".class"))) { bytes = in.readAllBytes(); }
				index.append(owner).append('\t').append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).append('\n');
				out.putNextEntry(new ZipEntry(prefix + owner + ".class.bin"));
				out.write(bytes);
				out.closeEntry();
			}
			out.putNextEntry(new ZipEntry(prefix + "index.tsv"));
			out.write(index.toString().getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}
		return sidecar;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The decisions
	// ---------------------------------------------------------------------------------------------------------------

	/** One guest mixin: its class, the jar it came from, the platform it was built for. */
	record Guest(ClassNode mixin, String jar, Ecosystem ecosystem) {
		List<String> targets() {
			return MixinOverloadPin.targetsOf(mixin);
		}

		@Override public String toString() {
			return mixin.name + " (" + jar + ")";
		}
	}

	/** What the consumers decided for one guest, and separately what MixinOverloadPin said. */
	record Decisions(List<String> decided, List<String> explained, int wrapped) {
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

	/** {@code internal} from the base, through the duplicate-lambda pruner when it is on (which records what it drops). */
	private static byte[] baseClass(ZipFile base, String internal, boolean pruner) {
		ZipEntry entry = base.getEntry(internal + ".class");
		if (entry == null) return null;
		try (InputStream in = base.getInputStream(entry)) {
			byte[] raw = in.readAllBytes();
			return pruner ? new DuplicateLambdaPruneInjector().transform(internal.replace('/', '.'), raw, null) : raw;
		} catch (IOException unreadable) {
			throw new java.io.UncheckedIOException(unreadable);
		}
	}

	private static void bind(ForbricClassLoader loader) {
		NativeGameReferences.bind(loader);
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

	/** Every guest mixin in {@link #packs()} with a bare lambda selector on a class {@code base} carries; one per class name. */
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

	/** The guest mixins of one jar and of every jar nested in it, whose injectors select a lambda by name. */
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
			if (lambda) out.add(new Guest(node, name, ecosystem));
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
