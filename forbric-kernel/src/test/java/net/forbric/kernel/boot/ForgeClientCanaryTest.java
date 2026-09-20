package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

class ForgeClientCanaryTest {
	@TempDir Path temporary;

	@Test
	void packagedCanarySubscribesTheThreeEventsOnlyBehindItsClientClassBoundary() throws Exception {
		Path jar = WorldgenCanaryDataTest.LOADER.resolve("run/forge-runtime/forbriclive.jar");
		assumeTrue(Files.isRegularFile(jar), "build canaries with forbric-loader/run/build-testmods.sh");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ClassNode client = new ClassNode();
			new ClassReader(bytes(zip, "forbric/live/ForbricLiveClient.class")).accept(client, 0);
			var init = client.methods.stream().filter(method -> method.name.equals("init")
					&& method.desc.equals("(Lnet/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext;)V"))
					.findFirst().orElseThrow();
			assertEquals(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, init.access);
			for (String event : List.of("net/minecraftforge/client/event/RegisterKeyMappingsEvent",
					"net/minecraftforge/client/event/EntityRenderersEvent$RegisterRenderers",
					"net/minecraftforge/event/BuildCreativeModeTabContentsEvent")) {
				int subscriptions = 0;
				for (AbstractInsnNode instruction : init.instructions) {
					if (!(instruction instanceof FieldInsnNode bus) || bus.getOpcode() != Opcodes.GETSTATIC
							|| !bus.owner.equals(event) || !bus.name.equals("BUS")) continue;
					assertEquals("Lnet/minecraftforge/eventbus/api/bus/EventBus;", bus.desc);
					InvokeDynamicInsnNode listener = assertInstanceOf(InvokeDynamicInsnNode.class,
							nextCode(bus), event + " must create its listener after loading BUS");
					assertEquals("()Ljava/util/function/Consumer;", listener.desc);
					assertEquals("java/lang/invoke/LambdaMetafactory", listener.bsm.getOwner());
					assertTrue(List.of(listener.bsmArgs).contains(Type.getMethodType("(L" + event + ";)V")),
							"the listener must consume the same event type as its BUS");
					MethodInsnNode subscribe = assertInstanceOf(MethodInsnNode.class, nextCode(listener),
							event + " must actually register its listener, not merely link its class");
					assertEquals(Opcodes.INVOKEINTERFACE, subscribe.getOpcode());
					assertEquals("net/minecraftforge/eventbus/api/bus/EventBus", subscribe.owner);
					assertEquals("addListener", subscribe.name);
					assertEquals("(Ljava/util/function/Consumer;)Lnet/minecraftforge/eventbus/api/listener/EventListener;", subscribe.desc);
					subscriptions++;
				}
				assertEquals(1, subscriptions, "subscribe once to " + event);
			}
			String common = new String(bytes(zip, "forbric/live/ForbricLiveMod.class"), StandardCharsets.ISO_8859_1);
			assertFalse(common.contains("net/minecraftforge/client/"), "server entry point must not resolve Forge client classes");
			assertFalse(common.contains("net/minecraft/client/"), "server entry point must not resolve game client classes");
			assertTrue(common.contains("forbric.live.ForbricLiveClient"), "load the client half by name");
		}
	}

	@Test
	void presetListenerUsesTheModsBusGroupAndTheGateRequiresItsDelivery() throws Exception {
		Path jar = WorldgenCanaryDataTest.LOADER.resolve("run/forge-runtime/forbriclive.jar");
		assumeTrue(Files.isRegularFile(jar), "build canaries with forbric-loader/run/build-testmods.sh");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ClassNode client = new ClassNode();
			new ClassReader(bytes(zip, "forbric/live/ForbricLiveClient.class")).accept(client, 0);
			var init = client.methods.stream().filter(method -> method.name.equals("init")).findFirst().orElseThrow();
			int subscriptions = 0;
			String event = "net/minecraftforge/client/event/RegisterPresetEditorsEvent";
			for (AbstractInsnNode instruction : init.instructions) {
				if (!(instruction instanceof MethodInsnNode bus) || !bus.owner.equals(event) || !bus.name.equals("getBus")) continue;
				assertEquals("(Lnet/minecraftforge/eventbus/api/bus/BusGroup;)Lnet/minecraftforge/eventbus/api/bus/EventBus;", bus.desc);
				InvokeDynamicInsnNode listener = assertInstanceOf(InvokeDynamicInsnNode.class, nextCode(bus));
				assertTrue(List.of(listener.bsmArgs).contains(Type.getMethodType("(L" + event + ";)V")));
				MethodInsnNode subscribe = assertInstanceOf(MethodInsnNode.class, nextCode(listener));
				assertEquals("addListener", subscribe.name);
				assertEquals("(Ljava/util/function/Consumer;)Lnet/minecraftforge/eventbus/api/listener/EventListener;", subscribe.desc);
				subscriptions++;
			}
			assertEquals(1, subscriptions);
		}
		assertTrue(Files.readString(Path.of("run/gate-m26-forgeclient.sh")).contains("'RegisterPresetEditorsEvent'"));
	}

	@Test
	void clientFixtureDropsOnlyDatapackEntriesAndLeavesItsSourceJarUnchanged() throws Exception {
		Path source = temporary.resolve("source with spaces.jar");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(source))) {
			for (String entry : List.of("data/forbriclive/forge/biome_modifier/probe.json",
					"data/forbriclive/worldgen/placed_feature/probe.json", "forbric/live/ForbricLiveClient.class",
					"META-INF/mods.toml", "assets/forbriclive/retained.txt")) {
				zip.putNextEntry(new ZipEntry(entry));
				zip.write(("fixture: " + entry).getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		byte[] original = Files.readAllBytes(source);
		Path rundir = temporary.resolve("instance with spaces");
		Files.createDirectories(rundir.resolve("mods"));
		String script = Files.readString(Path.of("run/gate-m26-forgeclient.sh"));
		int begin = script.indexOf("# CLIENT_CANARY_STAGE_BEGIN");
		int end = script.indexOf("# CLIENT_CANARY_STAGE_END", begin);
		assertTrue(begin >= 0 && end > begin, "missing executable client fixture staging contract");
		ProcessBuilder builder = new ProcessBuilder("bash", "-c", script.substring(begin, end));
		builder.environment().put("FORGE", source.toString());
		builder.environment().put("RUNDIR", rundir.toString());
		assertSuccessful(builder);
		assertArrayEquals(original, Files.readAllBytes(source), "worldgen canary source must remain byte-for-byte intact");
		try (ZipFile staged = new ZipFile(rundir.resolve("mods/forbriclive.jar").toFile());
				ZipFile unmodified = new ZipFile(source.toFile())) {
			assertEquals(List.of("forbric/live/ForbricLiveClient.class", "META-INF/mods.toml", "assets/forbriclive/retained.txt"),
					staged.stream().map(ZipEntry::getName).toList());
			for (ZipEntry entry : staged.stream().toList()) {
				assertArrayEquals(unmodified.getInputStream(unmodified.getEntry(entry.getName())).readAllBytes(),
						staged.getInputStream(entry).readAllBytes(), "staging changed " + entry.getName());
			}
		}
	}

	@Test
	void clientGateParsesAndMarksOnlyTheKnownEventGapExpectedRed() throws Exception {
		Path gate = Path.of("run/gate-m26-forgeclient.sh");
		String script = Files.readString(gate);
		assertTrue(script.contains("# EXPECTED: RED until Phase 1 A"));
		assertTrue(script.contains("CONTROL_FAIL=$FAIL"));
		assertTrue(script.contains("make-test-world.sh"));
		assertTrue(script.contains("SRC_MODS=\"$RUNDIR/empty-mods\""));
		assertSuccessful(new ProcessBuilder("bash", "-n", gate.toString()));
	}

	private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
		do instruction = instruction.getNext(); while (instruction != null && instruction.getOpcode() < 0);
		return instruction;
	}

	private static byte[] bytes(ZipFile zip, String name) throws Exception {
		var entry = zip.getEntry(name);
		assertNotNull(entry, "missing canary class: " + name);
		return zip.getInputStream(entry).readAllBytes();
	}

	private static void assertSuccessful(ProcessBuilder builder) throws Exception {
		Process process = builder.redirectErrorStream(true).start();
		assertTrue(process.waitFor(10, TimeUnit.SECONDS), "client canary contract subprocess timed out");
		assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
	}
}
