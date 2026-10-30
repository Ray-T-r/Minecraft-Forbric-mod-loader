package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.transform.CustomPayloadInteropSeam;
import net.forbric.kernel.transform.PayloadCodecFunnelInjector;
import net.forbric.kernel.transform.VanillaMemberTwinInjector;

/**
 * fabric-networking's real {@code CustomPayloadStreamCodecMixin}, woven by the real Mixin onto the GENUINE NeoForge base's
 * {@code CustomPacketPayload$1} as the kernel's chain leaves it: the vanilla-member twins and the payload-codec seam
 * before Mixin ({@code PayloadCodecFunnelInjector} installed as KernelBoot registers it), the post-Mixin codec arbitration
 * after. The mixin wraps both {@code findCodec} calls; the seam is what {@code PostMixinCodecReturnArbitration} reconciles
 * a guest's choice against, so with the seam both wrapped calls are arbitrated and without it neither is.
 */
class CustomPayloadInteropSeamWeaveTest {
	private static final String PAYLOAD = "net/minecraft/network/protocol/common/custom/CustomPacketPayload";
	private static final String CODEC = PAYLOAD + "$1";
	private static final String MIXIN = "net/fabricmc/fabric/mixin/networking/CustomPayloadStreamCodecMixin";

	@TempDir Path work;

	@Test void fabricNetworkingsWrapIsArbitratedAroundTheSeamOnTheGenuineBase() throws Exception {
		Path genuine = TestFixtures.stagedRoot().resolve(TestFixtures.GENUINE_BASE_JAR).normalize();
		Path neo = TestFixtures.stagedRoot().resolve(TestFixtures.NEO_CARRIER_JAR).normalize();
		Path vanillaJar = TestFixtures.vanillaJar();
		TestFixtures.requireFiles(Fixture.STAGED, "the genuine NeoForge base and its carrier", genuine, neo);
		TestFixtures.requireFiles(Fixture.MC_LIBRARIES, "the vanilla client jar", vanillaJar);
		Path networking = nested(TestFixtures.fabricApi(), "fabric-networking-api-v1");

		Function<String, byte[]> base = reader(genuine);
		VanillaMemberTwinInjector twins = new VanillaMemberTwinInjector(vanilla(reader(vanillaJar)));
		byte[] payload = twins.transform(PAYLOAD.replace('/', '.'), base.apply(PAYLOAD + ".class"), null);
		byte[] twinned = twins.transform(CODEC.replace('/', '.'), base.apply(CODEC + ".class"), null);
		byte[] seamed = new CustomPayloadInteropSeam(base).transform(CODEC.replace('/', '.'), twinned, null);
		assertNotSame(twinned, seamed, "premise: the seam lands on the genuine codec class");

		WeaveHarness.Result withSeam = run("seam", payload, seamed, networking, genuine, neo);
		WeaveHarness.Result withoutSeam = run("no-seam", payload, twinned, networking, genuine, neo);

		for (WeaveHarness.Result result : List.of(withSeam, withoutSeam)) {
			assertTrue(result.printed(WeaveHarnessMain.DONE + " woven"), result.describe());
			assertTrue(WeaveHarness.hasMergedMethod(result.defined(CODEC)), "fabric-networking's wrap is woven in — " + result.describe());
			assertTrue(result.findings().stream().noneMatch(WeaveHarness.Finding::confirmedRequired), result.describe());
			// The funnel found vanilla's codec overload (the twin) and routes NeoForge's through it, as on the merged base.
			MethodNode neoCodec = method(node(result.defined(PAYLOAD)), "codec", "(L" + PAYLOAD + "$FallbackProvider;Ljava/util/List;"
					+ "Lnet/minecraft/network/ConnectionProtocol;Lnet/minecraft/network/protocol/PacketFlow;)");
			assertTrue(calls(neoCodec, "net/forbric/kernel/runtime/KernelPayloadCodecs", "through"), "the funnel landed — " + result.describe());
		}

		ClassNode woven = node(withSeam.defined(CODEC));
		String interop = "net/forbric/kernel/interop/PayloadInterop";
		assertEquals(1, count(method(woven, "findCodec", null), interop, "findCodec"), "one seam");
		for (String caller : List.of("writeCap", "decode")) {
			String desc = "(Lnet/minecraft/network/FriendlyByteBuf;" + (caller.equals("decode") ? ")" : "L");
			assertEquals(1, count(method(woven, caller, desc), interop, "afterGuestCodec"),
					caller + "'s wrapped lookup is arbitrated — " + withSeam.describe());
		}
		ClassNode plain = node(withoutSeam.defined(CODEC));
		for (MethodNode m : plain.methods) {
			assertEquals(0, count(m, "net/forbric/kernel/interop/PayloadInterop", null), "without the seam nothing is arbitrated: " + m.name);
		}

		// The verifier loads every type it assigns across: the base, its carrier, the module, MixinExtras and the game's netty.
		List<URL> urls = new ArrayList<>(List.of(genuine.toUri().toURL(), neo.toUri().toURL(), networking.toUri().toURL(),
				Path.of(WeaveHarness.mixinExtrasJar()).toUri().toURL()));
		Path netty = TestFixtures.minecraftDir().resolve("libraries/io/netty");
		TestFixtures.requireDirectory(Fixture.MC_LIBRARIES, "Minecraft's netty", netty);
		try (var jars = Files.walk(netty)) {
			for (Path jar : jars.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) urls.add(jar.toUri().toURL());
		}
		try (URLClassLoader resolver = new URLClassLoader(urls.toArray(URL[]::new), CustomPayloadInteropSeamWeaveTest.class.getClassLoader())) {
			String verdict = withSeam.verify(CODEC, resolver);
			assertTrue(verdict.isEmpty(), CODEC + " does not verify after weaving:\n" + verdict + "\n" + withSeam.describe());
		}
	}

	private WeaveHarness.Result run(String label, byte[] payload, byte[] codec, Path networking, Path genuine, Path neo) throws Exception {
		Path dir = Files.createDirectories(work.resolve(label));
		Path probe = dir.resolve("fixture/seam/Probe.java");
		Files.createDirectories(probe.getParent());
		Files.writeString(probe, """
				package fixture.seam;
				public class Probe {
				 public String probe() throws Exception {
				  ClassLoader loader = Probe.class.getClassLoader();
				  Class.forName("net.minecraft.network.protocol.common.custom.CustomPacketPayload", false, loader);
				  Class.forName("net.minecraft.network.protocol.common.custom.CustomPacketPayload$1", false, loader);
				  return "woven";
				 }
				}
				""");
		String config = "fabric-networking-seam.mixins.json";
		Path json = dir.resolve(config);
		Files.writeString(json, "{\"required\":true,\"package\":\"net.fabricmc.fabric.mixin.networking\",\"compatibilityLevel\":\"JAVA_21\","
				+ "\"mixins\":[\"CustomPayloadStreamCodecMixin\"],\"injectors\":{\"defaultRequire\":1}}");
		Path payloadFile = dir.resolve("payload.class");
		Path codecFile = dir.resolve("codec.class");
		Files.write(payloadFile, payload);
		Files.write(codecFile, codec);
		Map<String, Path> resources = new LinkedHashMap<>();
		resources.put(config, json);
		// The base's two classes as the pre-Mixin chain before the funnel leaves them; they shadow the base jar's.
		resources.put(PAYLOAD + ".class", payloadFile);
		resources.put(CODEC + ".class", codecFile);
		Path fixture = WeaveHarness.fixture(dir, "seam-" + label, List.of(probe), resources);
		String libraries = String.join(java.io.File.pathSeparator, networking.toString(), genuine.toString(), neo.toString());
		return WeaveHarness.run(work, label, fixture, List.of(new WeaveHarness.Config(config, "fabric-networking-api-v1", Ecosystem.FABRIC)),
				List.of(PayloadCodecFunnelInjector.class), EnvType.CLIENT, "fixture.seam.Probe", "probe",
				Map.of(WeaveHarnessMain.LIBRARIES, libraries));
	}

	/** fabric-api's nested module jar, unpacked beside the run. */
	private Path nested(Path api, String module) throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(api), "actual Fabric API fixture required");
		try (ZipFile z = new ZipFile(api.toFile())) {
			ZipEntry entry = z.stream().filter(e -> e.getName().startsWith("META-INF/jars/" + module + "-")).findFirst().orElseThrow();
			Path out = work.resolve(module + ".jar");
			try (InputStream in = z.getInputStream(entry)) { Files.copy(in, out); }
			try (ZipFile check = new ZipFile(out.toFile())) { assertNotNull(check.getEntry(MIXIN + ".class"), "the real mixin"); }
			return out;
		}
	}

	private static Function<String, byte[]> reader(Path jar) {
		return path -> {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				var entry = zip.getEntry(path);
				if (entry == null) return null;
				try (InputStream in = zip.getInputStream(entry)) { return in.readAllBytes(); }
			} catch (java.io.IOException unreadable) {
				throw new AssertionError(unreadable);
			}
		};
	}

	private static Function<String, ClassNode> vanilla(Function<String, byte[]> jar) {
		return owner -> {
			byte[] bytes = jar.apply(owner + ".class");
			if (bytes == null) return null;
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
			return node;
		};
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	/** The method named {@code name} whose descriptor starts with {@code descPrefix} (any, when null). */
	private static MethodNode method(ClassNode node, String name, String descPrefix) {
		List<MethodNode> found = new ArrayList<>();
		for (MethodNode m : node.methods) if (m.name.equals(name) && (descPrefix == null || m.desc.startsWith(descPrefix))) found.add(m);
		assertEquals(1, found.size(), name + " in " + node.name);
		return found.getFirst();
	}

	private static boolean calls(MethodNode method, String owner, String name) {
		return count(method, owner, name) > 0;
	}

	private static int count(MethodNode method, String owner, String name) {
		int n = 0;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && (name == null || call.name.equals(name))) n++;
		}
		return n;
	}
}
