package net.forbric.kernel.discovery;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * The per-half judgement that decides which jars Forbric refuses now that it no longer loads MinecraftForge mods.
 *
 * <p>Every case is a shape from the plan's fixture matrix, built here so it cannot drift, and each one has been run
 * red: the expected verdict was inverted (or the deciding evidence removed from the jar) and the assertion failed with
 * the reason the half printed. The three real multi-manifest Forge builds are checked against the jars themselves
 * where the local corpus has them.
 */
class LoaderHalfJudgeTest {
	private static final String FORGE_MOD = "Lnet/minecraftforge/fml/common/Mod;";
	private static final String NEO_MOD = "Lnet/neoforged/fml/common/Mod;";
	private static final String MAIN = "net/fabricmc/api/ModInitializer";

	// ----------------------------------------------------------------------------------------------- refused

	@Test void aForgeOnlyJarIsRefused() throws Exception {
		LoaderHalfJudge.Verdict verdict = judge(jar()
				.forgeToml("onlyforge", "Only Forge")
				.put("example/ForgeMod.class", forgeMod("example/ForgeMod", "onlyforge")));
		assertTrue(verdict.refused(), verdict.describe());
		assertEquals(List.of(), verdict.usableFamilies());
		assertEquals("onlyforge", verdict.modId());
		assertEquals("Only Forge", verdict.displayName());
		assertTrue(verdict.describe().contains("Forbric no longer loads MinecraftForge mods"), verdict.describe());
	}

	/** animalgarden-owl's shape: every manifest, one Forge @Mod, and that Forge class named as the Fabric entrypoint. */
	@Test void aForgeBuildWhoseOtherManifestsPointAtItsForgeClassIsRefused() throws Exception {
		LoaderHalfJudge.Verdict verdict = judge(jar()
				.forgeToml("owl", "Owl").neoToml("owl", "Owl", null)
				.fabricJson("owl", "{\"main\":[\"example.ModSetup\"],\"client\":[\"example.ClientInit\"]}", null)
				.put("example/ModSetup.class", forgeMod("example/ModSetup", "owl"))
				.put("example/Registrations.class", referencing("example/Registrations",
						"net/minecraftforge/registries/DeferredRegister"))
				.put("example/ClientInit.class", plain("example/ClientInit", null, true)));
		assertTrue(verdict.refused(), verdict.describe());
		String neo = verdict.half(Ecosystem.NEOFORGE).orElseThrow().reason();
		assertTrue(neo.contains("its only entry is MinecraftForge's"), neo);
		assertTrue(neo.contains("links only against MinecraftForge (1 class(es) beyond the entry"), neo);
		assertTrue(neo.contains("its @Mod(\"owl\") is MinecraftForge's example.ModSetup"), neo);
		String fabric = verdict.half(Ecosystem.FABRIC).orElseThrow().reason();
		assertTrue(fabric.contains("has no public no-argument constructor"), fabric);
		assertTrue(fabric.contains("does not implement net.fabricmc.api.ClientModInitializer"), fabric);
	}

	/**
	 * aquariuslibs' shape: a neoforge.mods.toml beside the Forge one, the id's only @Mod MinecraftForge's
	 * ({@code ModLibSetup}), helpers beyond it that call MinecraftForge ({@code RegisterFunctions}), and not one class
	 * naming net.neoforged.
	 */
	@Test void aNeoForgeManifestOverForgeOnlyCodeIsRefused() throws Exception {
		LoaderHalfJudge.Verdict verdict = judge(jar()
				.forgeToml("libs", "Libs").neoToml("libs", "Libs", null)
				.put("example/Libs.class", forgeMod("example/Libs", "libs"))
				.put("example/RegisterFunctions.class", referencing("example/RegisterFunctions",
						"net/minecraftforge/registries/ForgeRegistries")));
		assertTrue(verdict.refused(), verdict.describe());
	}

	/** The entry class is not counted against itself: an MDK-style Forge @Mod with nothing Forge beyond it is kept. */
	@Test void aForgeModClassAloneDoesNotMakeTheNeoForgeHalfFake() throws Exception {
		LoaderHalfJudge.Verdict verdict = judge(jar()
				.forgeToml("libs", "Libs").neoToml("libs", "Libs", "libs.mixins.json")
				.put("libs.mixins.json", "{}".getBytes(StandardCharsets.UTF_8))
				.put("example/Libs.class", forgeMod("example/Libs", "libs"))
				.put("example/mixin/Hook.class", plain("example/mixin/Hook", null, true)));
		assertFalse(verdict.refused(), verdict.describe());
		assertEquals(List.of(Ecosystem.NEOFORGE), verdict.usableFamilies(), verdict.describe());

		// But with nothing beside it — no other class, no mixin config — the whole mod is that MinecraftForge entry, and
		// nothing of it would run on NeoForge: refused.
		LoaderHalfJudge.Verdict entryOnly = judge(jar()
				.forgeToml("libs", "Libs").neoToml("libs", "Libs", null)
				.put("example/Libs.class", forgeMod("example/Libs", "libs")));
		assertTrue(entryOnly.refused(), entryOnly.describe());
		assertTrue(entryOnly.half(Ecosystem.NEOFORGE).orElseThrow().reason().contains("the whole mod is its MinecraftForge "
				+ "entry class"), entryOnly.describe());
	}

	/** journeymap-forge's shape: a Fabric half whose only entrypoint throws "wrong loader". */
	@Test void aFabricHalfThatIsOnlyAWrongLoaderNoticeIsRefused() throws Exception {
		LoaderHalfJudge.Verdict verdict = judge(jar()
				.forgeToml("journeymap", "JourneyMap")
				.fabricJson("journeymap-wrongloader", "{\"preLaunch\":[\"example.FabricError\"]}", null)
				.put("example/FabricError.class", throwingEntrypoint("example/FabricError"))
				.put("example/JourneymapForge.class", forgeMod("example/JourneymapForge", "journeymap")));
		assertTrue(verdict.refused(), verdict.describe());
		assertTrue(verdict.half(Ecosystem.FABRIC).orElseThrow().reason().contains("does nothing but throw"), verdict.describe());
		assertEquals("journeymap", verdict.modId(), "the player is told the mod they installed, not the marker id");
	}

	@Test void aForgeJarNestedInsideAnotherJarIsRefusedWhenJudgedOnItsOwn() throws Exception {
		LoaderHalfJudge.Verdict verdict = judge(nestedForge().build());
		assertTrue(verdict.refused(), verdict.describe());
	}

	// ----------------------------------------------------------------------------------------------- kept

	/**
	 * The Modrinth datapack wrapper's shape (glowing-glow-squids, dragon-egg-recipe, norocketusage …): all three
	 * manifests, a library fabric.mod.json, and one no-argument dummy class carrying a MinecraftForge @Mod and nothing
	 * else of Forge. NeoForge ignores that annotation and loads the data, so the NeoForge half is usable too — and the
	 * jar keeps loading through NeoForge, whose data packs are served whether or not fabric-api is installed.
	 */
	@Test void aDatapackWrapperWhoseOnlyForgeTraceIsADummyModAnnotationKeepsItsNeoForgeHalf() throws Exception {
		LoaderHalfJudge.Verdict verdict = judge(jar()
				.forgeToml("mr_wrapped", "Wrapped").neoToml("mr_wrapped", "Wrapped", null).fabricJson("mr_wrapped", null, null)
				.put("com/modrinth/x/ModrinthWrapper.class", annotated("com/modrinth/x/ModrinthWrapper", FORGE_MOD, "mr_wrapped", "()V"))
				.put("data/mr_wrapped/recipe/x.json", "{}".getBytes(StandardCharsets.UTF_8)));
		assertFalse(verdict.refused(), verdict.describe());
		assertEquals(List.of(Ecosystem.FABRIC, Ecosystem.NEOFORGE), verdict.usableFamilies(), verdict.describe());
		assertTrue(verdict.half(Ecosystem.NEOFORGE).orElseThrow().reason().contains("which NeoForge ignores"), verdict.describe());
	}

	@Test void anArchitecturyStyleForgeAndFabricJarLoadsThroughFabric() throws Exception {
		LoaderHalfJudge.Verdict verdict = judge(jar()
				.forgeToml("arch", "Arch").fabricJson("arch", "{\"main\":[\"example.FabricInit\"]}", null)
				.put("example/FabricInit.class", plain("example/FabricInit", MAIN, true))
				.put("example/ForgeMod.class", forgeMod("example/ForgeMod", "arch")));
		assertFalse(verdict.refused(), verdict.describe());
		assertEquals(List.of(Ecosystem.FABRIC), verdict.usableFamilies());
	}

	@Test void aForgeAndNeoForgeJarLoadsThroughNeoForge() throws Exception {
		LoaderHalfJudge.Verdict verdict = judge(jar()
				.forgeToml("both", "Both").neoToml("both", "Both", null)
				.put("example/ForgeMod.class", forgeMod("example/ForgeMod", "both"))
				.put("example/NeoMod.class", neoMod("example/NeoMod", "both")));
		assertFalse(verdict.refused(), verdict.describe());
		assertEquals(List.of(Ecosystem.NEOFORGE), verdict.usableFamilies());
	}

	@Test void aThreeInOneUniversalJarKeepsBothRemainingHalves() throws Exception {
		LoaderHalfJudge.Verdict verdict = judge(jar()
				.forgeToml("tri", "Tri").neoToml("tri", "Tri", null)
				.fabricJson("tri", "{\"main\":[\"example.FabricInit\"]}", null)
				.put("example/FabricInit.class", plain("example/FabricInit", MAIN, true))
				.put("example/ForgeMod.class", forgeMod("example/ForgeMod", "tri"))
				.put("example/NeoMod.class", neoMod("example/NeoMod", "tri")));
		assertFalse(verdict.refused(), verdict.describe());
		assertEquals(List.of(Ecosystem.FABRIC, Ecosystem.NEOFORGE), verdict.usableFamilies());
	}

	@Test void aFabricModWithOptionalForgeCompatClassesIsNotTouched() throws Exception {
		LoaderHalfJudge.Verdict verdict = judge(jar()
				.fabricJson("compat", "{\"main\":[\"example.FabricInit\"]}", null)
				.put("example/FabricInit.class", plain("example/FabricInit", MAIN, true))
				.put("example/compat/ForgeCompat.class", referencing("example/compat/ForgeCompat",
						"net/minecraftforge/common/MinecraftForge")));
		assertFalse(verdict.refused(), verdict.describe());
		assertFalse(verdict.hasMinecraftForge());
		assertEquals(List.of(Ecosystem.FABRIC), verdict.usableFamilies());
	}

	@Test void aLibraryOnlyFabricManifestIsUsable() throws Exception {
		LoaderHalfJudge.Verdict verdict = judge(jar()
				.forgeToml("lib", "Lib").fabricJson("lib", null, null)
				.put("example/Library.class", plain("example/Library", null, true))
				.put("example/ForgeMod.class", forgeMod("example/ForgeMod", "lib")));
		assertFalse(verdict.refused(), verdict.describe());
		assertTrue(verdict.half(Ecosystem.FABRIC).orElseThrow().reason().startsWith("a library"), verdict.describe());
	}

	@Test void aForgeJarNestedInANeoForgeJarDoesNotRefuseTheNeoForgeMod() throws Exception {
		LoaderHalfJudge.Verdict verdict = judge(jar()
				.neoToml("outer", "Outer", null)
				.put("example/NeoMod.class", neoMod("example/NeoMod", "outer"))
				.put("META-INF/jarjar/forge-inner.jar", nestedForge().build()));
		assertFalse(verdict.refused(), verdict.describe());
		assertFalse(verdict.hasMinecraftForge(), "a nested jar's manifest is not the outer jar's half");
	}

	@Test void aForgeJarNestedInAFabricJarDoesNotRefuseTheFabricMod() throws Exception {
		LoaderHalfJudge.Verdict verdict = judge(jar()
				.fabricJson("outer", "{\"main\":[\"example.FabricInit\"]}", null)
				.put("example/FabricInit.class", plain("example/FabricInit", MAIN, true))
				.put("META-INF/jars/forge-inner.jar", nestedForge().build()));
		assertFalse(verdict.refused(), verdict.describe());
		assertFalse(verdict.hasMinecraftForge());
	}

	@Test void aNeoForgeModThatAlsoShipsAStaleModsTomlLoadsThroughNeoForge() throws Exception {
		LoaderHalfJudge.Verdict verdict = judge(jar()
				.neoToml("modern", "Modern", null).forgeToml("modern", "Modern")
				.put("example/NeoMod.class", neoMod("example/NeoMod", "modern")));
		assertFalse(verdict.refused(), verdict.describe());
		assertEquals(List.of(Ecosystem.NEOFORGE), verdict.usableFamilies());
	}

	@Test void anEntrypointOrModClassThatLivesInANestedJarStillExists() throws Exception {
		JarBuilder inner = jar().put("example/NeoMod.class", neoMod("example/NeoMod", "wrapped"))
				.put("example/FabricInit.class", plain("example/FabricInit", MAIN, true));
		LoaderHalfJudge.Verdict neo = judge(jar().forgeToml("wrapped", "W").neoToml("wrapped", "W", null)
				.put("META-INF/jarjar/core.jar", inner.build()));
		assertEquals(List.of(Ecosystem.NEOFORGE), neo.usableFamilies(), neo.describe());
		LoaderHalfJudge.Verdict fabric = judge(jar().forgeToml("wrapped", "W")
				.fabricJson("wrapped", "{\"main\":[\"example.FabricInit\"]}", null)
				.put("META-INF/jars/core.jar", inner.build()));
		assertEquals(List.of(Ecosystem.FABRIC), fabric.usableFamilies(), fabric.describe());
	}

	/**
	 * Multiloader jars over-declare a sibling loader's mixin config, and discovery drops such a config with a warning
	 * (ForbricModDiscovererTest.dropsDeclaredButAbsentMixinConfig). An absent config is named, and decides nothing:
	 * refusing over it would refuse a jar that loads today.
	 */
	@Test void anAbsentMixinConfigIsNamedButDecidesNothing() throws Exception {
		LoaderHalfJudge.Verdict verdict = judge(jar()
				.forgeToml("mx", "Mx").neoToml("mx", "Mx", "mx.neoforge.mixins.json")
				.fabricJson("mx", "{\"main\":[\"example.FabricInit\"]}", "mx.fabric.mixins.json")
				.put("example/FabricInit.class", plain("example/FabricInit", MAIN, true))
				.put("example/NeoMod.class", neoMod("example/NeoMod", "mx")));
		assertFalse(verdict.refused(), verdict.describe());
		assertEquals(List.of(Ecosystem.FABRIC, Ecosystem.NEOFORGE), verdict.usableFamilies(), verdict.describe());
		assertTrue(verdict.describe().contains("mx.fabric.mixins.json not in the jar"), verdict.describe());
		assertTrue(verdict.describe().contains("mx.neoforge.mixins.json not in the jar"), verdict.describe());
	}

	/**
	 * A Forge+NeoForge jar with no @Mod at all — a mixin, data or library mod — is judged by its entry, as a Fabric half
	 * is, and not by stray classes: optional MinecraftForge compatibility code NeoForge never loads does not make its
	 * NeoForge half fake. Native NeoForge loads this jar, and so did the kernel before MinecraftForge was dropped.
	 */
	@Test void aMixinOnlyNeoForgeHalfWithOptionalForgeCompatCodeIsKept() throws Exception {
		LoaderHalfJudge.Verdict verdict = judge(jar()
				.forgeToml("mx", "Mx").neoToml("mx", "Mx", "mx.mixins.json")
				.put("mx.mixins.json", "{}".getBytes(StandardCharsets.UTF_8))
				.put("example/mixin/Hook.class", plain("example/mixin/Hook", null, true))
				.put("example/compat/ForgeCompat.class", referencing("example/compat/ForgeCompat",
						"net/minecraftforge/common/MinecraftForge")));
		assertFalse(verdict.refused(), verdict.describe());
		assertEquals(List.of(Ecosystem.NEOFORGE), verdict.usableFamilies(), verdict.describe());
		assertTrue(verdict.half(Ecosystem.NEOFORGE).orElseThrow().reason().startsWith("no @Mod"), verdict.describe());

		// Even when the one class in it uses MinecraftForge: with no entry to judge, the half is NeoForge's to load.
		LoaderHalfJudge.Verdict single = judge(jar()
				.forgeToml("mx", "Mx").neoToml("mx", "Mx", null)
				.put("example/Helper.class", referencing("example/Helper", "net/minecraftforge/common/MinecraftForge")));
		assertFalse(single.refused(), single.describe());
	}

	/** But a MinecraftForge @Mod whose mixins hook MinecraftForge code is MinecraftForge's mod: refused. */
	@Test void aForgeModWhoseOtherCodeHooksMinecraftForgeIsRefused() throws Exception {
		LoaderHalfJudge.Verdict verdict = judge(jar()
				.forgeToml("mx", "Mx").neoToml("mx", "Mx", "mx.mixins.json")
				.put("mx.mixins.json", "{}".getBytes(StandardCharsets.UTF_8))
				.put("example/Mx.class", forgeMod("example/Mx", "mx"))
				.put("example/mixin/Hook.class", referencing("example/mixin/Hook", "net/minecraftforge/event/ForgeEventFactory")));
		assertTrue(verdict.refused(), verdict.describe());
	}

	/**
	 * fabric.mod.json is read by the parser the kernel's own Fabric discovery uses, which is strict JSON: a comment or a
	 * trailing comma makes the half unreadable here exactly as it is unreadable there, and the half says so — so the
	 * refusal can name the broken file instead of telling the player to fetch the Fabric build they may already have.
	 */
	@Test void aFabricManifestWithACommentIsUnreadableAndSaysSo() throws Exception {
		for (String json : List.of(
				"{\"schemaVersion\":1,\"id\":\"c\",\"version\":\"1\" // a comment\n}",
				"{\"schemaVersion\":1,\"id\":\"c\",\"version\":\"1\",}")) {
			LoaderHalfJudge.Verdict verdict = judge(jar()
					.forgeToml("c", "C")
					.put("fabric.mod.json", json.getBytes(StandardCharsets.UTF_8))
					.put("example/C.class", forgeMod("example/C", "c")));
			assertTrue(verdict.refused(), verdict.describe());
			LoaderHalfJudge.Half fabric = verdict.half(Ecosystem.FABRIC).orElseThrow();
			assertTrue(fabric.unreadable(), fabric.toString());
			assertEquals(List.of(fabric), verdict.unreadableHalves());
			assertThrows(RuntimeException.class, () -> com.electronwill.nightconfig.json.JsonFormat.fancyInstance()
					.createParser().parse(json), "the kernel's Fabric parser rejects it too");
		}
		// A half that reads but is fake is not "unreadable".
		LoaderHalfJudge.Verdict fake = judge(jar()
				.forgeToml("journeymap", "JourneyMap")
				.fabricJson("journeymap-wrongloader", "{\"preLaunch\":[\"example.FabricError\"]}", null)
				.put("example/FabricError.class", throwingEntrypoint("example/FabricError")));
		assertEquals(List.of(), fake.unreadableHalves());
	}

	@Test void anUnreadableManifestIsAReasonNotAnException() throws Exception {
		LoaderHalfJudge.Verdict verdict = judge(jar()
				.put("META-INF/mods.toml", "[[mods]\nmodId=".getBytes(StandardCharsets.UTF_8))
				.put("fabric.mod.json", "{\"schemaVersion\":1,".getBytes(StandardCharsets.UTF_8)));
		assertTrue(verdict.refused(), verdict.describe());
		assertTrue(verdict.half(Ecosystem.FABRIC).orElseThrow().reason().startsWith("fabric.mod.json cannot be read"),
				verdict.describe());
	}

	@Test void aJarWithNoManifestDeclaresNothingAndIsNotRefused() throws Exception {
		LoaderHalfJudge.Verdict verdict = judge(jar().put("example/Library.class", plain("example/Library", null, true)));
		assertEquals(List.of(), verdict.halves());
		assertFalse(verdict.refused());
	}

	// ----------------------------------------------------------------------------------------------- real jars

	@Test void theThreeRealMultiManifestForgeBuildsAreRefusedAndTheirRealSiblingsAreNot() throws Exception {
		Path popular = Path.of("run/client-popular/mods");
		Path owl = popular.resolve("animalgarden-owl-1.2.8-forge-26.2-65.0.0.jar");
		Path aquarius = popular.resolve("aquariuslibs-1.2.0-forge-26.2-65.0.0.jar");
		Path journeymap = popular.resolve("journeymap-forge-26.2-6.0.8.jar");
		TestFixtures.requireFiles(Fixture.THIRD_PARTY, "the popular pack's multi-manifest Forge builds", owl, aquarius, journeymap);
		for (Path jar : List.of(owl, aquarius, journeymap)) {
			LoaderHalfJudge.Verdict verdict = LoaderHalfJudge.judge(jar);
			assertTrue(verdict.refused(), jar.getFileName() + ": " + verdict.describe());
		}
		assertTrue(LoaderHalfJudge.judge(journeymap).half(Ecosystem.FABRIC).orElseThrow().reason()
				.contains("journeymap.common.FabricError.onPreLaunch does nothing but throw"));

		// The NeoForge builds of the same two mods, where the corpus has them, load: the rule is about the build.
		Path neoAquarius = Path.of("build/compat-inputs/mix80/mods/aquariuslibs-1.2.0-neoforge-26.2.0.2.jar");
		Path neoJourneymap = Path.of("run/client-merged-pack/mods/journeymap-neoforge-26.2-6.0.1.jar");
		TestFixtures.requireFiles(Fixture.THIRD_PARTY, "the NeoForge builds of aquariuslibs and JourneyMap",
				neoAquarius, neoJourneymap);
		for (Path jar : List.of(neoAquarius, neoJourneymap)) {
			LoaderHalfJudge.Verdict verdict = LoaderHalfJudge.judge(jar);
			assertFalse(verdict.refused(), jar.getFileName() + ": " + verdict.describe());
		}
	}

	@Test void realDatapackWrappersAndSimpleRpcAreNotRefused() throws Exception {
		Path wrapper = Path.of("build/compat-inputs/mix80/mods/glowing-glow-squids-3.jar");
		Path rpc = Path.of("build/compat-inputs/random/mods/SimpleRPC-4.1.4.jar");
		TestFixtures.requireFiles(Fixture.THIRD_PARTY, "a Modrinth datapack wrapper and SimpleRPC", wrapper, rpc);
		for (Path jar : List.of(wrapper, rpc)) {
			LoaderHalfJudge.Verdict verdict = LoaderHalfJudge.judge(jar);
			assertFalse(verdict.refused(), jar.getFileName() + ": " + verdict.describe());
			assertTrue(verdict.usableFamilies().contains(Ecosystem.NEOFORGE), jar.getFileName() + ": " + verdict.describe());
		}
	}

	// ----------------------------------------------------------------------------------------------- helpers

	private static LoaderHalfJudge.Verdict judge(JarBuilder jar) throws Exception {
		return judge(jar.build());
	}

	private static LoaderHalfJudge.Verdict judge(byte[] jar) throws Exception {
		return LoaderHalfJudge.judge("fixture.jar", jar);
	}

	private static JarBuilder nestedForge() {
		return jar().forgeToml("inner", "Inner").put("inner/InnerMod.class", forgeMod("inner/InnerMod", "inner"));
	}

	static JarBuilder jar() {
		return new JarBuilder();
	}

	static final class JarBuilder {
		private final Map<String, byte[]> entries = new LinkedHashMap<>();

		JarBuilder put(String name, byte[] bytes) {
			entries.put(name, bytes);
			return this;
		}

		JarBuilder forgeToml(String id, String name) {
			return put("META-INF/mods.toml", toml(id, name, "forge", null));
		}

		JarBuilder neoToml(String id, String name, String mixins) {
			return put("META-INF/neoforge.mods.toml", toml(id, name, "neoforge", mixins));
		}

		JarBuilder fabricJson(String id, String entrypoints, String mixins) {
			return put("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1\",\"name\":\"" + id
					+ "\"" + (entrypoints == null ? "" : ",\"entrypoints\":" + entrypoints)
					+ (mixins == null ? "" : ",\"mixins\":[\"" + mixins + "\"]") + "}").getBytes(StandardCharsets.UTF_8));
		}

		byte[] build() throws Exception {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (JarOutputStream out = new JarOutputStream(bytes)) {
				for (var entry : entries.entrySet()) {
					out.putNextEntry(new JarEntry(entry.getKey()));
					out.write(entry.getValue());
					out.closeEntry();
				}
			}
			return bytes.toByteArray();
		}

		Path write(Path file) throws Exception {
			Files.write(file, build());
			return file;
		}

		private static byte[] toml(String id, String name, String loader, String mixins) {
			return ("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + id
					+ "\"\nversion=\"1\"\ndisplayName=\"" + name + "\"\n"
					+ (mixins == null ? "" : "[[mixins]]\nconfig=\"" + mixins + "\"\n")
					+ "[[dependencies." + id + "]]\nmodId=\"" + loader + "\"\ntype=\"required\"\nversionRange=\"[1,)\"\n")
					.getBytes(StandardCharsets.UTF_8);
		}
	}

	static byte[] forgeMod(String name, String id) {
		return annotated(name, FORGE_MOD, id, "(Lnet/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext;)V");
	}

	static byte[] neoMod(String name, String id) {
		return annotated(name, NEO_MOD, id, "(Lnet/neoforged/bus/api/IEventBus;)V");
	}

	private static byte[] annotated(String name, String annotation, String id, String constructor) {
		ClassWriter out = new ClassWriter(0);
		out.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		var mod = out.visitAnnotation(annotation, true);
		mod.visit("value", id);
		mod.visitEnd();
		constructor(out, constructor);
		out.visitEnd();
		return out.toByteArray();
	}

	/** A public class with a public constructor, optionally implementing {@code implemented} with a real body. */
	static byte[] plain(String name, String implemented, boolean noArg) {
		ClassWriter out = new ClassWriter(0);
		out.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object",
				implemented == null ? null : new String[] {implemented});
		constructor(out, noArg ? "()V" : "(Ljava/lang/String;)V");
		if (MAIN.equals(implemented)) {
			var init = out.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "()V", null, null);
			init.visitCode();
			init.visitInsn(Opcodes.RETURN);
			init.visitMaxs(0, 1);
			init.visitEnd();
		}
		out.visitEnd();
		return out.toByteArray();
	}

	/** FabricError's shape: a PreLaunchEntrypoint whose onPreLaunch only throws. */
	private static byte[] throwingEntrypoint(String name) {
		ClassWriter out = new ClassWriter(0);
		out.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object",
				new String[] {"net/fabricmc/loader/api/entrypoint/PreLaunchEntrypoint"});
		constructor(out, "()V");
		var method = out.visitMethod(Opcodes.ACC_PUBLIC, "onPreLaunch", "()V", null, null);
		method.visitCode();
		method.visitTypeInsn(Opcodes.NEW, "java/lang/UnsupportedOperationException");
		method.visitInsn(Opcodes.DUP);
		method.visitLdcInsn("User Attempting to load this Forge mod in Fabric Loader");
		method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>",
				"(Ljava/lang/String;)V", false);
		method.visitInsn(Opcodes.ATHROW);
		method.visitMaxs(3, 1);
		method.visitEnd();
		out.visitEnd();
		return out.toByteArray();
	}

	/** A class whose constant pool names {@code owner} — what "its code links against" means here. */
	private static byte[] referencing(String name, String owner) {
		ClassWriter out = new ClassWriter(0);
		out.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		constructor(out, "()V");
		var method = out.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "touch", "()V", null, null);
		method.visitCode();
		method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "touch", "()V", false);
		method.visitInsn(Opcodes.RETURN);
		method.visitMaxs(0, 0);
		method.visitEnd();
		out.visitEnd();
		return out.toByteArray();
	}

	private static void constructor(ClassWriter out, String descriptor) {
		var ctor = out.visitMethod(Opcodes.ACC_PUBLIC, "<init>", descriptor, null, null);
		ctor.visitCode();
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(1, 2);
		ctor.visitEnd();
	}
}
