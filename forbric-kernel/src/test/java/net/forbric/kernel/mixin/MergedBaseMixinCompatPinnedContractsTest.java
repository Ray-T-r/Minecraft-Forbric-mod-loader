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
