package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.discovery.MetadataFailures;
import net.forbric.kernel.ui.CompatibilityDecision;
import net.forbric.kernel.ui.DialogLang;

/**
 * The user-visible half of dropping MinecraftForge: a MinecraftForge-only jar in {@code mods/} is refused with a
 * reason, end to end — no mod of it is discovered, it is a Lost row and a confirmed required finding, a strict launch or
 * a dedicated server stops on it, and the load report says what to get instead, in Chinese and in English. A
 * Forge+Fabric universal jar beside it is not refused: it loads through Fabric.
 *
 * <p>Red-checked: with the refusal in {@code ForbricModDiscoverer.parseJar} removed the Forge-only jar was discovered as a
 * MinecraftForge mod and every assertion on the finding failed; with {@code recordRefusal}'s {@code installed} forced
 * false the finding was a mere suspicion and both stops returned true.
 */
@ResourceLock("ModCatalog")
@ResourceLock("system-properties")
class MinecraftForgeRefusalTest {
	@TempDir Path temporary;
	private Path mods;
	private Path forgeOnly;
	private Path universal;
	private Path neo;

	@BeforeEach @AfterEach void reset() {
		MetadataFailures.reset();
		MultiLoaderArbiter.reset();
		CompatibilityFindings.reset();
		CompatibilityDecision.reset();
		ModCatalog.publish(List.of());
		KernelLoadReport.reset();
		System.clearProperty(DialogLang.SWITCH);
		System.clearProperty(CompatibilityDecision.PROPERTY);
	}

	@BeforeEach void install() throws Exception {
		mods = Files.createDirectories(temporary.resolve("mods"));
		forgeOnly = jar(mods.resolve("onlyforge-1.0-forge.jar"), new String[][] {
				{"META-INF/mods.toml", toml("onlyforge", "Only Forge", "forge")}},
				"example/OnlyForge.class", mod("example/OnlyForge", "Lnet/minecraftforge/fml/common/Mod;", "onlyforge"));
		universal = jar(mods.resolve("arch-1.0.jar"), new String[][] {
				{"META-INF/mods.toml", toml("arch", "Arch", "forge")},
				{"fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"arch\",\"version\":\"1\",\"entrypoints\":{\"main\":[\"example.ArchFabric\"]}}"}},
				"example/ArchForge.class", mod("example/ArchForge", "Lnet/minecraftforge/fml/common/Mod;", "arch"),
				"example/ArchFabric.class", fabricInit("example/ArchFabric"));
		neo = jar(mods.resolve("goodneo-1.0.jar"), new String[][] {
				{"META-INF/neoforge.mods.toml", toml("goodneo", "Good Neo", "neoforge")}},
				"example/GoodNeo.class", mod("example/GoodNeo", "Lnet/neoforged/fml/common/Mod;", "goodneo"));
	}

	@Test void aMinecraftForgeOnlyJarIsRefusedAndAUniversalOneLoadsAsFabric() throws Exception {
		// Discovery: nothing of the refused jar, and the universal jar is not a Forge-family jar any more.
		KernelBoot.ForgeFamilyMods found = KernelBoot.discoverForgeFamilyModJars(mods, DuplicateModArbiter.Decision.none());
		assertEquals(List.of(neo), found.jars());
		assertEquals(List.of(), new ForbricModDiscoverer().discoverJar(forgeOnly));
		assertTrue(MetadataFailures.unsupported(forgeOnly));
		assertFalse(MetadataFailures.unsupported(universal));

		// Arbitration: refused is FORGE (never null), suppressed for everyone; the universal jar is Fabric's.
		assertEquals(Ecosystem.FORGE, MultiLoaderArbiter.ownerOf(forgeOnly));
		assertTrue(MultiLoaderArbiter.refused(forgeOnly));
		assertTrue(MultiLoaderArbiter.suppressedFor(forgeOnly, Ecosystem.FORGE));
		assertEquals(Ecosystem.FABRIC, MultiLoaderArbiter.ownerOf(universal));
		assertFalse(MultiLoaderArbiter.suppressedFor(universal, Ecosystem.FABRIC));
		assertNull(DuplicateModArbiter.claimOf(new ForbricModDiscoverer(), forgeOnly, null, new java.util.ArrayList<>()),
				"a refused jar claims no id");

		// The finding: confirmed, required, on the mod's own id, naming the mod, in the player's language.
		System.setProperty(DialogLang.SWITCH, "en");
		MetadataFailures.recordFindings(MultiLoaderArbiter::ownerOf, List.of(), mods, jar -> false);
		List<CompatibilityFinding> required = CompatibilityFindings.confirmedRequired();
		assertEquals(List.of("onlyforge:" + MetadataFailures.UNSUPPORTED_FINDING), required.stream().map(CompatibilityFinding::key).toList());
		String detail = required.get(0).detail();
		assertTrue(detail.contains("Only Forge (onlyforge-1.0-forge.jar) is a MinecraftForge mod"), detail);
		assertTrue(detail.contains("no longer supports MinecraftForge"), detail);
		assertTrue(detail.contains("NeoForge or Fabric build of Only Forge"), detail);
		assertTrue(required.get(0).evidence().contains("unsupported=minecraftforge"), required.get(0).evidence().toString());

		// The Lost row the Mods screen and the load report read.
		KernelModCatalog.publish(new ForbricModDiscoverer().discoverJar(neo), mods);
		ModCatalog.Entry row = ModCatalog.all().stream().filter(e -> e.modId().equals("onlyforge")).findFirst()
				.orElseThrow(() -> new AssertionError("no row: " + ModCatalog.all()));
		assertEquals(ModCatalog.Status.FAILED, row.status());
		assertEquals("Only Forge", row.name());
		assertEquals(Ecosystem.FORGE, row.ecosystem());
		assertEquals(List.of(), CompatibilityFindings.unattributed(), "it belongs to the row of the mod the player installed");

		// The decision: a strict launch and a dedicated server stop.
		System.setProperty(CompatibilityDecision.PROPERTY, "strict");
		assertFalse(CompatibilityDecision.check(true), "strict stops, client or not");
		System.setProperty(CompatibilityDecision.PROPERTY, "ask");
		assertFalse(CompatibilityDecision.check(false), "a dedicated server has nobody to ask and stops");
		assertThrows(CompatibilityDecision.LaunchStopped.class, () -> CompatibilityDecision.requireContinuation(false));
		System.clearProperty(CompatibilityDecision.PROPERTY);

		// The load report, in both languages.
		Set<String> refused = Set.of("onlyforge-1.0-forge.jar");
		String en = KernelLoadReport.render(false, ModCatalog.failures(), CompatibilityFindings.unattributed(), List.of(),
				List.of(), refused);
		assertTrue(en.contains("Only Forge  (onlyforge)"), en);
		assertTrue(en.contains("not loaded: Forbric no longer supports MinecraftForge"), en);
		assertTrue(en.contains("Get the NeoForge or Fabric build of Only Forge and put it in the mods folder in place of "
				+ "onlyforge-1.0-forge.jar."), en);
		assertTrue(en.contains("Forbric now loads Fabric and NeoForge mods only."), en);
		assertFalse(en.contains("their classes did load"), "the general advice is not true of a refused mod: " + en);
		String zh = KernelLoadReport.render(true, ModCatalog.failures(), CompatibilityFindings.unattributed(), List.of(),
				List.of(), refused);
		assertTrue(zh.contains("没有加载：Forbric 已不再支持 MinecraftForge"), zh);
		assertTrue(zh.contains("请下载 Only Forge 的 NeoForge 或 Fabric 版本，放进 mods 文件夹替换 onlyforge-1.0-forge.jar。"), zh);
		assertTrue(zh.contains("Forbric 现在只加载 Fabric 和 NeoForge mod。"), zh);

		// And the writer finds the refusal by itself, from the Lost rows.
		Path file = temporary.resolve("load-report.txt");
		Locale before = Locale.getDefault();
		try {
			Locale.setDefault(Locale.ENGLISH);
			KernelLoadReport.writeTo(file);
		} finally {
			Locale.setDefault(before);
		}
		assertTrue(Files.readString(file).contains("not loaded: Forbric no longer supports MinecraftForge"), Files.readString(file));
	}

	@Test void theFindingSpeaksChineseToAChinesePlayer() throws Exception {
		new ForbricModDiscoverer().discoverJar(forgeOnly);
		System.setProperty(DialogLang.SWITCH, "zh_cn");
		MetadataFailures.recordFindings(MultiLoaderArbiter::ownerOf, List.of(), mods, jar -> false);
		CompatibilityFinding finding = CompatibilityFindings.confirmedRequired().get(0);
		assertEquals("Only Forge（onlyforge-1.0-forge.jar）是 MinecraftForge mod。Forbric 已不再支持 MinecraftForge，所以它不会被加载。"
				+ "请改用 Only Forge 的 NeoForge 或 Fabric 版本。", finding.detail());
	}

	@Test void aJarThePlayerSwitchedOffOrAnotherModBundlesStopsNothing() throws Exception {
		new ForbricModDiscoverer().discoverJar(forgeOnly);
		MetadataFailures.recordFindings(MultiLoaderArbiter::ownerOf, List.of(), mods, forgeOnly::equals);
		assertEquals(List.of(), CompatibilityFindings.all(), "switched off in forbric-disabled.txt: nothing to ask about");
		assertEquals(List.of(), MetadataFailures.lost());

		// The same jar where a parent's JarJar put it: a note, and no Lost row.
		Path bundled = Files.copy(forgeOnly, Files.createDirectories(temporary.resolve("candidates")).resolve("inner.jar"));
		new ForbricModDiscoverer().discoverJar(bundled);
		MetadataFailures.recordFindings(MultiLoaderArbiter::ownerOf, List.of(), mods, forgeOnly::equals);
		assertEquals(List.of(), CompatibilityFindings.confirmedRequired());
		assertEquals(1, CompatibilityFindings.suspected().size(), CompatibilityFindings.all().toString());
		assertEquals(List.of(), MetadataFailures.lost());
	}

	@Test void minecraftForgeInThePreferenceIsIgnored() throws Exception {
		System.setProperty("forbric.multiLoaderPreference", "minecraftforge,fabric");
		try {
			assertEquals(List.of(Ecosystem.FABRIC), MultiLoaderArbiter.preference());
			System.setProperty("forbric.multiLoaderPreference", "minecraftforge");
			assertEquals(List.of(Ecosystem.NEOFORGE, Ecosystem.FABRIC), MultiLoaderArbiter.preference(),
					"nothing left: the default order");
		} finally {
			System.clearProperty("forbric.multiLoaderPreference");
		}
	}

	// --------------------------------------------------------------------------------------------------------------

	private static String toml(String id, String name, String loader) {
		return "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + id + "\"\nversion=\"1\"\n"
				+ "displayName=\"" + name + "\"\n[[dependencies." + id + "]]\nmodId=\"" + loader
				+ "\"\ntype=\"required\"\nversionRange=\"[1,)\"\n";
	}

	private static Path jar(Path path, String[][] texts, Object... classes) throws Exception {
		try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(path))) {
			for (String[] text : texts) {
				out.putNextEntry(new java.util.jar.JarEntry(text[0]));
				out.write(text[1].getBytes(StandardCharsets.UTF_8));
				out.closeEntry();
			}
			for (int i = 0; i < classes.length; i += 2) {
				out.putNextEntry(new java.util.jar.JarEntry((String) classes[i]));
				out.write((byte[]) classes[i + 1]);
				out.closeEntry();
			}
		}
		return path;
	}

	private static byte[] mod(String name, String annotation, String id) {
		ClassWriter out = new ClassWriter(0);
		out.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		var mod = out.visitAnnotation(annotation, true);
		mod.visit("value", id);
		mod.visitEnd();
		out.visitEnd();
		return out.toByteArray();
	}

	private static byte[] fabricInit(String name) {
		ClassWriter out = new ClassWriter(0);
		out.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", new String[] {"net/fabricmc/api/ModInitializer"});
		var ctor = out.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		ctor.visitCode();
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(1, 1);
		ctor.visitEnd();
		var init = out.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "()V", null, null);
		init.visitCode();
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 1);
		init.visitEnd();
		out.visitEnd();
		return out.toByteArray();
	}
}
