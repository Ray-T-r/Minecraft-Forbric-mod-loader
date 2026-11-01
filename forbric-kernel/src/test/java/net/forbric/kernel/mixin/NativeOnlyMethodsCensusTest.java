/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.json.JsonFormat;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModPresence;
import net.forbric.api.UnifiedDependency;
import net.forbric.kernel.metadata.forge.ModsTomlParser;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

/**
 * Re-derives {@code native-only-methods.txt} — for each platform a mod can be native to (vanilla 26.2 for Fabric,
 * NeoForge's patched 26.2 for NeoForge), every method it declares in vanilla's packages that the staged base does not,
 * and every class there only the base has — plus the base's members digest, and asserts the shipped table equals it.
 * With the base's own raw bytes that is each platform's member list, which is what {@link NativeAbsentTargets} needs to
 * tell an injector target the mod's own platform lacks too from one the base lost.
 *
 * <p>Raw, not after the vanilla twins: NativeAbsentTargets is asked about the raw class and the raw jar's members digest
 * (ForbricMixinService reads the pre-chain bytes for it), so a member only a twin adds is a row here. If that twin stands
 * down at run time (no vanilla reference), a Fabric injector on the member is then reported as the loss it is instead of
 * being dropped as "the platform lacks it too".
 *
 * <p>The predicate is then checked against all of each platform, not a sample: no method a platform's jar declares may
 * ever be called absent for that platform's mods. That is the direction that matters — a wrong "absent" would hide a
 * loss of the base behind native's silence — and a sample would not show it.
 *
 * <p>The table also says which game each platform's rows describe — the versions the base's own build pins record,
 * which the staged NeoForge carrier and vanilla's jar must declare as well — and which of Minecraft's library jars,
 * named by vanilla's version JSON, ship classes in vanilla's packages, each by its members digest.
 */
@ResourceLock("ModCatalog")
@ResourceLock("ModPresence")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class NativeOnlyMethodsCensusTest {
	private static final Path STAGED = TestFixtures.stagedRoot();
	private static final Path MERGED = STAGED.resolve(TestFixtures.BASE_JAR);
	private static final Path BLOCK_ENTITY_SOURCE = TestFixtures.vanillaJar();
	private static final String BLOCK_ENTITY = "net/minecraft/world/level/block/entity/BlockEntity";

	/** Each platform's game: the jar its mods were compiled against and run on. */
	private static final Map<Ecosystem, Path> NATIVE = new EnumMap<>(Map.of(
			Ecosystem.FABRIC, TestFixtures.vanillaJar(),
			Ecosystem.NEOFORGE, STAGED.resolve(TestFixtures.BASE_JAR)));

	/** The base's build pins ({@code mc= neoforge= nfrt= result= tools=}), which prepare stages beside it (D31). */
	private static final Path PINS = STAGED.resolve(TestFixtures.BASE_JAR + ".pins");
	private static final Path NEO_RUNTIME = STAGED.resolve(TestFixtures.NEO_CARRIER_JAR);
	private static final Path VERSION_JSON = TestFixtures.minecraftDir().resolve("versions/26.2/26.2.json");

	/** platform → class in vanilla's packages → its methods' {@code name + desc}. */
	private static Map<Ecosystem, Map<String, Set<String>>> platforms;
	private static Map<String, byte[]> merged;
	private static String mergedMembers;
	/** platform → requirement id → the version of the game the rows describe. */
	private static Map<Ecosystem, Map<String, String>> versions;
	/** Each of vanilla's library jars that ships classes in vanilla's packages, by its Maven name. */
	private static Map<String, Path> libraries;

	@BeforeAll static void read() throws Exception {
		TestFixtures.requireFile(Fixture.STAGED, MERGED, "staged base required: " + MERGED);
		TestFixtures.requireFiles(Fixture.STAGED, "the base's build pins and the NeoForge carrier", PINS, NEO_RUNTIME);
		TestFixtures.require(Fixture.MC_LIBRARIES, Files.isRegularFile(VERSION_JSON), "vanilla's version JSON: " + VERSION_JSON);
		for (Map.Entry<Ecosystem, Path> platform : NATIVE.entrySet()) {
			Path jar = platform.getValue();
			Fixture kind = platform.getKey() == Ecosystem.FABRIC || !jar.startsWith(STAGED) ? Fixture.MC_LIBRARIES : Fixture.STAGED;
			TestFixtures.require(kind, Files.isRegularFile(jar), platform.getKey().displayName() + "'s game required: " + jar);
		}
		platforms = new EnumMap<>(Ecosystem.class);
		for (Map.Entry<Ecosystem, Path> platform : NATIVE.entrySet()) platforms.put(platform.getKey(), members(platform.getValue()));
		merged = new HashMap<>();
		try (ZipFile zip = new ZipFile(MERGED.toFile())) {
			for (ZipEntry entry : Collections.list(zip.entries())) {
				if (entry.getName().endsWith(".class")) merged.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
			}
		}
		mergedMembers = NativeAbsentTargets.membersDigest(MERGED);
		try (ZipFile zip = new ZipFile(NATIVE.get(Ecosystem.FABRIC).toFile())) {
			for (ZipEntry entry : Collections.list(zip.entries())) {
				String name = entry.getName();
				if (name.endsWith(".class") && !inVanillaPackages(name)) vanillaElsewhere.add(name);
			}
		}
		versions = gameVersions();
		libraries = vanillaLibraries();
	}

	/**
	 * The game each platform's rows describe, from the base's own pins ({@code mc=26.2 neoforge=26.2.0.93 nfrt=...}, D31):
	 * {@code minecraft} for both, and NeoForge's id at the version its loader reports to a mod's range. Asserted against
	 * what vanilla's jar and the NeoForge carrier's own toml declare, so the rows' game is the one the kernel runs.
	 */
	private static Map<Ecosystem, Map<String, String>> gameVersions() throws Exception {
		Map<String, String> pins = new HashMap<>();
		for (String pin : Files.readString(PINS).strip().split("\\s+")) {
			int eq = pin.indexOf('=');
			if (eq > 0) pins.put(pin.substring(0, eq), pin.substring(eq + 1));
		}
		String minecraft = pins.get("mc");
		String neoforge = pins.get("neoforge");
		assertNotNull(minecraft, "pins name the Minecraft version: " + pins);
		assertNotNull(neoforge, "pins name the NeoForge build: " + pins);

		try (ZipFile vanilla = new ZipFile(NATIVE.get(Ecosystem.FABRIC).toFile())) {
			UnmodifiableConfig json = JsonFormat.fancyInstance().createParser()
					.parse(new String(vanilla.getInputStream(vanilla.getEntry("version.json")).readAllBytes(), StandardCharsets.UTF_8));
			assertEquals(minecraft, json.get("id"), "vanilla's jar is the Minecraft the merge read");
		}
		try (ZipFile carrier = new ZipFile(NEO_RUNTIME.toFile())) {
			var toml = ModsTomlParser.parse(carrier.getInputStream(carrier.getEntry("META-INF/neoforge.mods.toml")));
			assertEquals(List.of(neoforge), toml.getMods().stream().filter(m -> m.getModId().equals("neoforge"))
					.map(m -> m.getVersion()).toList(), "the NeoForge carrier is the build the merge read");
		}
		Map<Ecosystem, Map<String, String>> out = new EnumMap<>(Ecosystem.class);
		out.put(Ecosystem.FABRIC, Map.of(NativeAbsentTargets.MINECRAFT, minecraft));
		out.put(Ecosystem.NEOFORGE, Map.of(NativeAbsentTargets.MINECRAFT, minecraft, "neoforge", neoforge));
		return out;
	}

	/**
	 * Every library vanilla's version JSON lists whose jar ships a class in vanilla's packages. A listed jar that is not
	 * on disk is a missing fixture, unless the library is one platform's natives (it has OS rules); those carry no
	 * classes.
	 */
	private static Map<String, Path> vanillaLibraries() throws Exception {
		UnmodifiableConfig json = JsonFormat.fancyInstance().createParser().parse(Files.readString(VERSION_JSON));
		Map<String, Path> out = new TreeMap<>();
		for (Object entry : (List<?>) json.get("libraries")) {
			UnmodifiableConfig library = (UnmodifiableConfig) entry;
			String path = library.get(List.of("downloads", "artifact", "path"));
			if (path == null) continue;
			Path jar = TestFixtures.minecraftDir().resolve("libraries").resolve(path);
			if (!Files.isRegularFile(jar)) {
				TestFixtures.require(Fixture.MC_LIBRARIES, library.get("rules") != null, "vanilla's library " + jar);
				continue;
			}
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				if (Collections.list(zip.entries()).stream().anyMatch(e -> e.getName().endsWith(".class") && inVanillaPackages(e.getName()))) {
					out.put(library.get("name"), jar);
				}
			}
		}
		return out;
	}

	/** Classes vanilla's jar ships outside {@link NativeAbsentTargets#VANILLA_PACKAGES}: none, or the table is short. */
	private static final List<String> vanillaElsewhere = new ArrayList<>();

	@BeforeEach @AfterEach void clean() {
		CompatibilityFindings.reset();
		MixinConfigOwners.reset();
		System.clearProperty(NativeAbsentTargets.PROPERTY);
		System.clearProperty(NativeAbsentTargets.BASE_PROPERTY);
		ModPresence.publishFabric(List.of());
		ModPresence.publishForgeFamily(List.of());
	}

	@Test void theShippedTableIsExactlyWhatTheArtifactsSay() throws Exception {
		assertEquals(List.of(), vanillaElsewhere, "vanilla ships classes outside " + NativeAbsentTargets.VANILLA_PACKAGES
				+ "; the table only speaks for those packages");
		TreeSet<String> rows = new TreeSet<>();
		rows.add(NativeAbsentTargets.BASE + mergedMembers);
		Map<String, Set<String>> mergedMethods = new HashMap<>();
		for (Map.Entry<String, byte[]> entry : merged.entrySet()) {
			mergedMethods.put(entry.getKey().substring(0, entry.getKey().length() - ".class".length()), methods(entry.getValue()));
		}
		for (Map.Entry<String, Path> library : libraries.entrySet()) {
			rows.add(NativeAbsentTargets.LIBRARY + NativeAbsentTargets.membersDigest(library.getValue()) + " " + library.getKey());
		}
		for (Map.Entry<Ecosystem, Map<String, Set<String>>> platform : platforms.entrySet()) {
			String id = NativeAbsentTargets.idOf(platform.getKey());
			StringBuilder line = new StringBuilder(NativeAbsentTargets.PLATFORM + id);
			new TreeMap<>(versions.get(platform.getKey())).forEach((game, version) -> line.append(' ').append(game).append('=').append(version));
			rows.add(line.toString());
			for (Map.Entry<String, Set<String>> original : platform.getValue().entrySet()) {
				Set<String> declared = mergedMethods.get(original.getKey());
				assertNotNull(declared, "the base lacks " + platform.getKey().displayName() + "'s class " + original.getKey()
						+ "; NativeAbsentTargets' javadoc says every class the platforms declare in vanilla's packages is there");
				for (String method : original.getValue()) {
					if (!declared.contains(method)) rows.add(id + " " + original.getKey() + "#" + method);
				}
			}
			for (String name : mergedMethods.keySet()) {
				if (inVanillaPackages(name) && !platform.getValue().containsKey(name)) {
					rows.add(id + " " + NativeAbsentTargets.MERGED_ONLY + name);
				}
			}
		}
		assertTrue(rows.stream().noneMatch(r -> r.startsWith("fabric " + BLOCK_ENTITY + "#")),
				"BlockEntity lost no vanilla method in the merge, so a BlockEntity miss is vanilla's too: " + rows);

		List<String> shipped = new ArrayList<>();
		try (InputStream in = NativeAbsentTargets.class.getResourceAsStream(NativeAbsentTargets.TABLE)) {
			assertNotNull(in, NativeAbsentTargets.TABLE + " is missing");
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				if (!line.isBlank() && !line.startsWith("#")) shipped.add(line.trim());
			}
		}
		if (System.getenv("FORBRIC_WRITE_NATIVE_ONLY_METHODS") != null) {
			Path out = Path.of("src/main/resources" + NativeAbsentTargets.TABLE);
			Files.writeString(out, "# Generated by NativeOnlyMethodsCensusTest (FORBRIC_WRITE_NATIVE_ONLY_METHODS=1): for each platform a mod\n"
					+ "# can be native to, what its own game declares in vanilla's packages that the staged base does not.\n"
					+ "# With the base's raw bytes (before any kernel transformer) this is that platform's member list;\n"
					+ "# NativeAbsentTargets asks it whether an injector target the base lacks is one the owning mod's platform\n"
					+ "# lacks too. The base is NeoForge's own game, so the neoforge platform has no rows.\n"
					+ "# base <digest>                      the members digest of the raw base the rows were derived from\n"
					+ "# library <digest> <name>            the members digest of one of vanilla's library jars, answered for\n"
					+ "#                                    as it is (every platform loads the same jars)\n"
					+ "# platform <id> <req>=<version>...   the table speaks for this platform, whose game is the one those\n"
					+ "#                                    versions name (fabric: vanilla; neoforge: NeoForge's patched game);\n"
					+ "#                                    a mod whose mandatory range excludes them is not answered for\n"
					+ "# <id> <owner>#<name><descriptor>    a method the platform declares and the base's class does not\n"
					+ "# <id> merged-only <owner>           a class in vanilla's packages the platform does not have\n"
					+ String.join("\n", rows) + "\n");
		}
		assertEquals(rows, new TreeSet<>(shipped), "native-only-methods.txt must equal what the staged base and the "
				+ "platforms' jars say; regenerate with FORBRIC_WRITE_NATIVE_ONLY_METHODS=1 after a base rebuild");
	}

	/** The jar the runtime reads and the bytes the table was derived from give one digest. */
	@Test void theRunningBaseIsTheOneTheTableSpeaksFor() {
		assertEquals(mergedMembers, NativeAbsentTargets.shipped().base(), "the shipped table records the staged base's members");
		assertEquals(mergedMembers, NativeAbsentTargets.membersDigest(merged), "by path and by map, one digest");
	}

	/** The direction that matters, over every method each platform declares: none is ever called absent for its mods. */
	@Test void noMethodAPlatformDeclaresIsEverCalledAbsentForItsOwnMods() {
		NativeAbsentTargets.Table table = NativeAbsentTargets.shipped();
		Function<String, byte[]> raw = merged::get;
		for (Map.Entry<Ecosystem, Map<String, Set<String>>> platform : platforms.entrySet()) {
			List<String> wrong = new ArrayList<>();
			int asked = 0;
			for (Map.Entry<String, Set<String>> original : platform.getValue().entrySet()) {
				for (String method : original.getValue()) {
					asked++;
					int paren = method.indexOf('(');
					String name = method.substring(0, paren);
					if (NativeAbsentTargets.nativeLacks(platform.getKey(), original.getKey(), name, method.substring(paren), raw, table)
							|| NativeAbsentTargets.nativeLacks(platform.getKey(), original.getKey(), name, null, raw, table)) {
						wrong.add(original.getKey() + "#" + method);
					}
				}
			}
			assertTrue(asked > 90_000, platform.getKey().displayName() + " declares far more methods than " + asked);
			assertEquals(List.of(), wrong, "methods " + platform.getKey().displayName() + " declares that the predicate calls "
					+ "absent from it");
		}
	}

	/**
	 * And the classes a platform does not have are never answered for: Mixin fails on those for another reason. The base
	 * is NeoForge's own game, so it has no class NeoForge lacks; vanilla lacks a few of its anonymous and helper classes.
	 */
	@Test void aClassOnlyTheMergedBaseHasIsNeverAnsweredFor() {
		NativeAbsentTargets.Table table = NativeAbsentTargets.shipped();
		for (Ecosystem platform : platforms.keySet()) {
			assertNotNull(table.of(platform), "the table speaks for " + platform.displayName());
			if (platform == Ecosystem.NEOFORGE) {
				assertTrue(table.of(platform).mergedOnly().isEmpty(), "the base is NeoForge's own game: " + table.of(platform).mergedOnly());
				continue;
			}
			assertFalse(table.of(platform).mergedOnly().isEmpty(), "the measured base has classes in vanilla's packages "
					+ platform.displayName() + " lacks");
			for (String owner : table.of(platform).mergedOnly()) {
				assertFalse(NativeAbsentTargets.nativeLacks(platform, owner, "noSuchMethod", null, merged::get, table), owner);
			}
		}
	}

	/**
	 * Not Enough Crashes' case on the real base: an injector into {@code BlockEntity.populateCrashReport}, the Yarn
	 * name of what 26.2 calls {@code fillCrashReportCategory}, in a Fabric mod, is left out of the verdict, and the
	 * mixin is FIT. A method only vanilla has (a row) stays a miss — UNFIT — which is the merge's loss.
	 */
	@Test void theNotEnoughCrashesShapeIsFitOnTheRealBaseAndAVanillaOnlyMethodStaysUnfit() {
		Set<String> blockEntity = platforms.get(Ecosystem.FABRIC).get(BLOCK_ENTITY);
		assertFalse(blockEntity.stream().anyMatch(m -> m.startsWith("populateCrashReport(")), "vanilla's BlockEntity has no "
				+ "populateCrashReport in " + BLOCK_ENTITY_SOURCE);
		assertTrue(blockEntity.stream().anyMatch(m -> m.startsWith("fillCrashReportCategory(")), "…it has fillCrashReportCategory");
		Function<String, byte[]> resolver = merged::get;
		NativeAbsentTargets.Context context = new NativeAbsentTargets.Context(resolver, 0, Ecosystem.FABRIC, owner -> mergedMembers,
				mod(Ecosystem.FABRIC, "nec", NativeAbsentTargets.MINECRAFT, "26.2"));

		MixinFit.Result nec = MixinFit.evaluate(injecting("MixinTileEntity", BLOCK_ENTITY, "populateCrashReport"), resolver,
				name -> true, MixinAddedMembers.View.NONE, context);
		assertEquals(MixinFit.Verdict.FIT, nec.verdict(), nec.toString());
		assertEquals(List.of("@Inject target BlockEntity.populateCrashReport"), nec.nativeAbsent());
		MixinFit.Result before = MixinFit.evaluate(injecting("MixinTileEntity", BLOCK_ENTITY, "populateCrashReport"), resolver,
				name -> true, MixinAddedMembers.View.NONE, NativeAbsentTargets.Context.NONE);
		assertEquals(MixinFit.Verdict.UNFIT, before.verdict(), "without the native view it is the UNFIT that stopped the server");
		// A build of the same mod for a newer game: vanilla 26.2 is not its native game, so nothing is answered for it.
		MixinFit.Result newer = MixinFit.evaluate(injecting("MixinTileEntity", BLOCK_ENTITY, "populateCrashReport"), resolver,
				name -> true, MixinAddedMembers.View.NONE, new NativeAbsentTargets.Context(resolver, 0, Ecosystem.FABRIC,
						owner -> mergedMembers, mod(Ecosystem.FABRIC, "nec", NativeAbsentTargets.MINECRAFT, ">=26.3")));
		assertEquals(MixinFit.Verdict.UNFIT, newer.verdict(), "a mod requiring a newer Minecraft: " + newer);
		assertEquals(List.of(), newer.nativeAbsent(), newer.toString());

		// NeoForge widened it with the BlockPos; vanilla's one-argument form is a row, so the miss is the merge's.
		String entity = "net/minecraft/world/entity/Entity";
		String muffled = "playMuffledStepSound(Lnet/minecraft/world/level/block/state/BlockState;)V";
		assertTrue(NativeAbsentTargets.shipped().of(Ecosystem.FABRIC).lists(entity, "playMuffledStepSound", null),
				"the row this case stands on");
		MixinFit.Result lost = MixinFit.evaluate(injecting("Lost", entity, muffled), resolver, n -> true,
				MixinAddedMembers.View.NONE, context);
		assertEquals(MixinFit.Verdict.UNFIT, lost.verdict(), "Entity." + muffled + " is vanilla's and the merge lost it: " + lost);
		assertEquals(List.of(), lost.nativeAbsent(), lost.toString());
	}

	/**
	 * Methods a mod's own game declares that the base dropped or retyped: vanilla methods NeoForge's patches replaced
	 * (a Fabric mod's loss), and a NeoForge method the base keeps (NeoForge's own game, so nothing of NeoForge's is
	 * lost). The other platform lacks each too, so the other platform's mod's injector into one is dropped natively and
	 * kept here; the same bytes in the owning platform's mod's config inject natively, so here they stay the CONFIRMED
	 * loss they are — required, since the config says required. (On the merged base four of these were MinecraftForge's
	 * methods the merge lost; that platform is gone.)
	 */
	@Test void aMethodTheModsOwnPlatformHasAndTheMergeLostStaysAConfirmedLoss() {
		List<Lost> cases = List.of(
				new Lost(Ecosystem.FABRIC, "net/minecraft/world/entity/Entity",
						"playMuffledStepSound(Lnet/minecraft/world/level/block/state/BlockState;)V"),
				new Lost(Ecosystem.FABRIC, "net/minecraft/world/entity/Entity", "playCombinationStepSounds("
						+ "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V"),
				new Lost(Ecosystem.FABRIC, "net/minecraft/world/level/block/FireBlock", "checkBurnOut("
						+ "Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;ILnet/minecraft/util/RandomSource;I)V"),
				new Lost(Ecosystem.NEOFORGE, "net/minecraft/world/entity/boss/enderdragon/EnderDragon",
						"getParts()[Lnet/neoforged/neoforge/entity/PartEntity;"));
        int retained=0,missing=0;
		for (Lost lost : cases) {
			String method = lost.selector();
			Ecosystem other = lost.platform() == Ecosystem.FABRIC ? Ecosystem.NEOFORGE : Ecosystem.FABRIC;
			assertTrue(declares(platforms.get(lost.platform()).get(lost.owner()), method),
					lost.platform().displayName() + " declares " + lost.owner() + "#" + method);
            if(declares(methods(merged.get(lost.owner()+".class")),method)){
                retained++;
                var context=new NativeAbsentTargets.Context(merged::get,0,lost.platform(),owner->mergedMembers,mod(lost.platform(),"retained"));
                MixinFit.Result result=MixinFit.evaluate(injecting("Retained",lost.owner(),method),merged::get,n->true,MixinAddedMembers.View.NONE,context);
                assertEquals(MixinFit.Verdict.FIT,result.verdict(),"the rebuilt base retained this native public API: "+lost+" "+result);
                assertEquals(List.of(),result.nativeAbsent());continue;
            }
            missing++;
			assertFalse(declares(platforms.get(other).get(lost.owner()), method), "…nor " + other.displayName());

			Map<String, byte[]> classes = new HashMap<>(merged);
			classes.put("net/example/lost/LostMixin.class", injecting("LostMixin", lost.owner(), method, "net/example/lost/"));
			String config = "lost-" + lost.platform().familyId() + ".mixins.json";
			String otherConfig = "lost-" + other.familyId() + "-" + lost.platform().familyId() + ".mixins.json";
			MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned(config, "lostmod", lost.platform()),
					new MixinConfigOwners.Owned(otherConfig, "lostothermod", other)));
			DiscoveredMod owning = mod(lost.platform(), "lostmod"), others = mod(other, "lostothermod");
			ModPresence.publishForgeFamily(List.of(lost.platform() == Ecosystem.NEOFORGE ? owning : others));
			ModPresence.publishFabric(List.of(lost.platform() == Ecosystem.FABRIC ? owning : others));

			assertEquals(List.of("LostMixin"), KernelGuestMixinAdapter.unfitMixins(config, json(), classes::get, null,
					classes::get, owner -> mergedMembers), lost + ": the mod's own platform has it, so the merge lost it");
			String id = "mixin:" + config + ":net.example.lost.LostMixin";
			assertTrue(CompatibilityFindings.all().stream().anyMatch(f -> f.id().equals(id)
					&& f.confidence() == CompatibilityFinding.Confidence.CONFIRMED && f.required()),
					lost + ": a CONFIRMED required loss — " + CompatibilityFindings.all());

			CompatibilityFindings.reset();
			assertEquals(List.of(), KernelGuestMixinAdapter.unfitMixins(otherConfig, json(), classes::get, null,
					classes::get, owner -> mergedMembers), lost + ": " + other.displayName() + " lacks it too, so its mod's "
							+ "injector is dropped natively and the mixin is kept here");
			assertEquals(List.of(), CompatibilityFindings.all(), lost.toString());
			CompatibilityFindings.reset();
			MixinConfigOwners.reset();
		}
        assertTrue(retained>0,"the new base preserves former native losses");assertTrue(missing>0,"remaining actual deficits are still reported");
	}

	private record Lost(Ecosystem platform, String owner, String selector) {
	}

	/**
	 * Minecraft's own libraries are answered from their own bytes, as every platform loads them; the review's probe
	 * — brigadier's {@code CommandDispatcher}, a plain selector it does not declare, nothing requiring the injector — is
	 * dropped as native drops it, and no method any library declares is ever called absent. The same class from a jar
	 * whose members are not the table's (another version of the library) is not answered for: the miss stays the merge's.
	 */
	@Test void aMinecraftLibraryIsAnsweredFromItsOwnBytesAndOnlyAtVanillasVersion() throws Exception {
		NativeAbsentTargets.Table table = NativeAbsentTargets.shipped();
		assertTrue(libraries.keySet().stream().anyMatch(name -> name.startsWith("com.mojang:brigadier:")), libraries.toString());
		assertTrue(libraries.keySet().stream().allMatch(name -> name.startsWith("com.mojang:")),
				"only Mojang's libraries ship classes in vanilla's packages: " + libraries.keySet());
		int asked = 0;
		for (Map.Entry<String, Path> library : libraries.entrySet()) {
			assertTrue(table.libraries().contains(NativeAbsentTargets.membersDigest(library.getValue())), library.getKey());
			Map<String, byte[]> classes = classes(library.getValue());
			// The jar that serves such a class is the library's own, never the merged base or a platform's game.
			for (String name : classes.keySet()) {
				String owner = name.substring(0, name.length() - ".class".length());
				assertFalse(merged.containsKey(name), library.getKey() + "'s " + name + " is in the merged base too");
				for (Map.Entry<Ecosystem, Map<String, Set<String>>> platform : platforms.entrySet()) {
					assertFalse(platform.getValue().containsKey(owner), library.getKey() + "'s " + name + " is in "
							+ platform.getKey().displayName() + "'s game too");
				}
			}
			List<String> wrong = new ArrayList<>();
			for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
				String owner = entry.getKey().substring(0, entry.getKey().length() - ".class".length());
				if (!inVanillaPackages(owner)) continue;
				for (String method : methods(entry.getValue())) {
					int paren = method.indexOf('(');
					for (Ecosystem platform : platforms.keySet()) {
						asked++;
						if (NativeAbsentTargets.nativeLacks(platform, owner, method.substring(0, paren), method.substring(paren), classes::get, table)
								|| NativeAbsentTargets.nativeLacks(platform, owner, method.substring(0, paren), null, classes::get, table)) {
							wrong.add(platform + " " + owner + "#" + method);
						}
					}
				}
			}
			assertEquals(List.of(), wrong, library.getKey() + ": methods it declares that the predicate calls absent");
		}
		assertTrue(asked > 10_000, "the libraries declare far more methods than " + asked);

		Path brigadier = libraries.entrySet().stream().filter(e -> e.getKey().startsWith("com.mojang:brigadier:"))
				.findFirst().orElseThrow().getValue();
		Map<String, byte[]> classes = classes(brigadier);
		String digest = NativeAbsentTargets.membersDigest(brigadier);
		String dispatcher = "com/mojang/brigadier/CommandDispatcher";
		for (Ecosystem platform : platforms.keySet()) {
			MixinFit.Result probe = MixinFit.evaluate(injecting("DispatcherMixin", dispatcher, "forbricNoSuchMethod"), classes::get,
					name -> true, MixinAddedMembers.View.NONE, new NativeAbsentTargets.Context(classes::get, 0, platform,
							owner -> digest, mod(platform, "probe")));
			assertEquals(MixinFit.Verdict.FIT, probe.verdict(), platform + ": " + probe);
			assertEquals(List.of("@Inject target CommandDispatcher.forbricNoSuchMethod"), probe.nativeAbsent(), probe.toString());
		}

		// Another version of the library: one method fewer, so another members digest.
		ClassNode node = new ClassNode();
		new ClassReader(classes.get(dispatcher + ".class")).accept(node, 0);
		node.methods.removeIf(m -> m.name.equals("getRoot"));
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		Map<String, byte[]> other = new HashMap<>(classes);
		other.put(dispatcher + ".class", writer.toByteArray());
		String otherDigest = NativeAbsentTargets.membersDigest(other);
		assertNotEquals(digest, otherDigest);
		MixinFit.Result unlisted = MixinFit.evaluate(injecting("DispatcherMixin", dispatcher, "forbricNoSuchMethod"), other::get,
				name -> true, MixinAddedMembers.View.NONE, new NativeAbsentTargets.Context(other::get, 0, Ecosystem.FABRIC,
						owner -> otherDigest, mod(Ecosystem.FABRIC, "probe")));
		assertEquals(MixinFit.Verdict.UNFIT, unlisted.verdict(), "a library jar the table does not list: " + unlisted);
		assertEquals(List.of(), unlisted.nativeAbsent(), unlisted.toString());
	}

	/** The mod discovery would have read: of {@code platform}, requiring {@code requires} as id/constraint pairs. */
	private static DiscoveredMod mod(Ecosystem platform, String id, String... requires) {
		List<UnifiedDependency> deps = new ArrayList<>();
		for (int i = 0; i + 1 < requires.length; i += 2) deps.add(new UnifiedDependency(requires[i], requires[i + 1], true));
		return new DiscoveredMod(platform, id, "1.0", id, deps, List.of(), null, id + ".jar");
	}

	private static Map<String, byte[]> classes(Path jar) throws Exception {
		Map<String, byte[]> out = new HashMap<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : Collections.list(zip.entries())) {
				if (entry.getName().endsWith(".class")) out.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
			}
		}
		return out;
	}

	/** Whether {@code methods} holds {@code selector}: exactly when it has a descriptor, by name when it is bare. */
	private static boolean declares(Set<String> methods, String selector) {
		return selector.indexOf('(') >= 0 ? methods.contains(selector)
				: methods.stream().anyMatch(m -> m.startsWith(selector + "("));
	}

	private static byte[] json() {
		return "{\"required\":true,\"package\":\"net.example.lost\",\"mixins\":[\"LostMixin\"]}".getBytes(StandardCharsets.UTF_8);
	}

	/** A guest mixin on {@code target} whose one {@code @Inject} names {@code selector} — and nothing else. */
	private static byte[] injecting(String simpleName, String target, String selector) {
		return injecting(simpleName, target, selector, "net/example/mixin/");
	}

	private static byte[] injecting(String simpleName, String target, String selector, String pkg) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, pkg + simpleName, null, "java/lang/Object", null);
		AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor targets = mixin.visitArray("value");
		targets.visit(null, Type.getObjectType(target));
		targets.visitEnd();
		mixin.visitEnd();
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "onReport", "()V", null, null);
		AnnotationVisitor inject = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
		AnnotationVisitor methods = inject.visitArray("method");
		methods.visit(null, selector);
		methods.visitEnd();
		inject.visitEnd();
		mv.visitCode();
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 1);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static boolean inVanillaPackages(String name) {
		return NativeAbsentTargets.VANILLA_PACKAGES.stream().anyMatch(name::startsWith);
	}

	/** Every class in vanilla's packages in {@code jar}, with its methods' {@code name + desc}. */
	private static Map<String, Set<String>> members(Path jar) throws Exception {
		Map<String, Set<String>> out = new HashMap<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : Collections.list(zip.entries())) {
				String name = entry.getName();
				if (!name.endsWith(".class")) continue;
				String internal = name.substring(0, name.length() - ".class".length());
				if (!inVanillaPackages(internal)) continue;
				out.put(internal, methods(zip.getInputStream(entry).readAllBytes()));
			}
		}
		return out;
	}

	private static Set<String> methods(byte[] bytes) {
		Set<String> out = new HashSet<>();
		new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
				out.add(name + desc);
				return null;
			}
		}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return out;
	}
}
