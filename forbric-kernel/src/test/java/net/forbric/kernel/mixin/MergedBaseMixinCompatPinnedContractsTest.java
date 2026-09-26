/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import net.forbric.kernel.transform.CreativePagerFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * {@link MergedBaseMixinCompat#PINNED_CONTRACTS} is a hand list, so it is read back off fabric-api itself: every row's
 * pin implements exactly that interface on exactly that target, and every fabric-api pin that implements an interface
 * has its row — a pin added later without one would bring back the unguarded {@code AssertionError}.
 */
class MergedBaseMixinCompatPinnedContractsTest {
	private static final Pattern PACKAGE = Pattern.compile("\"package\"\\s*:\\s*\"([^\"]+)\"");

	@Test void everyFabricApiPinThatImplementsAnInterfaceHasItsRowAndEveryRowIsTrue() throws Exception {
		Map<String, byte[]> modules = fabricApiModules();
		List<MergedBaseMixinCompat.PinnedContract> read = new ArrayList<>();
		int judged = 0;
		for (String pin : MergedBaseMixinCompat.SUPPRESSED_MIXINS) {
			String config = pin.substring(0, pin.indexOf(':'));
			if (!config.startsWith("fabric-")) continue; // other mods' pins have no fixture here
			String module = config.substring(0, config.indexOf('.'));
			byte[] jar = modules.get(module);
			assertNotNull(jar, "fabric-api nests no " + module);
			Map<String, byte[]> entries = entries(jar);
			Matcher pkg = PACKAGE.matcher(new String(entries.get(config), StandardCharsets.UTF_8));
			assertTrue(pkg.find(), config);
			String mixin = pkg.group(1).replace('.', '/') + "/" + pin.substring(pin.indexOf(':') + 1).replace('.', '/');
			var node = MixinFit.parse(entries.get(mixin + ".class"));
			judged++;
			for (String contract : MixinFit.contributedInterfaces(node)) {
				for (String target : MixinFit.mixinTargets(node)) read.add(new MergedBaseMixinCompat.PinnedContract(pin, target, contract));
			}
		}
		assertTrue(judged >= 6, "the fabric-api pins were all read: " + judged);
		assertEquals(Set.copyOf(read), Set.copyOf(MergedBaseMixinCompat.PINNED_CONTRACTS));
		for (var row : MergedBaseMixinCompat.PINNED_CONTRACTS) assertTrue(MergedBaseMixinCompat.SUPPRESSED_MIXINS.contains(row.pin()));
	}

	/** Lifted by -Dforbric.keepMixins or -Dforbric.mergedBaseCompat=off, a pin's mixin applies and implements its interface. */
	@Test @ResourceLock("system-properties")
	void aPinIsInForceUntilSomethingLiftsIt() {
		String pin = MergedBaseMixinCompat.CREATIVE_PAGER_PIN;
		assertTrue(MergedBaseMixinCompat.pinInForce(pin));
		assertFalse(MergedBaseMixinCompat.pinInForce(pin.substring(0, pin.indexOf(':') + 1) + "NotPinned"));
		try {
			System.setProperty("forbric.keepMixins", pin);
			assertFalse(MergedBaseMixinCompat.pinInForce(pin), "kept by name");
			System.clearProperty("forbric.keepMixins");
			System.setProperty("forbric.mergedBaseCompat", "off");
			assertFalse(MergedBaseMixinCompat.pinInForce(pin), "the built-in lists switched off");
			System.setProperty("forbric.suppressMixins", pin);
			assertTrue(MergedBaseMixinCompat.pinInForce(pin), "named again by hand");
		} finally {
			System.clearProperty("forbric.keepMixins");
			System.clearProperty("forbric.mergedBaseCompat");
			System.clearProperty("forbric.suppressMixins");
		}
	}

	/**
	 * The raw-byte check in front of the pinned-contract parse turns away only what the parse would: across every class
	 * of owo-lib and fabric-api's creative-tab module, a class relies on the interface exactly when its bytes pass. A
	 * needle spelled as a descriptor or with dots would miss owo's mixin here, and let it throw in the game.
	 */
	@Test
	void thePinnedContractPrefilterTurnsAwayOnlyWhatTheParseWould() throws Exception {
		MergedBaseMixinCompat.PinnedContract row = MergedBaseMixinCompat.PINNED_CONTRACTS.getFirst();
		byte[] named = net.forbric.kernel.util.ByteScan.needle(row.contract());
		Map<String, byte[]> classes = new HashMap<>();
		for (var e : entries(fabricApiModules().get("fabric-creative-tab-api-v1")).entrySet()) {
			if (e.getKey().endsWith(".class")) classes.put(e.getKey(), e.getValue());
		}
		try (ZipFile owo = new ZipFile(CreativePagerFixtures.owo().toFile())) {
			for (ZipEntry e : owo.stream().toList()) {
				if (e.getName().endsWith(".class")) classes.put(e.getName(), owo.getInputStream(e).readAllBytes());
			}
		}
		List<String> relying = new ArrayList<>();
		int turnedAway = 0;
		for (var e : classes.entrySet()) {
			boolean parsed;
			try {
				parsed = MixinFit.referencesAny(MixinFit.parse(e.getValue()), Set.of(row.contract()));
			} catch (RuntimeException unreadable) {
				parsed = false;
			}
			assertEquals(parsed, KernelGuestMixinAdapter.reliesOn(e.getValue(), row.contract(), named), e.getKey());
			if (parsed) relying.add(e.getKey());
			if (!net.forbric.kernel.util.ByteScan.contains(e.getValue(), named)) turnedAway++;
		}
		assertTrue(relying.contains(CreativePagerFixtures.OWO_MIXIN + ".class"), relying.toString());
		assertTrue(turnedAway > classes.size() * 9 / 10, turnedAway + " of " + classes.size() + " never parsed");
	}

	/** fabric-api's nested module jars, by module id (the file name up to its version). */
	private static Map<String, byte[]> fabricApiModules() throws Exception {
		Map<String, byte[]> out = new HashMap<>();
		try (ZipFile api = new ZipFile(CreativePagerFixtures.fabricApi().toFile())) {
			for (ZipEntry e : api.stream().toList()) {
				String name = e.getName();
				if (!name.startsWith("META-INF/jars/fabric-") || !name.endsWith(".jar")) continue;
				String file = name.substring("META-INF/jars/".length());
				Matcher id = Pattern.compile("^(fabric-.+?-v\\d+)-").matcher(file);
				if (id.find()) out.put(id.group(1), api.getInputStream(e).readAllBytes());
			}
		}
		return out;
	}

	private static Map<String, byte[]> entries(byte[] jar) throws Exception {
		Map<String, byte[]> out = new HashMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jar))) {
			for (ZipEntry e; (e = in.getNextEntry()) != null; ) if (!e.isDirectory()) out.put(e.getName(), in.readAllBytes());
		}
		return out;
	}
}
