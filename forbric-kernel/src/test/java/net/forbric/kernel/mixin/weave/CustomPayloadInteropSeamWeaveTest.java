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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.mixin.NativeGameReferences;
import net.forbric.kernel.mixin.NativeReferenceSources;
import net.forbric.kernel.transform.CustomPayloadInteropSeam;
import net.forbric.kernel.transform.PayloadCodecFunnelInjector;
import net.forbric.kernel.transform.VanillaMemberTwinInjector;

/**
 * fabric-networking's real {@code CustomPayloadStreamCodecMixin}, woven by the real Mixin onto the GENUINE NeoForge base's
 * {@code CustomPacketPayload$1} as the kernel's chain leaves it: the vanilla-member twins and the payload-codec seam
 * before Mixin ({@code PayloadCodecFunnelInjector} installed as KernelBoot registers it), the post-Mixin codec arbitration
 * after. The mixin wraps both {@code findCodec} calls; the seam is what {@code PostMixinCodecReturnArbitration} reconciles
 * a guest's choice against, so with the seam both wrapped calls are arbitrated and without it neither is.
 *
 * <p>The twins are made with the vanilla source KernelBoot builds, over a launch configured as KernelBoot configures it:
 * the genuine base, which embeds no index, and a vanilla-reference sidecar the installer's own writer built.
 */
@ResourceLock("native-game-references")
class CustomPayloadInteropSeamWeaveTest {
	private static final String PAYLOAD = "net/minecraft/network/protocol/common/custom/CustomPacketPayload";
	private static final String CODEC = PAYLOAD + "$1";
	private static final String MIXIN = "net/fabricmc/fabric/mixin/networking/CustomPayloadStreamCodecMixin";
	private static final String FALLBACK = "L" + PAYLOAD + "$FallbackProvider;";

	@TempDir Path work;

	@AfterEach void unconfigure() { NativeGameReferences.configure(null); }

	@Test void fabricNetworkingsWrapIsArbitratedAroundTheSeamOnTheGenuineBase() throws Exception {
		Path genuine = TestFixtures.genuineBase().normalize();
		Path neo = TestFixtures.neoCarrier().normalize();
		TestFixtures.requireFiles(Fixture.STAGED, "the genuine NeoForge base and its carrier", genuine, neo);
		Path networking = nested(TestFixtures.fabricApi(), "fabric-networking-api-v1");

		Function<String, byte[]> base = reader(genuine);
		NativeGameReferences.configure(NativeReferenceSources.open(genuine, TestFixtures.vanillaReference(genuine)));
		VanillaMemberTwinInjector twins = new VanillaMemberTwinInjector(VanillaMemberTwinInjector.vanillaReferences(base));
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

		// A vanilla-compiled caller's path, in the classes exactly as the game defines them after weaving: vanilla's
		// codec(FallbackProvider, List) (the copied twin) builds the codec class through its vanilla-shaped constructor (the
		// delegate twin), which hands NeoForge's constructor null for the protocol and the flow; that constructor stores
		// them, and the seamed lookup passes those two fields into PayloadInterop.findCodec. VanillaCallerPayloadLookupTest
		// runs PayloadInterop with exactly that null context. (Not run here: Identifier's static initializer reaches
		// NeoForge's FMLLoader through Component.translatable, which a weave run does not boot.)
		ClassNode wovenPayload = node(withSeam.defined(PAYLOAD));
		String twinCtor = "(Ljava/util/Map;" + FALLBACK + ")V";
		String neoCtor = "(Ljava/util/Map;Lnet/minecraft/network/ConnectionProtocol;Lnet/minecraft/network/protocol/PacketFlow;" + FALLBACK + ")V";
		MethodNode vanillaCodec = method(wovenPayload, "codec", "(" + FALLBACK + "Ljava/util/List;)");
		assertTrue(invokes(vanillaCodec, Opcodes.INVOKESPECIAL, CODEC, "<init>", twinCtor), "vanilla's codec builds the codec class vanilla's way");
		MethodNode twin = method(woven, "<init>", twinCtor);
		assertEquals(2, opcodes(twin, Opcodes.ACONST_NULL), "the delegate twin hands NeoForge's constructor no protocol and no flow");
		assertTrue(invokes(twin, Opcodes.INVOKESPECIAL, CODEC, "<init>", neoCtor), "into NeoForge's own constructor");
		MethodNode neoConstructor = method(woven, "<init>", neoCtor);
		for (String field : List.of("val$protocol", "val$packetFlow")) {
			assertTrue(accesses(neoConstructor, Opcodes.PUTFIELD, field), "NeoForge's constructor stores " + field);
			assertTrue(accesses(method(woven, "findCodec", null), Opcodes.GETFIELD, field), "the seamed lookup hands " + field + " on");
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

	private static boolean invokes(MethodNode method, int opcode, String owner, String name, String desc) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == opcode && call.owner.equals(owner) && call.name.equals(name)
					&& call.desc.equals(desc)) return true;
		}
		return false;
	}

	private static boolean accesses(MethodNode method, int opcode, String field) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof FieldInsnNode access && access.getOpcode() == opcode && access.owner.equals(CODEC)
					&& access.name.equals(field)) return true;
		}
		return false;
	}

	private static int opcodes(MethodNode method, int opcode) {
		int n = 0;
		for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() == opcode) n++;
		return n;
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
