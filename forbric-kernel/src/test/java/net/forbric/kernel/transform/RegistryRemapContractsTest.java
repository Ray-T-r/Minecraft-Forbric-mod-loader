/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * {@link RegistryRemapContracts}: the remap contract the kernel reads off a mod's registry mixin, which
 * {@code KernelRegistryRevert} undoes on the plain registries before NeoForge's snapshot goes back on a disconnect.
 *
 * <p>Moved out of {@code RegistrySyncParityInjectorTest} when that injector went with MinecraftForge's registry wrapper:
 * these two cases were about the reader, which fabric-api's registry sync needs on any base.
 */
class RegistryRemapContractsTest {
	/** A mod's remap mixin under unrelated names (RemapContractSamples); every type its contract names is on the loader. */
	private static final java.util.List<RegistryRemapContracts.RemapContract> CONTRACTS =
			RemapContractSamples.contracts(java.util.Map.of(RemapContractSamples.INTERFACE, RemapContractSamples.rebinds()));

	/** What makes a remap contract is the mixin's shape: a shadowed-storage method taking the ids, declared by its interface. */
	@Test
	void aRemapContractIsReadOffTheMixinNotItsNames() {
		assertEquals(java.util.List.of(new RegistryRemapContracts.RemapContract(RemapContractSamples.TARGET, RemapContractSamples.INTERFACE,
				RemapContractSamples.NAME, RemapContractSamples.DESC, 0, RemapContractSamples.UNDO)), CONTRACTS,
				"rebind is one, forgetAll its undo; note, which touches no shadowed field, is not");
		assertEquals(java.util.List.of(), RemapContractSamples.contracts(java.util.Map.of()), "an interface that cannot be read declares nothing");
	}

	/** fabric-api's own registry-sync mixin, read the same way: the sample the structural reading has to land on. */
	@Test
	void fabricApisRegistrySyncMixinCarriesOneRemapContract() throws Exception {
		Path api = TestFixtures.fabricApi();
		TestFixtures.require(Fixture.STAGED, java.nio.file.Files.isRegularFile(api), "the pinned Fabric API " + api + " absent");
		java.util.Map<String, byte[]> entries = new java.util.HashMap<>();
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(api.toFile())) {
			var nested = zip.stream().filter(e -> e.getName().startsWith("META-INF/jars/fabric-registry-sync-v0-")).findFirst().orElseThrow();
			try (var in = new java.util.zip.ZipInputStream(zip.getInputStream(nested))) {
				for (java.util.zip.ZipEntry entry; (entry = in.getNextEntry()) != null; ) entries.put(entry.getName(), in.readAllBytes());
			}
		}
		java.util.List<RegistryRemapContracts.RemapContract> found = new java.util.ArrayList<>();
		for (var entry : entries.entrySet()) {
			if (!entry.getKey().endsWith(".class")) continue;
			ClassNode node = parse(entry.getValue());
			if (net.forbric.kernel.mixin.MixinFit.mixinTargets(node).isEmpty()) continue;
			found.addAll(RegistryRemapContracts.remapContracts(node, entries::get));
		}
		assertEquals(1, found.size(), found::toString);
		assertEquals("net/minecraft/core/MappedRegistry", found.getFirst().target());
		assertEquals("(Lit/unimi/dsi/fastutil/objects/Object2IntMap;Lnet/fabricmc/fabric/impl/registry/sync/RemappableRegistry$RemapMode;)V",
				found.getFirst().desc());
		assertEquals("unmap", found.getFirst().undo());
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}
}
