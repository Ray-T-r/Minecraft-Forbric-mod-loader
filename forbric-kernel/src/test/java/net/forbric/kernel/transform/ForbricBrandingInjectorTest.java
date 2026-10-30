/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.util.ForbricBranding;

/**
 * NeoForge's real BrandingControl and the merged base's real F3 version line name Forbric's release.
 *
 * <p>Those two read staged bytes. {@link #theEditedLinesReadForbricAndCountEveryInstalledMod} runs the edits on
 * stand-ins compiled here with the call sequences the edits key on, against the real {@link ForbricBranding}.
 */
@ExecutesInjector(ForbricBrandingInjector.class)
@ResourceLock("system-properties")
@ResourceLock("ModCatalog")
class ForbricBrandingInjectorTest {
	private static final Path STAGED = TestFixtures.stagedRoot();
	private static final Path MERGED = STAGED.resolve(TestFixtures.BASE_JAR);
	private static final Path NEOFORGE_RUNTIME = STAGED.resolve(TestFixtures.NEO_CARRIER_JAR);

	/** NeoForge's own line counts NeoForge's ModList (one mod here); F3 shows the launcher profile and the sent brand. */
	private static final Map<String, String> STAND_INS = Map.of(
			"net.neoforged.fml.ModList", """
					package net.neoforged.fml;

					public class ModList {
						public static ModList get() {
							return new ModList();
						}

						public int size() {
							return 1;
						}
					}
					""",
			"net.neoforged.neoforge.common.NeoForgeVersion", """
					package net.neoforged.neoforge.common;

					public class NeoForgeVersion {
						public static String getVersion() {
							return "26.2.0.88";
						}
					}
					""",
			"net.neoforged.neoforge.internal.BrandingControl", """
					package net.neoforged.neoforge.internal;

					import java.util.List;
					import net.neoforged.fml.ModList;
					import net.neoforged.neoforge.common.NeoForgeVersion;

					public final class BrandingControl {
						private static String minecraft = "26.2";
						private static List<String> brandings;

						private static void computeBranding() {
							if (brandings == null) {
								String loader = "NeoForge " + NeoForgeVersion.getVersion();
								brandings = List.of("Minecraft " + minecraft, loader + " (" + ModList.get().size() + " mods loaded)");
							}
						}

						public static List<String> getBrandings() {
							computeBranding();
							return brandings;
						}
					}
					""",
			"net.minecraft.client.Minecraft", """
					package net.minecraft.client;

					public class Minecraft {
						public static Minecraft getInstance() {
							return new Minecraft();
						}

						public String getLaunchedVersion() {
							return "26.2-forbric";
						}
					}
					""",
			"net.minecraft.client.ClientBrandRetriever", """
					package net.minecraft.client;

					public class ClientBrandRetriever {
						public static String getClientModName() {
							return "neoforge";
						}
					}
					""",
			"net.minecraft.client.gui.components.debug.DebugEntryVersion", """
					package net.minecraft.client.gui.components.debug;

					import java.util.List;
					import net.minecraft.client.ClientBrandRetriever;
					import net.minecraft.client.Minecraft;

					public class DebugEntryVersion {
						public void display(List<String> lines) {
							lines.add("Minecraft 26.2 (" + Minecraft.getInstance().getLaunchedVersion() + "/"
									+ ClientBrandRetriever.getClientModName() + ")");
						}
					}
					""");

	@AfterEach void reset() { System.clearProperty(ForbricBrandingInjector.PROPERTY); }

	/**
	 * Three mods installed, one per ecosystem, one of them carrying a fourth inside its jar: the title screen counts
	 * the three a player chose, not NeoForge's one, and both lines name Forbric's release where NeoForge's stood.
	 */
	@Test void theEditedLinesReadForbricAndCountEveryInstalledMod(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		Map<String, byte[]> classes = new HashMap<>(original);
		for (String target : List.of(ForbricBrandingInjector.BRANDING_CONTROL, ForbricBrandingInjector.DEBUG_VERSION)) {
			String internal = target.replace('.', '/');
			byte[] out = InjectorExecution.transform(new ForbricBrandingInjector(), target, original.get(internal), EnvType.CLIENT);
			assertNotSame(original.get(internal), out, target);
			classes.put(internal, out);
		}
		ClassLoader loader = InjectorExecution.load(classes);
		for (String target : List.of(ForbricBrandingInjector.BRANDING_CONTROL, ForbricBrandingInjector.DEBUG_VERSION)) {
			assertEquals("", InjectorExecution.verify(classes.get(target.replace('.', '/')), loader), target);
		}

		List<ModCatalog.Entry> previous = ModCatalog.everything();
		ModCatalog.publish(List.of(mod(Ecosystem.FABRIC, "sodium", ""), mod(Ecosystem.NEOFORGE, "create", ""),
				mod(Ecosystem.FORGE, "jei", ""), mod(Ecosystem.FABRIC, "fabric-api-base", "sodium")));
		try {
			assertEquals(List.of("Minecraft 26.2", ForbricBranding.display() + " (3 mods loaded)"), InjectorExecution.invokeStatic(
					loader.loadClass(ForbricBrandingInjector.BRANDING_CONTROL), "getBrandings"));
		} finally {
			ModCatalog.publish(previous);
		}
		assertEquals(List.of("Minecraft 26.2 (" + ForbricBranding.display() + "/forbric)"), f3(loader));

		ClassLoader merged = InjectorExecution.load(original);
		assertEquals(List.of("Minecraft 26.2", "NeoForge 26.2.0.88 (1 mods loaded)"), InjectorExecution.invokeStatic(
				merged.loadClass(ForbricBrandingInjector.BRANDING_CONTROL), "getBrandings"), "premise: the merged lines");
		assertEquals(List.of("Minecraft 26.2 (26.2-forbric/neoforge)"), f3(merged));
	}

	@Test void switchedOffTheStandInsAreLeftAsMerged(@TempDir Path work) throws Exception {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		System.setProperty(ForbricBrandingInjector.PROPERTY, "off");
		for (String target : List.of(ForbricBrandingInjector.BRANDING_CONTROL, ForbricBrandingInjector.DEBUG_VERSION)) {
			byte[] bytes = original.get(target.replace('.', '/'));
			assertSame(bytes, InjectorExecution.transform(new ForbricBrandingInjector(), target, bytes, EnvType.CLIENT), target);
		}
	}

	private static List<String> f3(ClassLoader loader) throws Throwable {
		List<String> lines = new ArrayList<>();
		InjectorExecution.invoke(InjectorExecution.construct(loader.loadClass(ForbricBrandingInjector.DEBUG_VERSION)), "display", lines);
		return lines;
	}

	private static ModCatalog.Entry mod(Ecosystem ecosystem, String id, String bundledBy) {
		return new ModCatalog.Entry(ecosystem, id, id, "1", "", List.of(), id + ".jar", "", bundledBy);
	}

	@Test void theTitleScreenNamesForbricAndCountsEveryInstalledMod() throws Exception {
		TestFixtures.requireFile(Fixture.STAGED, NEOFORGE_RUNTIME, "neoforge runtime not staged: " + NEOFORGE_RUNTIME);
		String internal = ForbricBrandingInjector.BRANDING_CONTROL.replace('.', '/');
		byte[] original = NativeCoremodParityTest.read(NEOFORGE_RUNTIME, internal);
		byte[] out = new ForbricBrandingInjector().transform(ForbricBrandingInjector.BRANDING_CONTROL, original, null);
		assertNotSame(original, out);
		MethodNode compute = method(out, "computeBranding");
		List<String> calls = calls(compute);
		assertTrue(calls.contains(ForbricBrandingInjector.BRANDING + ".installedModCount"), calls::toString);
		assertTrue(calls.contains(ForbricBrandingInjector.BRANDING + ".display"), calls::toString);
		assertFalse(calls.stream().anyMatch(c -> c.endsWith("ModList.get") || c.endsWith("NeoForgeVersion.getVersion")), calls::toString);
		assertEquals(1, indys(compute), "only the \"Minecraft \" concat is left");
		new Analyzer<>(new BasicVerifier()).analyze(internal, compute);
		assertSame(out, new ForbricBrandingInjector().transform(ForbricBrandingInjector.BRANDING_CONTROL, out, null), "a second pass adds nothing");
		System.setProperty(ForbricBrandingInjector.PROPERTY, "off");
		assertSame(original, new ForbricBrandingInjector().transform(ForbricBrandingInjector.BRANDING_CONTROL, original, null));
	}

	@Test void f3ShowsForbricsReleaseAndBrand() throws Exception {
		TestFixtures.requireFile(Fixture.STAGED, MERGED, "merged base not staged: " + MERGED);
		String internal = ForbricBrandingInjector.DEBUG_VERSION.replace('.', '/');
		byte[] original = NativeCoremodParityTest.read(MERGED, internal);
		byte[] out = new ForbricBrandingInjector().transform(ForbricBrandingInjector.DEBUG_VERSION, original, null);
		assertNotSame(original, out);
		MethodNode display = method(out, "display");
		List<String> calls = calls(display);
		assertEquals(List.of("net/minecraft/SharedConstants.getCurrentVersion", "net/minecraft/WorldVersion.name",
				ForbricBrandingInjector.BRANDING + ".display", ForbricBrandingInjector.BRANDING + ".brand",
				"net/minecraft/client/gui/components/debug/DebugScreenDisplayer.addPriorityLine"), calls);
		new Analyzer<>(new BasicVerifier()).analyze(internal, display);
		assertSame(out, new ForbricBrandingInjector().transform(ForbricBrandingInjector.DEBUG_VERSION, out, null));
		assertSame(original, new ForbricBrandingInjector().transform("net.minecraft.client.gui.screens.TitleScreen", original, null));
	}

	private static MethodNode method(byte[] bytes, String name) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
	}

	private static List<String> calls(MethodNode method) {
		List<String> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode call) out.add(call.owner + "." + call.name);
		return out;
	}

	private static int indys(MethodNode method) {
		int n = 0;
		for (AbstractInsnNode insn : method.instructions) if (insn instanceof InvokeDynamicInsnNode) n++;
		return n;
	}
}
