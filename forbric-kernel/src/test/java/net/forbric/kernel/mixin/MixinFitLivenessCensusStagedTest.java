package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.json.JsonFormat;

import net.forbric.api.Ecosystem;

/**
 * Census: every guest mixin of the five compatibility packs, judged as KernelGuestMixinAdapter judges it (MixinFit, then
 * MixinRetarget's plan when that leaves fewer misses) with {@code forbric.mixinFit.liveness} on and off, against the
 * staged merged base and carriers. Pins exactly which mixins change verdict, and says what the final-class ledger will
 * do with each injector that never runs: a Mixin injector with a mandatory count is a CONFIRMED row on the mod
 * ("partly did not run"), a MixinExtras one a SUSPECTED note, one with a zero count nothing.
 *
 * <p>Offline, so it resolves against raw jar bytes rather than the transform chain's (MixinFitReport's divergence) except
 * for the duplicate-lambda prune, the one step that removes methods an injector binds to; each pack's own jars stand in
 * for KernelBoot's guest scan; a multi-loader jar's config is given to the loader the default arbitration picks. The report is written to
 * {@code build/reports/mixin-liveness-census.txt}.
 */
@ResourceLock("system-properties")
class MixinFitLivenessCensusStagedTest {
	private static final String OLD = System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader");
	private static final Path MERGED = Path.of(OLD, "run/merged-base/patched-mc-merged-26.2.jar");
	private static final Path INTEROP = Path.of(OLD, "run/merged-base/forge-runtime-interop.jar");
	private static final Path NEO_RUNTIME = Path.of(OLD, "run/neoforge-runtime/neoforge-runtime.jar");
	private static final Map<String, Path> PACKS = new LinkedHashMap<>();
	static {
		String other = System.getenv("FORBRIC_LIVENESS_PACKS");    // "name=dir;name=dir": census those instead, report only
		if (other != null) {
			for (String pack : other.split(";")) if (pack.contains("=")) PACKS.put(pack.substring(0, pack.indexOf('=')), Path.of(pack.substring(pack.indexOf('=') + 1)));
		} else {
			for (String pack : List.of("sweep90", "mix80", "popular", "random")) PACKS.put(pack, Path.of("build/compat-inputs", pack, "mods"));
			PACKS.put("client-merged-pack", Path.of("run/client-merged-pack/mods"));
		}
	}
	private static final Set<String> STANDARD = Set.of("Inject", "Redirect", "ModifyArg", "ModifyArgs", "ModifyConstant", "ModifyVariable");

	/**
	 * What changes, as {@code config:mixin verdict-off -> verdict-on}, pinned; each was read against the merged bytes. A
	 * PARTIAL that stays PARTIAL gained a "never runs" line. Five methods carry all of the FIT → PARTIAL moves:
	 * {@code Hud.extractHotbarAndDecorations} (NeoForge's HUD layers replaced its one caller), {@code LiquidBlock
	 * .shouldSpreadLiquid} (both carriers' {@code FluidInteractionRegistry.canInteract} replaced it), {@code AxeItem
	 * .getStripped} ({@code getToolModifiedState}), {@code LivingEntity.trapdoorUsableAsLadder} ({@code CommonHooks
	 * .isLivingOnLadder}), and the vanilla-shaped forwarders the merge kept beside NeoForge's overloads, which the merged
	 * callers skip ({@code ItemModelGenerator.bakeSideFaces}, {@code ServerExplosion.hurtEntities}, {@code EffectsInInventory
	 * .extractText}, {@code CropBlock.getGrowthSpeed(Block, …)}); plus {@code Block.tryDropExperience} and
	 * {@code ScreenEffectRenderer.getViewBlockingState}, which the merged callers replaced outright.
	 */
	private static final Set<String> EXPECTED = Set.of(
			"apoli.mixins.json:legacy.hud_power.HudMixin FIT -> PARTIAL",
			"architectury.mixins.json:MixinServerExplosion PARTIAL -> PARTIAL",
			"balm.fabric.mixins.json:FabricCropBlockMixin PARTIAL -> PARTIAL",
			"bclib.mixins.common.json:axe.AxeItemMixin FIT -> PARTIAL",
			"bettermounthud.mixins.json:HudMixin FIT -> PARTIAL",
			"configapi-fabric.mixins.json:event.ServerExplosionMixin PARTIAL -> PARTIAL",
			"fabric-block-api-v1.mixins.json:LiquidBlockMixin FIT -> PARTIAL",
			"fabric-block-api-v1.mixins.json:LivingEntityMixin FIT -> PARTIAL",
			"fabric-content-registries-v0.mixins.json:AxeItemMixin FIT -> PARTIAL",
			"fabric-renderer-api-v1.mixins.json:block.particle.ScreenEffectRendererMixin FIT -> PARTIAL",
			"fabric-renderer-api-v1.mixins.json:block.render.SectionCompilerMixin PARTIAL -> PARTIAL",
			"fabric-rendering-v1.mixins.json:HudMixin PARTIAL -> PARTIAL",
			"puzzleslib.fabric.mixins.json:BlockFabricMixin FIT -> PARTIAL",
			"puzzleslib.fabric.mixins.json:ServerExplosionFabricMixin FIT -> PARTIAL",
			"puzzleslib.fabric.mixins.json:client.EffectsInInventoryFabricMixin PARTIAL -> PARTIAL",
			"sodium-fabric.mixins.json:features.render.model.ItemModelGeneratorMixin FIT -> PARTIAL",
			"wover.traits.mixins.common.json:AxeItemMixin FIT -> PARTIAL");
	/** The same, counted per pack: one mixin changes in every pack that carries it. */
	private static final int EXPECTED_IN_PACKS = 43;

	@AfterEach
	void reset() {
		System.clearProperty(MixinFit.LIVENESS_PROPERTY);
		MixinStubRebind.forget();
		MixinRetarget.reset();
		MergedBaseUncalledMethods.forgetGuests();
	}

	@Test
	void exactlyThePinnedMixinsChangeVerdict() throws Exception {
		for (Path p : List.of(MERGED, INTEROP, NEO_RUNTIME)) assumeTrue(Files.isRegularFile(p), p + " required");
		for (Path p : PACKS.values()) assumeTrue(Files.isDirectory(p), p + " required (symlink it from the main checkout)");
		Map<String, byte[]> game = new HashMap<>();
		for (Path jar : List.of(NEO_RUNTIME, INTEROP, MERGED)) game.putAll(read(Files.readAllBytes(jar), n -> n.endsWith(".class")));
		// The one step of the transform chain that removes methods an injector binds to: the merge's orphaned duplicate
		// lambdas, which InsertedLambdaArgumentShim then follows into the live one (litematica, fusion). Raw bytes would
		// report those injectors as bound in dead code; the kernel's resolver never serves them.
		Map<String, byte[]> pruned = new java.util.concurrent.ConcurrentHashMap<>();
		net.forbric.kernel.transform.DuplicateLambdaPruneInjector prune = new net.forbric.kernel.transform.DuplicateLambdaPruneInjector();
		Function<String, byte[]> resolver = name -> {
			byte[] raw = game.get(name);
			if (raw == null || !name.startsWith("net/minecraft/")) return raw;
			return pruned.computeIfAbsent(name, n -> {
				byte[] out = prune.transform(n.substring(0, n.length() - ".class".length()).replace('/', '.'), raw, null);
				return out == null ? raw : out;
			});
		};

		StringBuilder report = new StringBuilder();
		Set<String> changed = new java.util.TreeSet<>();
		Set<String> inPacks = new java.util.TreeSet<>();
		Set<String> seen = new LinkedHashSet<>();
		int scanned = 0;
		Map<String, Integer> ledger = new TreeMap<>();
		for (Map.Entry<String, Path> pack : PACKS.entrySet()) {
			List<Path> jars;
			try (Stream<Path> s = Files.list(pack.getValue())) {
				jars = s.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
			}
			// What KernelBoot's guest scan records for this pack: every installed jar, nested ones included.
			MergedBaseUncalledMethods.forgetGuests();
			Map<Path, Map<String, Map<String, byte[]>>> read = new LinkedHashMap<>();
			for (Path jar : jars) {
				read.put(jar, units(jar));
				for (Map<String, byte[]> unit : read.get(jar).values()) {
					for (Map.Entry<String, byte[]> e : unit.entrySet()) if (e.getKey().endsWith(".class")) MergedBaseUncalledMethods.noteGuest(e.getValue());
				}
			}
			for (Path jar : jars) {
				for (Map.Entry<String, Map<String, byte[]>> unit : read.get(jar).entrySet()) {
					Map<String, byte[]> content = unit.getValue();
					Map<String, Ecosystem> owners = configOwners(content);
					for (Map.Entry<String, Ecosystem> config : owners.entrySet()) {
						byte[] json = content.get(config.getKey());
						UnmodifiableConfig parsed = parse(json);
						if (parsed == null) continue;
						String pkg = String.valueOf(parsed.<Object>get(List.of("package")));
						int minimum = parsed.get(List.of("injectors", "defaultRequire")) instanceof Number n ? n.intValue() : 0;
						for (String entry : entries(parsed)) {
							String path = pkg.replace('.', '/') + "/" + entry.replace('.', '/') + ".class";
							byte[] bytes = content.get(path);
							if (bytes == null || !seen.add(pack.getKey() + "|" + unit.getKey() + "|" + config.getKey() + ":" + entry)) continue;
							scanned++;
							String internal = path.substring(0, path.length() - ".class".length());
							MixinStubRebind.noteEcosystem(internal, config.getValue());
							Judged off = judge(bytes, resolver, "off");
							Judged on = judge(bytes, resolver, "on");
							if (off.equals(on)) continue;
							String id = config.getKey() + ":" + entry;
							changed.add(id + " " + off.verdict() + " -> " + on.verdict());
							inPacks.add(pack.getKey() + " " + id);
							report.append(String.format("%s  %s  %s [%s]%n  off: %s %s%n  on:  %s %s%n", pack.getKey(), unit.getKey(), id,
									config.getValue(), off.verdict(), off.plan(), on.verdict(), on.plan()));
							for (String line : on.unresolved()) if (!off.unresolved().contains(line)) report.append("    + ").append(line).append('\n');
							for (String line : off.unresolved()) if (!on.unresolved().contains(line)) report.append("    - ").append(line).append('\n');
							for (Map.Entry<String, String> handler : ledger(bytes, resolver, minimum, on.rewritten()).entrySet()) {
								report.append("    ledger: ").append(handler.getKey()).append(" → ").append(handler.getValue()).append('\n');
								ledger.merge(handler.getValue(), 1, Integer::sum);
							}
						}
					}
				}
			}
		}
		report.insert(0, String.format("liveness census: %d guest mixins in %d packs; %d change verdict (%d distinct); ledger %s%n%n",
				scanned, PACKS.size(), inPacks.size(), changed.size(), ledger));
		Files.createDirectories(Path.of("build/reports"));
		Files.writeString(Path.of("build/reports/mixin-liveness-census.txt"), report.toString());
		System.out.println(report);
		if (System.getenv("FORBRIC_LIVENESS_PACKS") != null) return;    // someone else's packs: the report is the answer
		assertEquals(EXPECTED, changed, report.toString());
		assertEquals(EXPECTED_IN_PACKS, inPacks.size(), report.toString());
	}

	private record Judged(MixinFit.Verdict verdict, List<String> unresolved, String plan, byte[] rewritten) {
		@Override public boolean equals(Object o) {
			return o instanceof Judged j && j.verdict == verdict && j.unresolved.equals(unresolved) && j.plan.equals(plan);
		}

		@Override public int hashCode() {
			return verdict.hashCode();
		}
	}

	/** As KernelGuestMixinAdapter: the verdict, and the retarget's when it leaves fewer misses. */
	private static Judged judge(byte[] bytes, Function<String, byte[]> resolver, String liveness) {
		System.setProperty(MixinFit.LIVENESS_PROPERTY, liveness);
		MixinFit.Result fit = MixinFit.evaluate(bytes, resolver, net.forbric.kernel.classloading.DelegationPolicy::alwaysGame);
		if (fit.verdict() == MixinFit.Verdict.PARTIAL) {
			MixinRetarget.Plan plan = MixinRetarget.plan(MixinFit.parse(bytes), resolver);
			if (!plan.isEmpty()) {
				byte[] rewritten = MixinRetarget.rewritten(bytes, plan);
				MixinFit.Result after = MixinFit.evaluate(rewritten, resolver, net.forbric.kernel.classloading.DelegationPolicy::alwaysGame);
				if (after.unresolved().size() < fit.unresolved().size()) {
					return new Judged(after.verdict(), after.unresolved(), "retargeted: " + plan.describe(), rewritten);
				}
			}
		}
		return new Judged(fit.verdict(), fit.unresolved(), "", bytes);
	}

	/**
	 * Each injector that never runs, and what FinalMixinApplications records for it: every handler judged alone, as the
	 * mixin reached Mixin.
	 */
	private static Map<String, String> ledger(byte[] original, Function<String, byte[]> resolver, int configMinimum, byte[] bytes) {
		System.setProperty(MixinFit.LIVENESS_PROPERTY, "on");
		Map<String, String> out = new LinkedHashMap<>();
		ClassNode mixin = MixinFit.parse(bytes);
		for (MethodNode handler : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(handler);
			if (injector == null) continue;
			ClassNode alone = MixinFit.parse(bytes);
			alone.methods.removeIf(m -> MixinFit.injectorOf(m) != null && !(m.name.equals(handler.name) && m.desc.equals(handler.desc)));
			ClassWriter writer = new ClassWriter(0);
			alone.accept(writer);
			MixinFit.Result fit = MixinFit.evaluate(writer.toByteArray(), resolver);
			if (fit.unresolved().stream().noneMatch(line -> line.contains(" never runs: "))) continue;
			boolean grouped = annotations(handler).stream().anyMatch(a -> a.desc.endsWith("/Group;"));
			boolean sugar = sugar(handler);
			Object require = MixinFit.value(injector, "require");
			int minimum = require instanceof Number n && n.intValue() >= 0 ? n.intValue() : grouped ? 0 : configMinimum;
			String kind = injector.desc.substring(injector.desc.lastIndexOf('/') + 1, injector.desc.length() - 1);
			boolean audited = injector.desc.startsWith("Lorg/spongepowered/") && STANDARD.contains(kind);
			String outcome = minimum == 0 ? "OPTIONAL (no row)"
					: audited && !grouped && !sugar ? "CONFIRMED, not required (mod row: partly did not run)" : "SUSPECTED (note)";
			out.put("@" + kind + " " + handler.name, outcome);
		}
		return out;
	}

	private static List<AnnotationNode> annotations(MethodNode m) {
		List<AnnotationNode> out = new ArrayList<>();
		if (m.visibleAnnotations != null) out.addAll(m.visibleAnnotations);
		if (m.invisibleAnnotations != null) out.addAll(m.invisibleAnnotations);
		return out;
	}

	private static boolean sugar(MethodNode m) {
		for (List<AnnotationNode>[] set : java.util.Arrays.asList(m.visibleParameterAnnotations, m.invisibleParameterAnnotations)) {
			if (set == null) continue;
			for (List<AnnotationNode> p : set) if (p != null) for (AnnotationNode a : p) if (a.desc.startsWith("Lcom/llamalad7/mixinextras/")) return true;
		}
		return false;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Packs

	/** A jar and every jar nested in it (Fabric {@code META-INF/jars}, Forge {@code META-INF/jarjar}), each read whole. */
	private static Map<String, Map<String, byte[]>> units(Path jar) throws IOException {
		Map<String, Map<String, byte[]>> units = new LinkedHashMap<>();
		Map<String, byte[]> top = read(Files.readAllBytes(jar), MixinFitLivenessCensusStagedTest::wanted);
		units.put(jar.getFileName().toString(), top);
		for (Map.Entry<String, byte[]> e : top.entrySet()) {
			String name = e.getKey();
			if (name.endsWith(".jar") && (name.startsWith("META-INF/jars/") || name.startsWith("META-INF/jarjar/"))) {
				units.put(jar.getFileName() + "!" + name.substring(name.lastIndexOf('/') + 1), read(e.getValue(), MixinFitLivenessCensusStagedTest::wanted));
			}
		}
		return units;
	}

	private static boolean wanted(String name) {
		return name.endsWith(".class") || name.endsWith(".json") || name.endsWith(".jar") || name.endsWith(".toml") || name.endsWith("MANIFEST.MF");
	}

	private static Map<String, byte[]> read(byte[] zip, java.util.function.Predicate<String> wanted) throws IOException {
		Map<String, byte[]> out = new LinkedHashMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
			for (ZipEntry e; (e = in.getNextEntry()) != null; ) if (!e.isDirectory() && wanted.test(e.getName())) out.put(e.getName(), in.readAllBytes());
		}
		return out;
	}

	private static final Pattern TOML_CONFIG = Pattern.compile("(?m)^\\s*config\\s*=\\s*\"([^\"]+)\"");

	/**
	 * Each top-level mixin config of a unit and the ecosystem whose manifest declares it; a config two manifests declare
	 * goes to the one MultiLoaderArbiter prefers by default (NeoForge, MinecraftForge, Fabric), one no manifest names to
	 * the unit's only ecosystem.
	 */
	private static Map<String, Ecosystem> configOwners(Map<String, byte[]> content) {
		Map<String, Set<Ecosystem>> declared = new LinkedHashMap<>();
		Set<Ecosystem> unit = new LinkedHashSet<>();
		byte[] fabric = content.get("fabric.mod.json");
		if (fabric != null) {
			unit.add(Ecosystem.FABRIC);
			UnmodifiableConfig json = parse(fabric);
			if (json != null && json.get(List.of("mixins")) instanceof List<?> list) {
				for (Object o : list) {
					String name = o instanceof String s ? s : o instanceof UnmodifiableConfig c ? c.<String>get(List.of("config")) : null;
					if (name != null) declared.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(Ecosystem.FABRIC);
				}
			}
		}
		for (Map.Entry<String, Ecosystem> toml : Map.of("META-INF/mods.toml", Ecosystem.FORGE, "META-INF/neoforge.mods.toml", Ecosystem.NEOFORGE).entrySet()) {
			byte[] bytes = content.get(toml.getKey());
			if (bytes == null) continue;
			unit.add(toml.getValue());
			Matcher m = TOML_CONFIG.matcher(new String(bytes, StandardCharsets.UTF_8));
			while (m.find()) declared.computeIfAbsent(m.group(1), k -> new LinkedHashSet<>()).add(toml.getValue());
		}
		byte[] manifest = content.get("META-INF/MANIFEST.MF");
		if (manifest != null) {
			for (String line : new String(manifest, StandardCharsets.UTF_8).replace("\r\n ", "").split("\r?\n")) {
				if (!line.startsWith("MixinConfigs:")) continue;
				for (String name : line.substring("MixinConfigs:".length()).split(",")) {
					if (!name.isBlank()) declared.computeIfAbsent(name.trim(), k -> new LinkedHashSet<>()).add(unit.contains(Ecosystem.NEOFORGE) ? Ecosystem.NEOFORGE : Ecosystem.FORGE);
				}
			}
		}
		Map<String, Ecosystem> out = new TreeMap<>();
		Set<String> candidates = new LinkedHashSet<>(declared.keySet());
		for (String name : content.keySet()) if (name.indexOf('/') < 0 && name.endsWith(".json") && name.contains("mixin")) candidates.add(name);
		for (String name : candidates) {
			if (!content.containsKey(name)) continue;
			Set<Ecosystem> by = declared.getOrDefault(name, unit.size() == 1 ? unit : Set.of());
			for (Ecosystem preferred : List.of(Ecosystem.NEOFORGE, Ecosystem.FORGE, Ecosystem.FABRIC)) {
				if (by.contains(preferred) && (by.size() == 1 || unit.contains(preferred))) { out.put(name, preferred); break; }
			}
		}
		return out;
	}

	private static List<String> entries(UnmodifiableConfig config) {
		List<String> out = new ArrayList<>();
		for (String key : List.of("mixins", "client", "server")) {
			if (config.get(List.of(key)) instanceof List<?> list) for (Object o : list) if (o instanceof String s && !s.isBlank()) out.add(s.trim());
		}
		return out;
	}

	private static UnmodifiableConfig parse(byte[] json) {
		try (Reader reader = new InputStreamReader(new ByteArrayInputStream(json), StandardCharsets.UTF_8)) {
			return JsonFormat.fancyInstance().createParser().parse(reader);
		} catch (RuntimeException | IOException notJson) {
			return null;
		}
	}
}
