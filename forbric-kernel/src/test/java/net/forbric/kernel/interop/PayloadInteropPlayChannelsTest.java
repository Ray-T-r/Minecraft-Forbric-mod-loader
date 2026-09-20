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

package net.forbric.kernel.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The kernel serves Fabric's {@code c:register} itself, so it must do BOTH halves of Fabric's own handler: replay
 * it to the addon, and record the PLAY channels onto the connection. It reaches all of that reflectively, by
 * name — and a name that has drifted returns null instead of throwing, so the recording silently does nothing.
 *
 * <p>That is not a hypothetical. The first version asked the payload for {@code phase()}; the record component is
 * {@code protocol}, so the reflective miss gave null, null never equalled "play", and nothing was recorded — the
 * same empty list the fix existed to fill. The cost is severe and mod-specific: {@code ServerPlayNetworking.canSend}
 * answers false for every Fabric PLAY channel, and Cardinal Components DISCONNECTS rather than skipping, so
 * joining a world ended with "This server requires Apoli: Legacy and Cardinal Components API".
 *
 * <p>So every name this path spells is checked against the real fabric-api and the real merged base.
 */
class PayloadInteropPlayChannelsTest {
	private static final Path MODS = Path.of("run/client-popular/mods");
	private static final Path MERGED_BASE =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();

	@Test
	void everyNameTheRecordingSpellsExistsWhereItIsSpelled() throws Exception {
		List<String> spelled = stringsOf("net/forbric/kernel/interop/PayloadInterop.class",
				Path.of("build/classes/java/main"));
		assumeTrue(!spelled.isEmpty(), "kernel classes not compiled");
		assertTrue(spelled.contains("fabric_getPendingChannelsNames"),
				"the connection accessor Fabric's ServerPlayNetworkAddon drains must be named");
		assertTrue(spelled.contains("protocol"),
				"CommonRegisterPayload's phase component is `protocol`; asking for `phase` records nothing");
		assertTrue(spelled.contains("channels"), "and its channel set");

		byte[] fabricApi = nested("fabric-api-", "networking-api-v1",
				"net/fabricmc/fabric/impl/networking/CommonRegisterPayload.class");
		assumeTrue(fabricApi != null, "fabric-api's networking module absent");
		List<String> components = accessorsOf(fabricApi);
		assertTrue(components.contains("protocol"),
				"fabric's CommonRegisterPayload must still declare protocol(); it declares " + components);
		assertTrue(components.contains("channels"), components.toString());

		byte[] holder = nested("fabric-api-", "networking-api-v1",
				"net/fabricmc/fabric/impl/networking/ChannelInfoHolder.class");
		assertNotNull(holder, "fabric's ChannelInfoHolder must be present");
		assertTrue(accessorsOf(holder).contains("fabric_getPendingChannelsNames"),
				"the one method that seeds a play addon's sendable channels");
	}

	@Test
	void theConnectionProtocolConstantAndAccessorAreWhatTheMergedBaseDeclares() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		ClassNode protocol = parse(fromZip(MERGED_BASE, "net/minecraft/network/ConnectionProtocol.class"));
		assertTrue(protocol.fields.stream().anyMatch(f -> "PLAY".equals(f.name)),
				"the enum constant the recording resolves by name");
		assertTrue(protocol.methods.stream().anyMatch(m -> "id".equals(m.name) && "()Ljava/lang/String;".equals(m.desc)),
				"and the accessor whose value it compares the payload's protocol against");
	}

	@Test
	void theSwitchIsSpelledTheWayTheGateDrivesIt() {
		assertEquals("forbric.fabricPlayChannels", PayloadInterop.PLAY_CHANNELS_PROPERTY);
		assertTrue(PayloadInterop.playChannelRecordingEnabled(), "on by default");
	}

	// --- helpers -------------------------------------------------------------------------------------------------

	/** Every constant string a class carries — what a reflective name looks like from the outside. */
	private static List<String> stringsOf(String entry, Path classes) throws Exception {
		Path file = classes.resolve(entry);
		if (!Files.isRegularFile(file)) return List.of();
		ClassNode node = parse(Files.readAllBytes(file));
		List<String> out = new ArrayList<>();
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) out.add(s);
			}
		}
		return out;
	}

	private static List<String> accessorsOf(byte[] bytes) {
		return parse(bytes).methods.stream().map(m -> m.name).toList();
	}

	/** A class inside a nested jar of one of the staged mods, or null when that mod is not present. */
	private static byte[] nested(String jarPrefix, String innerContains, String entry) throws IOException {
		if (!Files.isDirectory(MODS)) return null;
		try (var listing = Files.list(MODS)) {
			for (Path jar : listing.toList()) {
				if (!jar.getFileName().toString().startsWith(jarPrefix)) continue;
				try (ZipFile zip = new ZipFile(jar.toFile())) {
					for (ZipEntry candidate : zip.stream().toList()) {
						if (!candidate.getName().contains(innerContains)) continue;
						try (InputStream in = zip.getInputStream(candidate);
								java.util.zip.ZipInputStream inner = new java.util.zip.ZipInputStream(
										new ByteArrayInputStream(in.readAllBytes()))) {
							for (ZipEntry e = inner.getNextEntry(); e != null; e = inner.getNextEntry()) {
								if (entry.equals(e.getName())) return inner.readAllBytes();
							}
						}
					}
				}
			}
		}
		return null;
	}

	private static byte[] fromZip(Path jar, String entry) throws IOException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			assertNotNull(found, entry);
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}
}
