/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import net.forbric.kernel.access.ClassTweakerTransformer;

/**
 * The real jars the creative pager tests read: fabric-api's creative-tab module (the interface, the pinned mixin, the
 * class tweaker that injects the interface) and owo-lib (the mixin that crashed).
 *
 * <p>Found beside this checkout first, then beside the staged tree's checkout — the mod fixtures live in ignored
 * directories a second working tree does not have. Absent everywhere, the test is skipped, never failed.
 */
public final class CreativePagerFixtures {
	public static final String API_JAR = "fabric-api-0.155.2+26.2.jar";
	public static final String OWO_JAR = "owo-lib-0.13.1+26.2.jar";
	public static final String CREATIVE_MODULE = "META-INF/jars/fabric-creative-tab-api-v1-";
	public static final String INTERFACE = CreativePagerBridgeInjector.API;
	public static final String PINNED_MIXIN = "net/fabricmc/fabric/mixin/creativetab/client/CreativeModeInventoryScreenMixin";
	public static final String OWO_MIXIN = "io/wispforest/owo/mixin/itemgroup/MixinCreativeModeInventoryScreenMixin";

	private CreativePagerFixtures() {
	}

	/** {@code relative} under this checkout's forbric-kernel, else under the staged tree's checkout; skipped if neither. */
	public static Path find(String relative) {
		Path here = Path.of(relative);
		if (Files.isRegularFile(here)) return here;
		Path staged = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run")).toAbsolutePath().normalize();
		Path checkout = staged.getParent() == null ? null : staged.getParent().getParent();
		Path there = checkout == null ? here : checkout.resolve("forbric-kernel").resolve(relative);
		assumeTrue(Files.isRegularFile(there), relative + " is not present beside this checkout or the staged one");
		return there;
	}

	public static Path fabricApi() {
		return find("run/client-merged-pack/mods/" + API_JAR);
	}

	public static Path owo() {
		return find("build/compat-inputs/sweep90/mods/" + OWO_JAR);
	}

	/** One entry of fabric-api's nested creative-tab module. */
	public static byte[] creativeModule(String entry) throws Exception {
		try (ZipFile api = new ZipFile(fabricApi().toFile())) {
			ZipEntry nested = api.stream().filter(e -> e.getName().startsWith(CREATIVE_MODULE) && e.getName().endsWith(".jar"))
					.findFirst().orElseThrow(() -> new AssertionError("fabric-api nests no creative-tab module"));
			try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(api.getInputStream(nested).readAllBytes()))) {
				for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
					if (e.getName().equals(entry)) return in.readAllBytes();
				}
			}
		}
		throw new AssertionError(entry + " is not in fabric-api's creative-tab module");
	}

	public static byte[] owoClass(String internalName) throws Exception {
		try (ZipFile owo = new ZipFile(owo().toFile())) {
			ZipEntry entry = owo.getEntry(internalName + ".class");
			if (entry == null) throw new AssertionError(internalName + " is not in " + OWO_JAR);
			return owo.getInputStream(entry).readAllBytes();
		}
	}

	/** What the ACCESS phase does to the screen on any client with fabric-api: the module's own class tweaker. */
	public static byte[] classTweaked(String binaryName, byte[] bytes) throws Exception {
		byte[] tweaker = creativeModule("fabric-creative-tab-api-v1.classtweaker");
		ClassTweakerTransformer access = ClassTweakerTransformer.create(List.of(tweaker), (name, generated) -> { });
		return access.transform(binaryName, bytes, null);
	}
}
