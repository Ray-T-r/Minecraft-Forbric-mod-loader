package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.forbric.api.Ecosystem;

/**
 * The carrier-helper rules over the REAL mixins of the sweep90 pack and the REAL merged base: the mods that paid for
 * them, and fabric-rendering-v1's HudMixin, whose four R3 moves out of the same dispatcher must not change.
 */
class MixinRetargetCarrierHelperStagedTest {
	private static final Path MERGED_BASE = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run",
			"merged-base", "patched-mc-merged-26.2.jar").normalize();
	private static final Path SWEEP = Path.of(System.getProperty("user.dir"), "build", "compat-inputs", "sweep90", "mods").normalize();
	private static final String G = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V";

	@AfterEach
	void reset() {
		System.clearProperty(MixinRetarget.SPLIT_PROPERTY);
		MixinRetarget.reset();
		MixinStubRebind.forget();
	}

	/** Better Mount HUD's hunger-bar redirect: PARTIAL on the dispatcher, FIT on extractFoodLevel. */
	@Test
	void betterMountHudsFoodRedirectMovesToExtractFoodLevel() throws Exception {
		Function<String, byte[]> resolver = mergedResolver();
		String entry = "me/lortseam/bettermounthud/mixin/HudMixin";
		byte[] mixin = fromJar(SWEEP.resolve("bettermounthud-1.3.1.jar"), entry + ".class");
		MixinStubRebind.noteEcosystem(entry, Ecosystem.FABRIC);
		MixinFit.Result before = MixinFit.evaluate(mixin, resolver);
		assertEquals(MixinFit.Verdict.PARTIAL, before.verdict(), "premise: " + before.unresolved());

		MixinRetarget.Plan plan = MixinRetarget.plan(MixinFit.parse(mixin), resolver);
		assertEquals(1, plan.rewrites().size(), plan.describe());
		assertEquals("bettermounthud$alwaysRenderFood", plan.rewrites().get(0).handler());
		assertEquals("extractPlayerHealth", plan.rewrites().get(0).from());
		assertEquals("extractFoodLevel" + G, plan.rewrites().get(0).to());
		MixinFit.Result after = MixinFit.evaluate(MixinRetarget.rewritten(mixin, plan), resolver);
		assertEquals(MixinFit.Verdict.FIT, after.verdict(), "after: " + after.unresolved());
	}

	/** fabric-rendering-v1's HudMixin: each anchor has one home, so R3 moves it and R4 never runs. */
	@Test
	void fabricRenderingsHudMixinPlanIsTheSameWithTheSplitRuleOnOrOff() throws Exception {
		Function<String, byte[]> resolver = mergedResolver();
		String entry = "net/fabricmc/fabric/mixin/client/rendering/HudMixin";
		byte[] mixin = nested(SWEEP.resolve("fabric-api-0.161.0+26.2.jar"), "fabric-rendering-v1", entry + ".class");
		MixinStubRebind.noteEcosystem(entry, Ecosystem.FABRIC);
		String on = MixinRetarget.plan(MixinFit.parse(mixin), resolver).describe();
		System.setProperty(MixinRetarget.SPLIT_PROPERTY, "off");
		String off = MixinRetarget.plan(MixinFit.parse(mixin), resolver).describe();
		assertFalse(on.isEmpty(), "premise: R3 moves this mixin's anchors out of extractPlayerHealth");
		assertEquals(off, on);
	}

	// ---------------------------------------------------------------------------------------------------------------

	static Function<String, byte[]> mergedResolver() {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		return name -> {
			try {
				return readFromJar(MERGED_BASE, name);
			} catch (Exception e) {
				return null;
			}
		};
	}

	static byte[] fromJar(Path jar, String entry) throws Exception {
		assumeTrue(Files.isRegularFile(jar), jar + " absent (symlink build/compat-inputs from the main checkout)");
		byte[] bytes = readFromJar(jar, entry);
		assumeTrue(bytes != null, entry + " absent from " + jar.getFileName());
		return bytes;
	}

	static byte[] nested(Path outer, String modulePrefix, String entry) throws Exception {
		assumeTrue(Files.isRegularFile(outer), outer + " absent (symlink build/compat-inputs from the main checkout)");
		try (ZipFile zip = new ZipFile(outer.toFile())) {
			for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
				ZipEntry nested = e.nextElement();
				if (!nested.getName().startsWith("META-INF/jars/" + modulePrefix)) continue;
				Path tmp = Files.createTempFile("forbric-nested", ".jar");
				try (InputStream in = zip.getInputStream(nested)) {
					Files.write(tmp, in.readAllBytes());
				}
				try {
					byte[] bytes = readFromJar(tmp, entry);
					if (bytes != null) return bytes;
				} finally {
					Files.deleteIfExists(tmp);
				}
			}
		}
		assumeTrue(false, entry + " absent from the nested " + modulePrefix);
		return null;
	}

	private static byte[] readFromJar(Path jar, String entry) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			if (found == null) return null;
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}
}
