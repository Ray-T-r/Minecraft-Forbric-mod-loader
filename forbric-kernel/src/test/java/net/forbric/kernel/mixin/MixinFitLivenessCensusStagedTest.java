package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

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
 *
 * <p>And the same judgment set against stock 26.2 ({@link #vanillaAnchorsTheMergedBaseLostArePinned}): every anchor a
 * Fabric mixin names that resolves on vanilla and does not on the merged base after the kernel's transforms and anchor
 * movers — a call whose owner the merge's field widening changed, a call a carrier widened or replaced, a method it
 * reshaped (a name that now binds an overload the handler was not written for), a field it re-typed. That is the class
 * debugify's, CreativeCore's and MoogsStructureLib's mixins fell into (each reached Done on native Fabric and stopped on
 * Forbric). Only fabric-api, which every staged machine has, is ASSERTED: a new fabric-api line fails the build. Every
 * other corpus — the pure-Fabric A/B set, the compat packs — is report-only ({@code FORBRIC_ANCHOR_PACKS}, written to
 * {@code build/reports/vanilla-anchor-census.txt}): nothing fails on it, and someone has to read it.
 */
@ResourceLock("system-properties")
class MixinFitLivenessCensusStagedTest {
	private static final Path MERGED = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path INTEROP = TestFixtures.stagedRoot().resolve("merged-base/forge-runtime-interop.jar");
	private static final Path NEO_RUNTIME = TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar");
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
	 * {@code ScreenEffectRenderer.getViewBlockingState}, which the merged callers replaced outright. And NeoForge's
	 * renamed tooltip body {@code ItemStack.addDetailsToTooltipComponents}, which nothing calls: R3 moves malilib's last
	 * tooltip hook and trinkets' attribute-line hook there (carrier-renames.txt marks it UNCALLED), where they bind and
	 * never run.
	 */
	private static final Set<String> EXPECTED = Set.of(
			"apoli.mixins.json:legacy.hud_power.HudMixin FIT -> PARTIAL",
			"architectury.mixins.json:MixinServerExplosion PARTIAL -> PARTIAL",
			"balm.fabric.mixins.json:FabricCropBlockMixin PARTIAL -> PARTIAL",
			"bettermounthud.mixins.json:HudMixin FIT -> FIT",
			"configapi-fabric.mixins.json:event.ServerExplosionMixin PARTIAL -> PARTIAL",
			"fabric-block-api-v1.mixins.json:LivingEntityMixin FIT -> PARTIAL",
			"fabric-renderer-api-v1.mixins.json:block.particle.ScreenEffectRendererMixin FIT -> PARTIAL",
			"fabric-rendering-v1.mixins.json:HudMixin PARTIAL -> PARTIAL",
			"mixins.malilib.json:item.MixinItemStack FIT -> PARTIAL",
			"puzzleslib.fabric.mixins.json:BlockFabricMixin FIT -> PARTIAL",
			"puzzleslib.fabric.mixins.json:ServerExplosionFabricMixin FIT -> PARTIAL",
			"puzzleslib.fabric.mixins.json:client.EffectsInInventoryFabricMixin PARTIAL -> PARTIAL",
			"sodium-fabric.mixins.json:features.render.model.ItemModelGeneratorMixin FIT -> PARTIAL",
			"trinkets.fabric.mixins.json:ItemStackMixin FIT -> PARTIAL");
	/** The same, counted per pack: one mixin changes in every pack that carries it. */
	private static final int EXPECTED_IN_PACKS = 30;

	@AfterEach
	void reset() {
		System.clearProperty(MixinFit.LIVENESS_PROPERTY);
		MixinStubRebind.forget();
		MixinRetarget.reset();
		MergedBaseUncalledMethods.forgetGuests();
	}

	@Test
	void exactlyThePinnedMixinsChangeVerdict() throws Exception {
		for (Path p : List.of(MERGED, INTEROP, NEO_RUNTIME)) TestFixtures.require(Fixture.STAGED, Files.isRegularFile(p), p + " required");
		for (Path p : PACKS.values()) TestFixtures.require(Fixture.THIRD_PARTY, Files.isDirectory(p), p + " required (symlink it from the main checkout)");
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

	/**
	 * As KernelGuestMixinAdapter: the verdict, and the retarget's when the adapter takes it (MixinRetarget.adopt) — fewer
	 * misses, or fewer outright where one now binds in a method nothing runs.
	 */
	private static Judged judge(byte[] bytes, Function<String, byte[]> resolver, String liveness) {
		System.setProperty(MixinFit.LIVENESS_PROPERTY, liveness);
		MixinFit.Result fit = MixinFit.evaluate(bytes, resolver, net.forbric.kernel.classloading.DelegationPolicy::alwaysGame);
		MixinRetarget.Adoption adoption = MixinRetarget.adopt(bytes, fit, resolver,
				b -> MixinFit.evaluate(b, resolver, net.forbric.kernel.classloading.DelegationPolicy::alwaysGame));
		if (adoption != null) {
			return new Judged(adoption.after().verdict(), adoption.after().unresolved(), "retargeted: " + adoption.plan().describe(),
					adoption.rewritten());
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
	static Map<String, Map<String, byte[]>> units(Path jar) throws IOException {
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

	static Map<String, byte[]> read(byte[] zip, java.util.function.Predicate<String> wanted) throws IOException {
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
	static Map<String, Ecosystem> configOwners(Map<String, byte[]> content) {
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

	static List<String> entries(UnmodifiableConfig config) {
		List<String> out = new ArrayList<>();
		for (String key : List.of("mixins", "client", "server")) {
			if (config.get(List.of(key)) instanceof List<?> list) for (Object o : list) if (o instanceof String s && !s.isBlank()) out.add(s.trim());
		}
		return out;
	}

	static UnmodifiableConfig parse(byte[] json) {
		try (Reader reader = new InputStreamReader(new ByteArrayInputStream(json), StandardCharsets.UTF_8)) {
			return JsonFormat.fancyInstance().createParser().parse(reader);
		} catch (RuntimeException | IOException notJson) {
			return null;
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Vanilla anchors the merged base lost

	/** {@code "name=dir;name=dir"}: census these corpora too — the pure-Fabric A/B set, the compat packs — report only. */
	private static final String ANCHOR_PACKS = "FORBRIC_ANCHOR_PACKS";

	/**
	 * fabric-api's anchors that resolve on stock 26.2 and not on the merged base as the kernel's verdict judges it, as
	 * {@code config:mixin | anchor} — 66 of them. Each is a loss the kernel replaces elsewhere (its networking codec and
	 * configuration wraps are PayloadInterop's, its HUD layers KernelHudBridge's, its fuel, hopper, use-on and sound hooks
	 * bridges or adapters of their own that the verdict does not ask, its item tooltip hooks the Fabric tooltip bridge's
	 * over NeoForge's appenders) or reports as the mixin's PARTIAL line. Four of item-api's tooltip anchors read as bound
	 * only while R3 moved them, on the bytes alone, into NeoForge's addDetailsToTooltipComponents — a renamed tooltip body
	 * nothing calls; the carrier-rename census keeps them out of it, so they are lost, honestly, where they never ran. Two are
	 * names that bind a lambda the handler was not written for, which Mixin rejects: loot-api's
	 * {@code ReloadableServerRegistriesMixin} (suppressed by name; KernelLootBridge serves its callbacks) and
	 * resource-conditions' {@code SimpleJsonResourceReloadListenerMixin} (SupersededMixins; KernelFabricConditions judges
	 * the conditions at ConditionalOps' funnel). Both read FIT until the verdict asked whether the handler fits. Global widened-call contracts now retain networking wrappers and explicitly scoped sugar captures in tag generation.
	 * A third-party mod's lost anchor shows only in the report of a corpus someone names.
	 * A new line fails this, and so does one that stops being lost: delete it then.
	 */
	static final Set<String> FABRIC_API_LOST = Set.of(
			"fabric-content-registries-v0.mixins.json:FuelValuesMixin | @At(INVOKE) net.minecraft.world.level.block.entity.FuelValues$Builder.remove in FuelValues.vanillaBurnTimes",
			"fabric-content-registries-v0.mixins.json:fluid.AbstractBoatMixin | @At(INVOKE) net.minecraft.world.level.material.FluidState.is in AbstractBoat.checkInWater",
			"fabric-content-registries-v0.mixins.json:fluid.EntityMixin | @At(INVOKE) Entity.isUnderWater in updateSwimming",
			"fabric-content-registries-v0.mixins.json:fluid.EntityMixin | @At(INVOKE) net.minecraft.world.level.material.FluidState.is in Entity.updateSwimming",
			"fabric-content-registries-v0.mixins.json:fluid.LivingEntityMixin | @At(INVOKE) LivingEntity.isEyeInFluid in baseTick",
			"fabric-content-registries-v0.mixins.json:fluid.LivingEntityMixin | @At(INVOKE) LivingEntity.travelInLava in travelInFluid",
			"fabric-crash-report-info-v1.mixins.json:ServerWatchdogMixin | @At(INVOKE) java.lang.StringBuilder.append in ServerWatchdog.createWatchdogCrashReport",
			"fabric-data-generation-api-v1.client.mixins.json:ModelProviderMixin | @At(INVOKE) net.minecraft.client.data.models.BlockModelGenerators.run in ModelProvider.run",
			"fabric-data-generation-api-v1.client.mixins.json:ModelProviderMixin | @At(INVOKE) net.minecraft.client.data.models.ItemModelGenerators.run in ModelProvider.run",
			"fabric-entity-events-v1.mixins.json:LivingEntityMixin | @At(INVOKE) net.minecraft.world.level.Level.setBlock in LivingEntity.lambda$stopSleeping$0",
			"fabric-entity-events-v1.mixins.json:LivingEntityMixin | @At(INVOKE) net.minecraft.world.level.block.BedBlock.getBedOrientation in LivingEntity.getBedOrientation",
			"fabric-entity-events-v1.mixins.json:effect.LivingEntityMixin | @At(INVOKE) LivingEntity.canBeAffected in forceAddEffect",
			"fabric-entity-events-v1.mixins.json:effect.LivingEntityMixin | @At(INVOKE) com.google.common.collect.Maps.newHashMap in LivingEntity.removeAllEffects",
			"fabric-entity-events-v1.mixins.json:effect.LivingEntityMixin | @At(INVOKE) java.util.Map.clear in LivingEntity.removeAllEffects",
			"fabric-events-interaction-v0.mixins.json:ItemStackMixin | @At(INVOKE) net.minecraft.world.item.Item.useOn in ItemStack.useOn",
			"fabric-events-interaction-v0.mixins.json:ServerPlayerGameModeMixin | @At(INVOKE) net.minecraft.world.level.block.Block.destroy in ServerPlayerGameMode.destroyBlock",
			"fabric-item-api-v1.client.mixins.json:MultiPlayerGameModeMixin | @At(INVOKE) net.minecraft.world.item.ItemStack.isSameItemSameComponents in MultiPlayerGameMode.sameDestroyTarget",
			"fabric-item-api-v1.mixins.json:AbstractFurnaceBlockEntityMixin | @At(INVOKE) net.minecraft.world.item.Item.getCraftingRemainder in AbstractFurnaceBlockEntity.consumeFuel",
			"fabric-item-api-v1.mixins.json:AnvilMenuMixin | @At(INVOKE) net.minecraft.world.item.enchantment.Enchantment.canEnchant in AnvilMenu.createResult",
			"fabric-item-api-v1.mixins.json:BrewingStandBlockEntityMixin | @At(INVOKE) net.minecraft.world.item.Item.getCraftingRemainder in BrewingStandBlockEntity.doBrew",
			"fabric-item-api-v1.mixins.json:CraftingRecipeMixin | @At(INVOKE) net.minecraft.world.item.Item.getCraftingRemainder in CraftingRecipe.defaultCraftingReminder",
			"fabric-item-api-v1.mixins.json:CraftingRecipeMixin | @At(INVOKE) net.minecraft.world.item.ItemStack.getItem in CraftingRecipe.defaultCraftingReminder",
			"fabric-item-api-v1.mixins.json:EnchantCommandMixin | @At(INVOKE) net.minecraft.world.item.enchantment.Enchantment.canEnchant in EnchantCommand.enchant",
			"fabric-item-api-v1.mixins.json:EnchantRandomlyFunctionMixin | @At(INVOKE) net.minecraft.world.item.enchantment.Enchantment.canEnchant in EnchantRandomlyFunction.lambda$run$1",
			"fabric-item-api-v1.mixins.json:EnchantmentHelperMixin | @At(INVOKE) net.minecraft.world.item.enchantment.Enchantment.isPrimaryItem in EnchantmentHelper.lambda$getAvailableEnchantmentResults$0",
			"fabric-item-api-v1.mixins.json:ItemStackMixin | @At(INVOKE) ItemStack.addAttributeTooltips in addDetailsToTooltip",
			"fabric-item-api-v1.mixins.json:ItemStackMixin | @At(INVOKE) ItemStack.addToTooltip in addDetailsToTooltip",
			"fabric-item-api-v1.mixins.json:ItemStackMixin | @At(INVOKE) net.minecraft.core.DefaultedRegistry.getKey in ItemStack.addDetailsToTooltip",
			"fabric-item-api-v1.mixins.json:ItemStackMixin | @At(INVOKE) net.minecraft.world.item.TooltipFlag.isAdvanced in ItemStack.addDetailsToTooltip",
			"fabric-item-api-v1.mixins.json:ItemStackMixin | @At(INVOKE) net.minecraft.world.item.component.TooltipDisplay.shows in ItemStack.addDetailsToTooltip",
			"fabric-loot-api-v3.mixins.json:ReloadableServerRegistriesMixin | @Inject target ReloadableServerRegistries.lambda$scheduleRegistryLoad$0 binds lambda$scheduleRegistryLoad$0(Lnet/minecraft/world/level/storage/loot/LootDataType;Lnet/minecraft/resources/RegistryOps;Lnet/minecraft/server/packs/resources/ResourceManager;)Lnet/minecraft/core/WritableRegistry;, which the handler was not written for",
			"fabric-model-loading-api-v1.mixins.json:ModelManagerMixin | @At(INVOKE) net.minecraft.client.resources.model.cuboid.CuboidModel.fromStream in ModelManager.lambda$loadBlockModels$2",
			"fabric-networking-api-v1.mixins.json:ClientboundCustomPayloadPacketMixin | @At(INVOKE) net.minecraft.network.protocol.common.custom.CustomPacketPayload.codec in ClientboundCustomPayloadPacket.<clinit>",
			"fabric-object-builder-v1.client.mixins.json:HangingSignEditScreenMixin | @At(INVOKE) net.minecraft.resources.Identifier.withDefaultNamespace in HangingSignEditScreen.<init>",
			"fabric-object-builder-v1.client.mixins.json:SignEditScreenMixin | @At(INVOKE) net.minecraft.resources.Identifier.withDefaultNamespace in SignEditScreen.<init>",
			"fabric-registry-sync-v0.mixins.json:RegistryDataLoaderMixin | @At(INVOKE) RegistryDataLoader.load in load",
			"fabric-registry-sync-v0.mixins.json:RegistryPatchGeneratorMixin | @At(FIELD) net.minecraft.resources.RegistryDataLoader.WORLDGEN_REGISTRIES in RegistryPatchGenerator.lambda$createLookup$0",
			"fabric-renderer-api-v1.mixins.json:block.model.SimpleModelWrapperMixin | @At(INVOKE) SimpleModelWrapper.findNonBlockSprites in bake",
			"fabric-renderer-api-v1.mixins.json:block.render.LevelExtractorMixin | @At(INVOKE) net.minecraft.client.renderer.block.dispatch.BlockStateModel.hasMaterialFlag in LevelExtractor.extractBlockOutline",
			"fabric-renderer-api-v1.mixins.json:block.render.LevelRendererMixin | @At(INVOKE) net.minecraft.client.renderer.block.dispatch.BlockStateModel.collectParts in LevelRenderer.submitBlockDestroyAnimation",
			"fabric-renderer-api-v1.mixins.json:block.render.SectionCompilerMixin | @At(INVOKE) net.minecraft.client.renderer.block.ModelBlockRenderer.tesselateBlock in SectionCompiler.compile",
			"fabric-renderer-api-v1.mixins.json:block.render.SectionCompilerMixin | @At(INVOKE) net.minecraft.core.BlockPos.betweenClosed in SectionCompiler.compile",
			"fabric-renderer-api-v1.mixins.json:submit.SubmitNodeCollectionMixin | @At(INVOKE) net.minecraft.client.renderer.block.dispatch.BlockStateModel.hasMaterialFlag in SubmitNodeCollection.submitMovingBlock",
			"fabric-rendering-v1.mixins.json:DebugOptionsScreenOptionListMixin | @At(INVOKE) java.lang.String.contains in DebugOptionsScreen$OptionList.updateSearch",
			"fabric-rendering-v1.mixins.json:HudMixin | @At(INVOKE) Hud.extractBossOverlay in extractRenderState",
			"fabric-rendering-v1.mixins.json:HudMixin | @At(INVOKE) Hud.extractCameraOverlays in extractRenderState",
			"fabric-rendering-v1.mixins.json:HudMixin | @At(INVOKE) Hud.extractChat in extractRenderState",
			"fabric-rendering-v1.mixins.json:HudMixin | @At(INVOKE) Hud.extractCrosshair in extractRenderState",
			"fabric-rendering-v1.mixins.json:HudMixin | @At(INVOKE) Hud.extractDemoOverlay in extractRenderState",
			"fabric-rendering-v1.mixins.json:HudMixin | @At(INVOKE) Hud.extractEffects in extractRenderState",
			"fabric-rendering-v1.mixins.json:HudMixin | @At(INVOKE) Hud.extractOverlayMessage in extractRenderState",
			"fabric-rendering-v1.mixins.json:HudMixin | @At(INVOKE) Hud.extractScoreboardSidebar in extractRenderState",
			"fabric-rendering-v1.mixins.json:HudMixin | @At(INVOKE) Hud.extractSleepOverlay in extractRenderState",
			"fabric-rendering-v1.mixins.json:HudMixin | @At(INVOKE) Hud.extractTabList in extractRenderState",
			"fabric-rendering-v1.mixins.json:HudMixin | @At(INVOKE) Hud.extractTitle in extractRenderState",
			"fabric-rendering-v1.mixins.json:RenderPipelineBuilderMixin | @At(NEW) RenderPipeline$Builder.RenderPipeline$Snippet: handler wraps a 11-arg constructor, the call site constructs with 12 in buildSnippet",
			"fabric-resource-conditions-api-v1.mixins.json:RegistryLoadTaskPendingRegistrationMixin | @At(INVOKE) com.mojang.serialization.Decoder.parse in RegistryLoadTask$PendingRegistration.loadFromResource",
			"fabric-resource-conditions-api-v1.mixins.json:SimpleJsonResourceReloadListenerMixin | @Inject target SimpleJsonResourceReloadListener.lambda$scanDirectory$0 binds lambda$scanDirectory$0(Lnet/minecraft/resources/Identifier;Lnet/minecraft/resources/Identifier;Ljava/util/Map;Ljava/util/Optional;)V, which the handler was not written for",
			"fabric-resource-loader-v1.mixins.json:server.LanguageMixin | @At(INVOKE) java.util.Map.copyOf in Language.loadDefault",
			"fabric-screen-api-v1.mixins.json:GuiMixin | @At(INVOKE) net.minecraft.client.gui.screens.Screen.extractRenderStateWithTooltipAndSubtitles in Gui.extractRenderState",
			"fabric-sound-api-v1.mixins.json:SoundEngineMixin | @At(INVOKE) net.minecraft.client.sounds.SoundBufferLibrary.getStream in SoundEngine.play",
			"fabric-transfer-api-v1.mixins.json:HopperBlockEntityMixin | @At(INVOKE_ASSIGN) HopperBlockEntity.getAttachedContainer in ejectItems",
			"fabric-transfer-api-v1.mixins.json:HopperBlockEntityMixin | @At(INVOKE_ASSIGN) HopperBlockEntity.getSourceContainer in suckInItems");

	@Test
	void vanillaAnchorsTheMergedBaseLostArePinned() throws Exception {
		Path vanilla = TestFixtures.vanillaJar();
		for (Path p : List.of(MERGED, INTEROP, NEO_RUNTIME)) TestFixtures.require(Fixture.STAGED, Files.isRegularFile(p), p + " required");
		TestFixtures.require(Fixture.MC_LIBRARIES, Files.isRegularFile(vanilla), vanilla + " required");
		Path fabricApi = TestFixtures.fabricApi();
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(fabricApi), fabricApi + " required");
		Map<String, List<Path>> corpora = new LinkedHashMap<>();
		corpora.put("fabric-api", List.of(fabricApi));
		String other = System.getenv(ANCHOR_PACKS);
		if (other != null) {
			for (String pack : other.split(";")) {
				if (!pack.contains("=")) continue;
				try (Stream<Path> s = Files.list(Path.of(pack.substring(pack.indexOf('=') + 1)))) {
					corpora.put(pack.substring(0, pack.indexOf('=')), s.filter(p -> p.toString().endsWith(".jar")).sorted().toList());
				}
			}
		}
		Function<String, byte[]> before = vanillaResolver(vanilla), after = mergedResolver();

		StringBuilder report = new StringBuilder();
		Map<String, Set<String>> lost = new LinkedHashMap<>();
		for (Map.Entry<String, List<Path>> corpus : corpora.entrySet()) {
			Set<String> lines = new java.util.TreeSet<>();
			Set<String> seen = new java.util.HashSet<>();
			for (Path jar : corpus.getValue()) {
				for (Map.Entry<String, Map<String, byte[]>> unit : units(jar).entrySet()) {
					Map<String, byte[]> content = unit.getValue();
					for (Map.Entry<String, Ecosystem> config : configOwners(content).entrySet()) {
						if (config.getValue() != Ecosystem.FABRIC) continue;
						UnmodifiableConfig parsed = parse(content.get(config.getKey()));
						if (parsed == null) continue;
						String pkg = String.valueOf(parsed.<Object>get(List.of("package")));
						for (String entry : entries(parsed)) {
							String path = pkg.replace('.', '/') + "/" + entry.replace('.', '/') + ".class";
							byte[] bytes = content.get(path);
							if (bytes == null || !seen.add(config.getKey() + ":" + entry)) continue;
							MixinStubRebind.noteEcosystem(path.substring(0, path.length() - ".class".length()), Ecosystem.FABRIC);
							for (String anchor : lostAnchors(bytes, before, after)) {
								lines.add(config.getKey() + ":" + entry + " | " + anchor);
								report.append(corpus.getKey()).append("  ").append(unit.getKey()).append("  ").append(config.getKey())
										.append(':').append(entry).append("  ").append(anchor).append('\n');
							}
						}
					}
				}
			}
			lost.put(corpus.getKey(), lines);
		}
		StringBuilder summary = new StringBuilder("vanilla anchor census: anchors that resolve on stock 26.2 and not on the merged base\n");
		for (var corpus : lost.entrySet()) summary.append("  ").append(corpus.getKey()).append(": ").append(corpus.getValue().size()).append('\n');
		report.insert(0, summary.append('\n'));
		Files.createDirectories(Path.of("build/reports"));
		Files.writeString(Path.of("build/reports/vanilla-anchor-census.txt"), report.toString());
		System.out.println(report);
		assertEquals(FABRIC_API_LOST, lost.get("fabric-api"), report.toString());
	}

	/**
	 * The census can fail: debugify's MC-121706 shape, the anchor lost to the field the merge widened, is a census line
	 * exactly when MixinSubtypeOwnerRetarget's widened-field rule is off.
	 */
	@Test
	void theAnchorCensusSeesALostAnchorAndItsRepair() throws Exception {
		Path vanilla = TestFixtures.vanillaJar();
		for (Path p : List.of(MERGED, INTEROP, NEO_RUNTIME)) TestFixtures.require(Fixture.STAGED, Files.isRegularFile(p), p + " required");
		TestFixtures.require(Fixture.MC_LIBRARIES, Files.isRegularFile(vanilla), vanilla + " required");
		Function<String, byte[]> before = vanillaResolver(vanilla), after = mergedResolver();
		byte[] debugify = debugifyShaped();
		assertEquals(List.of(), lostAnchors(debugify, before, after), "the widened field's owner change is repaired");
		System.setProperty(MixinSubtypeOwnerRetarget.RETYPED_FIELD_PROPERTY, "off");
		try {
			assertEquals(List.of("@At(INVOKE) net.minecraft.world.entity.monster.Monster.lookAt in RangedBowAttackGoal.tick"),
					lostAnchors(debugify, before, after));
		} finally {
			System.clearProperty(MixinSubtypeOwnerRetarget.RETYPED_FIELD_PROPERTY);
		}
	}

	/**
	 * The census sees a name the carrier took over without asking the REPLACED rows, which only move it:
	 * MoogsStructureLib's HEAD of {@code placeEntities}, alone in its mixin. Of no known ecosystem (a config two mods
	 * claim: no row applies) it is a line; a Fabric mod's is moved by R7 and is none; with R7 off it is a line again, the
	 * row naming the replacement. With the handler-fit rule off the name binds and nothing is seen — the hole this closed.
	 */
	@Test
	void theAnchorCensusSeesANameTheCarrierTookOver() throws Exception {
		Path vanilla = TestFixtures.vanillaJar();
		for (Path p : List.of(MERGED, INTEROP, NEO_RUNTIME)) TestFixtures.require(Fixture.STAGED, Files.isRegularFile(p), p + " required");
		TestFixtures.require(Fixture.MC_LIBRARIES, Files.isRegularFile(vanilla), vanilla + " required");
		Function<String, byte[]> before = vanillaResolver(vanilla), after = mergedResolver();
		byte[] head = moogsHeadShaped();
		MergedBaseCalleeSwaps.Replaced row = MergedBaseCalleeSwaps.REPLACED.getFirst();
		String template = row.owner().substring(row.owner().lastIndexOf('/') + 1);
		String bound = MixinFit.parse(after.apply(row.owner() + ".class")).methods.stream()
				.filter(m -> m.name.equals("placeEntities")).findFirst().orElseThrow().desc;
		String binds = "binds placeEntities" + bound + ", which the handler was not written for";

		assertEquals(List.of("@Inject target " + template + ".placeEntities " + binds), lostAnchors(head, before, after));
		MixinStubRebind.noteEcosystem(MOOGS_HEAD, Ecosystem.FABRIC);
		assertEquals(List.of(), lostAnchors(head, before, after), "R7 moves a Fabric mod's");
		try {
			System.setProperty(MixinRetarget.REPLACED_CALL_PROPERTY, "off");
			assertEquals(List.of("@Inject target " + template + "." + row.vanilla() + " is gone: the carrier replaced it with "
					+ "addEntitiesToWorld, and the name " + binds), lostAnchors(head, before, after));
			System.setProperty(MixinFit.HANDLER_FIT_PROPERTY, "off");
			assertEquals(List.of(), lostAnchors(head, before, after), "RED control: the name binds, and that was all it asked");
		} finally {
			System.clearProperty(MixinRetarget.REPLACED_CALL_PROPERTY);
			System.clearProperty(MixinFit.HANDLER_FIT_PROPERTY);
		}
	}

	private static final String MOOGS_HEAD = "test/census/EntityProcessorMixin";

	/** MoogsStructureLib's EntityProcessorMixin with only its HEAD injector, as compiled: placeEntities by name. */
	private static byte[] moogsHeadShaped() {
		MergedBaseCalleeSwaps.Replaced row = MergedBaseCalleeSwaps.REPLACED.getFirst();
		ClassNode mixin = new ClassNode();
		mixin.version = org.objectweb.asm.Opcodes.V21;
		mixin.access = org.objectweb.asm.Opcodes.ACC_PUBLIC;
		mixin.name = MOOGS_HEAD;
		mixin.superName = "java/lang/Object";
		AnnotationNode type = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(org.objectweb.asm.Type.getObjectType(row.owner())))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(type));
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "HEAD"));
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of("placeEntities")), "at",
				new ArrayList<>(List.of(at)), "cancellable", true));
		String vanilla = row.vanilla().substring(row.vanilla().indexOf('('));
		org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(vanilla);
		org.objectweb.asm.Type[] params = java.util.Arrays.copyOf(args, args.length + 1);
		params[args.length] = org.objectweb.asm.Type.getType("Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;");
		MethodNode handler = new MethodNode(org.objectweb.asm.Opcodes.ACC_PRIVATE, "processAndPlaceEntities",
				org.objectweb.asm.Type.getMethodDescriptor(org.objectweb.asm.Type.VOID_TYPE, params), null, null);
		handler.visibleAnnotations = new ArrayList<>(List.of(inject));
		handler.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.RETURN));
		handler.maxLocals = org.objectweb.asm.Type.getArgumentsAndReturnSizes(handler.desc) >> 2;
		mixin.methods = new ArrayList<>(List.of(handler));
		ClassWriter writer = new ClassWriter(0);
		mixin.accept(writer);
		return writer.toByteArray();
	}

	/** What a mixin names that resolves on vanilla and not on the merged base, judged as KernelGuestMixinAdapter judges it. */
	static List<String> lostAnchors(byte[] mixin, Function<String, byte[]> vanilla, Function<String, byte[]> merged) {
		System.setProperty(MixinFit.LIVENESS_PROPERTY, "off");
		Set<String> natively = new LinkedHashSet<>(MixinFit.evaluate(mixin, vanilla).unresolved());
		List<String> out = new ArrayList<>();
		for (String anchor : judge(mixin, merged, "off").unresolved()) if (!natively.contains(anchor) && !out.contains(anchor)) out.add(anchor);
		return out;
	}

	/** Stock 26.2's game classes; anything else is not the game's and resolves. */
	private static Function<String, byte[]> vanillaResolver(Path vanilla) throws IOException {
		Map<String, byte[]> classes = read(Files.readAllBytes(vanilla), n -> n.endsWith(".class"));
		return name -> name.startsWith("net/minecraft/") || name.startsWith("com/mojang/") ? classes.get(name) : null;
	}

	/** The merged game as the kernel serves it: its compat pass, the field twins, and the duplicate-lambda prune. */
	private static Function<String, byte[]> mergedResolver() throws IOException {
		Map<String, byte[]> game = new HashMap<>();
		for (Path jar : List.of(NEO_RUNTIME, INTEROP, MERGED)) game.putAll(read(Files.readAllBytes(jar), n -> n.endsWith(".class")));
		Map<String, byte[]> served = new java.util.concurrent.ConcurrentHashMap<>();
		return name -> {
			byte[] raw = game.get(name);
			if (raw == null || !(name.startsWith("net/minecraft/") || name.startsWith("com/mojang/"))) return null;
			return served.computeIfAbsent(name, n -> {
				String binary = n.substring(0, n.length() - ".class".length()).replace('/', '.');
				byte[] out = new net.forbric.kernel.transform.ForbricMergedBaseCompatTransformer().transform(binary, raw, null);
				out = new net.forbric.kernel.transform.WidenedFieldTwinInjector().transform(binary, out, null);
				byte[] pruned = new net.forbric.kernel.transform.DuplicateLambdaPruneInjector().transform(binary, out, null);
				return pruned == null ? out : pruned;
			});
		};
	}

	/** debugify's RangedBowAttackGoalMixin, as compiled: AFTER Monster.lookAt in tick. */
	private static byte[] debugifyShaped() {
		ClassNode mixin = new ClassNode();
		mixin.version = org.objectweb.asm.Opcodes.V21;
		mixin.access = org.objectweb.asm.Opcodes.ACC_PUBLIC;
		mixin.name = "test/census/RangedBowAttackGoalMixin";
		mixin.superName = "java/lang/Object";
		AnnotationNode type = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(org.objectweb.asm.Type.getObjectType(
				"net/minecraft/world/entity/ai/goal/RangedBowAttackGoal")))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(type));
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target",
				"Lnet/minecraft/world/entity/monster/Monster;lookAt(Lnet/minecraft/world/entity/Entity;FF)V"));
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of("tick")), "at", new ArrayList<>(List.of(at))));
		MethodNode handler = new MethodNode(org.objectweb.asm.Opcodes.ACC_PRIVATE, "lookAtTarget",
				"(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
		handler.visibleAnnotations = new ArrayList<>(List.of(inject));
		mixin.methods = new ArrayList<>(List.of(handler));
		ClassWriter writer = new ClassWriter(0);
		mixin.accept(writer);
		return writer.toByteArray();
	}
}
