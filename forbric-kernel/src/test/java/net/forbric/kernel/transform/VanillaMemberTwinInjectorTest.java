package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.mixin.MixinFit;
import net.forbric.kernel.mixin.NativeGameReferences;
import net.forbric.kernel.mixin.NativeReferenceSources;

/**
 * The vanilla members only the merged base's MinecraftForge half kept, added back where a base lacks them: nothing on
 * the merged base; on the genuine NeoForge base every row lands, every member it adds links against that base and its
 * NeoForge carrier, the kernel repairs that wire those members to NeoForge's live ones find them, and the Fabric mixins
 * that name them bind — the real fabric-api and balm mixins where one exists, a fixture mixin for the rest.
 */
class VanillaMemberTwinInjectorTest {
	private static final Path MERGED = TestFixtures.mergedBase().normalize();
	private static final Path GENUINE = TestFixtures.genuineBase().normalize();
	private static final Path NEO_RUNTIME = TestFixtures.neoCarrier().normalize();
	private static final Path BALM = Path.of(System.getProperty("forbric.balmFabricJar",
			Path.of("run/client-popular/mods/balm-fabric-26.2-26.2.0.8.jar").toString()));

	@AfterEach void reset() { System.clearProperty(VanillaMemberTwinInjector.PROPERTY); }

	@Test void onTheMergedBaseEveryMemberIsAlreadyThereAndNothingChanges() {
		Function<String, byte[]> merged = reader(MERGED);
		VanillaMemberTwinInjector twins = new VanillaMemberTwinInjector(vanilla());
		for (String owner : owners()) {
			byte[] raw = merged.apply(owner + ".class");
			assertNotNull(raw, owner);
			for (VanillaMemberTwinInjector.Row row : rows(owner)) {
				assertTrue(declares(node(raw), row), "premise: the merged base declares " + row.name() + row.desc());
			}
			assertSame(raw, twins.transform(owner.replace('/', '.'), raw, null), owner + " is left exactly as it is");
		}
		assertEquals(0, twins.twinsAdded());
	}

	@Test void onTheGenuineBaseEveryRowLandsAndLinks() throws Exception {
		Function<String, byte[]> genuine = reader(GENUINE);
		Linker linker = new Linker(List.of(GENUINE, NEO_RUNTIME));
		VanillaMemberTwinInjector twins = new VanillaMemberTwinInjector(vanilla());
		Map<String, byte[]> transformed = new java.util.LinkedHashMap<>();
		for (String owner : owners()) {
			byte[] raw = genuine.apply(owner + ".class");
			ClassNode before = node(raw);
			for (VanillaMemberTwinInjector.Row row : rows(owner)) {
				assertFalse(declares(before, row), "premise: the genuine base lacks " + owner + "." + row.name() + row.desc());
			}
			byte[] out = twins.transform(owner.replace('/', '.'), raw, null);
			assertNotSame(raw, out, owner);
			transformed.put(owner, out);
			// A copied body may name another row's twin (vanilla's codec constructs $1 vanilla's way): link against all of them.
			linker.override(node(out));
		}
		for (var entry : transformed.entrySet()) {
			String owner = entry.getKey();
			byte[] out = entry.getValue();
			ClassNode before = node(genuine.apply(owner + ".class"));
			ClassNode after = node(out);
			for (VanillaMemberTwinInjector.Row row : rows(owner)) {
				assertTrue(declares(after, row), owner + "." + row.name() + row.desc() + " was added");
			}
			assertSame(out, twins.transform(owner.replace('/', '.'), out, null), "a second pass stands down on " + owner);
			for (MethodNode added : addedMethods(before, after)) {
				new Analyzer<>(new BasicVerifier()).analyze(after.name, added);
				for (String reference : references(added)) {
					assertTrue(linker.resolves(reference), owner + "." + added.name + " names " + reference
							+ ", which the genuine base and its NeoForge carrier do not declare");
				}
			}
			// Loads in a JVM, frames and all, from the bytes the writer produced.
			new ClassReader(out).accept(new org.objectweb.asm.util.CheckClassAdapter(new ClassWriter(0), false), 0);
		}
		assertEquals(VanillaMemberTwinInjector.ROWS.size(), twins.twinsAdded());
	}

	/** A delegate is vanilla's signature forwarding to NeoForge's overload, its own arguments in place, nulls for the rest. */
	@Test void theDelegatesForwardToNeoForgesLiveOverloads() {
		Function<String, byte[]> genuine = reader(GENUINE);
		Function<String, byte[]> merged = reader(MERGED);
		VanillaMemberTwinInjector twins = new VanillaMemberTwinInjector(vanilla());
		for (VanillaMemberTwinInjector.Row row : VanillaMemberTwinInjector.ROWS) {
			if (row.kind() != VanillaMemberTwinInjector.Kind.DELEGATE) continue;
			ClassNode after = node(twins.transform(row.owner().replace('/', '.'), genuine.apply(row.owner() + ".class"), null));
			MethodNode stub = VanillaMemberTwinInjector.method(after, row.name(), row.desc());
			List<MethodInsnNode> calls = new ArrayList<>();
			for (AbstractInsnNode insn : stub.instructions) if (insn instanceof MethodInsnNode call) calls.add(call);
			assertEquals(1, calls.size(), row.name());
			assertEquals(row.owner(), calls.getFirst().owner);
			assertEquals(row.live(), calls.getFirst().desc);
			if (row.owner().equals(VanillaMemberTwinInjector.AXE)) {
				// The merged base's own stub, instruction for instruction: carrier-stubs.txt's row keeps moving Fabric injectors.
				MethodNode mergedStub = VanillaMemberTwinInjector.method(node(merged.apply(row.owner() + ".class")), row.name(), row.desc());
				assertEquals(opcodes(mergedStub), opcodes(stub));
			}
		}
	}

	/** The repairs that make the merged base's copies live find the genuine base's twins and do the same. */
	@Test void theRepairsThatWireTheseMembersLandOnTheGenuineBase() {
		Function<String, byte[]> genuine = reader(GENUINE);
		VanillaMemberTwinInjector twins = new VanillaMemberTwinInjector(vanilla());
		ForbricMergedBaseCompatTransformer compat = new ForbricMergedBaseCompatTransformer(genuine);

		String gui = VanillaMemberTwinInjector.GUI_RENDERER;
		byte[] rawGui = genuine.apply(gui + ".class");
		ClassNode untwinnedGui = node(compat.transform(gui.replace('/', '.'), rawGui, null));
		assertNull(VanillaMemberTwinInjector.method(untwinnedGui, "forbric$prepareOrphanedPip", "(Lnet/minecraft/client/renderer/state/gui/pip/PictureInPictureRenderState;I)Z"),
				"premise: without the twins the PiP fallback cannot be built on the genuine base");
		ClassNode wiredGui = node(compat.transform(gui.replace('/', '.'), twins.transform(gui.replace('/', '.'), rawGui, null), null));
		assertTrue(wiredGui.methods.stream().anyMatch(m -> m.name.equals("forbric$prepareOrphanedPip")),
				"NeoForge's pooled lookup falls back to the vanilla map again");
		assertTrue(writes(VanillaMemberTwinInjector.method(wiredGui, "<init>", wiredGui.methods.stream()
				.filter(m -> m.name.equals("<init>")).findFirst().orElseThrow().desc), "pictureInPictureRenderers", "Ljava/util/Map;"),
				"and the vanilla map is filled in the constructor");

		String particles = VanillaMemberTwinInjector.PARTICLE_RESOURCES;
		byte[] rawParticles = genuine.apply(particles + ".class");
		ClassNode wiredParticles = node(compat.transform(particles.replace('/', '.'),
				twins.transform(particles.replace('/', '.'), rawParticles, null), null));
		assertTrue(writes(VanillaMemberTwinInjector.method(wiredParticles, "<init>", "()V"), "providers",
				"Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;"), "fabric-particles' int-keyed map is a live view of NeoForge's");

		String payload = VanillaMemberTwinInjector.PAYLOAD;
		ClassNode funnel = node(twins.transform(payload.replace('/', '.'), genuine.apply(payload + ".class"), null));
		assertTrue(PayloadCodecFunnelInjector.repair(funnel), "NeoForge's codec overload goes through vanilla's again");
		ClassNode bare = node(genuine.apply(payload + ".class"));
		assertFalse(PayloadCodecFunnelInjector.repair(bare), "premise: without vanilla's overload the funnel has nothing to route through");
	}

	@Test void withoutAVanillaReferenceOnlyTheCopiedBodiesStandDown() {
		Function<String, byte[]> genuine = reader(GENUINE);
		VanillaMemberTwinInjector twins = new VanillaMemberTwinInjector(owner -> null);
		for (String owner : owners()) {
			ClassNode after = node(twins.transform(owner.replace('/', '.'), genuine.apply(owner + ".class"), null));
			for (VanillaMemberTwinInjector.Row row : rows(owner)) {
				assertEquals(row.kind() != VanillaMemberTwinInjector.Kind.VANILLA_BODY, declares(after, row), row.name() + row.desc());
			}
		}
	}

	/**
	 * The vanilla source KernelBoot builds ({@link VanillaMemberTwinInjector#vanillaReferences} over the game's resources)
	 * on the genuine base, which embeds no native-reference index, launched as KernelBoot launches it: its sources
	 * configured from the base and a vanilla-reference sidecar the installer's own writer built. Every copied body lands
	 * with vanilla's original, and the payload-codec funnel finds vanilla's {@code codec} overload to route NeoForge's
	 * through. Without the launch's sources — a reader of the game's resources alone, which is what KernelBoot built
	 * before — none of them lands and the funnel's REQUIRED anchor has nothing to route through.
	 */
	@Test @ResourceLock("native-game-references")
	void kernelBootsVanillaSourceGivesEveryCopiedBodyItsVanillaOriginalOnTheGenuineBase() {
		Function<String, byte[]> genuine = reader(GENUINE);
		Function<String, byte[]> carrier = reader(NEO_RUNTIME);
		// The game's resources as the loader serves them: the base, then its carrier.
		Function<String, byte[]> gameResources = path -> {
			byte[] bytes = genuine.apply(path);
			return bytes != null ? bytes : carrier.apply(path);
		};
		assertNull(gameResources.apply("META-INF/forbric/native-reference/FABRIC/index.tsv"),
				"premise: neither the genuine base nor its carrier embeds a FABRIC index");
		List<VanillaMemberTwinInjector.Row> copied = VanillaMemberTwinInjector.ROWS.stream()
				.filter(row -> row.kind() == VanillaMemberTwinInjector.Kind.VANILLA_BODY).toList();
		assertEquals(5, copied.size(), "premise: five rows copy vanilla's body");
		Path sidecar = TestFixtures.vanillaReference(GENUINE);
		Function<String, ClassNode> vanillaJar = vanilla();
		String payload = VanillaMemberTwinInjector.PAYLOAD;
		try {
			NativeGameReferences.configure(NativeReferenceSources.open(GENUINE, sidecar));
			VanillaMemberTwinInjector twins = new VanillaMemberTwinInjector(VanillaMemberTwinInjector.vanillaReferences(gameResources));
			Map<String, ClassNode> twinned = new HashMap<>();
			for (String owner : owners()) twinned.put(owner, node(twins.transform(owner.replace('/', '.'), genuine.apply(owner + ".class"), null)));
			for (VanillaMemberTwinInjector.Row row : copied) {
				MethodNode copy = VanillaMemberTwinInjector.method(twinned.get(row.owner()), row.name(), row.desc());
				assertNotNull(copy, row.owner() + "." + row.name() + row.desc() + " lands from the sidecar");
				MethodNode original = VanillaMemberTwinInjector.method(vanillaJar.apply(row.owner()), row.name(), row.desc());
				assertEquals(opcodes(original), opcodes(copy), row.name() + " is vanilla's own body");
			}
			assertEquals(VanillaMemberTwinInjector.ROWS.size(), twins.twinsAdded(), "and every other row with them");
			ClassNode funnelled = node(twins.transform(payload.replace('/', '.'), genuine.apply(payload + ".class"), null));
			assertTrue(PayloadCodecFunnelInjector.repair(funnelled), "the funnel finds vanilla's codec overload");

			NativeGameReferences.configure(null);
			VanillaMemberTwinInjector bare = new VanillaMemberTwinInjector(VanillaMemberTwinInjector.vanillaReferences(gameResources));
			for (VanillaMemberTwinInjector.Row row : copied) {
				ClassNode after = node(bare.transform(row.owner().replace('/', '.'), genuine.apply(row.owner() + ".class"), null));
				assertNull(VanillaMemberTwinInjector.method(after, row.name(), row.desc()),
						"premise: over the game's resources alone " + row.name() + " has no vanilla body to copy");
			}
			assertFalse(PayloadCodecFunnelInjector.repair(node(bare.transform(payload.replace('/', '.'), genuine.apply(payload + ".class"), null))),
					"premise: and the funnel has no vanilla overload to route through");
		} finally {
			NativeGameReferences.configure(null);
		}
	}

	@Test void theSwitchLeavesTheBaseAlone() {
		byte[] raw = reader(GENUINE).apply(VanillaMemberTwinInjector.HUD + ".class");
		System.setProperty(VanillaMemberTwinInjector.PROPERTY, "off");
		assertSame(raw, new VanillaMemberTwinInjector(vanilla()).transform(VanillaMemberTwinInjector.HUD.replace('/', '.'), raw, null));
		assertTrue(new VanillaMemberTwinInjector(vanilla()).anchors().anchors().isEmpty());
	}

	@Test void everyAnchorIsAHedge() {
		for (AnchorSet.Anchor anchor : new VanillaMemberTwinInjector(owner -> null).anchors().anchors()) {
			assertEquals(AnchorSet.Severity.HEDGE, anchor.severity(), anchor.binaryName());
		}
	}

	/**
	 * The mixins that name the twinned members, judged by MixinFit — the kernel's own reading of whether Mixin will find
	 * each anchor — on the genuine base as the chain leaves it with and without the twins.
	 */
	@Test void theMixinsThatNameTheTwinsBindOnTheGenuineBase() throws Exception {
		Map<String, byte[]> mixins = new java.util.LinkedHashMap<>();
		mixins.put("fabric-rendering GuiRendererMixin", fabricApiClass("fabric-rendering-v1", "net/fabricmc/fabric/mixin/client/rendering/GuiRendererMixin"));
		mixins.put("fabric-rendering HudMixin", fabricApiClass("fabric-rendering-v1", "net/fabricmc/fabric/mixin/client/rendering/HudMixin"));
		mixins.put("fixture AxeItem HEAD", headMixin(VanillaMemberTwinInjector.AXE, "evaluateNewBlockState",
				VanillaMemberTwinInjector.ROWS.stream().filter(r -> r.owner().equals(VanillaMemberTwinInjector.AXE)).findFirst().orElseThrow().desc(), false));
		mixins.put("fixture CustomPacketPayload$1 constructor RETURN", headMixin(VanillaMemberTwinInjector.PAYLOAD_CODEC, "<init>",
				"(Ljava/util/Map;Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$FallbackProvider;)V", true));
		mixins.put("fixture ParticleResources.getProviders HEAD", headMixin(VanillaMemberTwinInjector.PARTICLE_RESOURCES, "getProviders",
				"()Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;", false));
		assertTheTwinsResolve(mixins);
	}

	/**
	 * balm-fabric's wrap of the moisture read in {@code CropBlock.getGrowthSpeed(Block, …)}, the one consumer of that twin:
	 * its own test, so a machine without the jar skips it by name ({@code [fixture:third-party]}) instead of passing the
	 * test above without ever judging it.
	 */
	@Test void balmsCropMixinBindsOnTheGenuineBase() throws Exception {
		TestFixtures.requireFiles(Fixture.THIRD_PARTY, "balm-fabric, whose FabricCropBlockMixin wraps CropBlock.getGrowthSpeed", BALM);
		assertTheTwinsResolve(Map.of("balm FabricCropBlockMixin", jarClass(BALM, "net/blay09/mods/balm/fabric/internal/mixin/FabricCropBlockMixin")));
	}

	/**
	 * Each of {@code mixins} judged by MixinFit on the genuine base as the chain leaves it, with and without the twins:
	 * the twins must resolve something that was missing, a fixture must fit outright, and a real mod's mixin must have
	 * nothing left unresolved on a twinned member.
	 */
	private static void assertTheTwinsResolve(Map<String, byte[]> mixins) {
		Function<String, byte[]> genuine = reader(GENUINE);
		Function<String, byte[]> neo = reader(NEO_RUNTIME);
		VanillaMemberTwinInjector twins = new VanillaMemberTwinInjector(vanilla());
		ForbricMergedBaseCompatTransformer compat = new ForbricMergedBaseCompatTransformer(genuine);
		Map<String, byte[]> chained = new HashMap<>();
		for (String owner : owners()) {
			chained.put(owner, compat.transform(owner.replace('/', '.'), twins.transform(owner.replace('/', '.'), genuine.apply(owner + ".class"), null), null));
		}
		// MixinFit asks by resource path (a/b/C.class).
		Function<String, byte[]> withTwins = path -> {
			String name = path.substring(0, path.length() - ".class".length());
			return chained.containsKey(name) ? chained.get(name) : fallback(genuine, neo).apply(name);
		};
		Function<String, byte[]> withoutTwins = path -> {
			String name = path.substring(0, path.length() - ".class".length());
			return chained.containsKey(name) ? compat.transform(name.replace('/', '.'), genuine.apply(path), null)
					: fallback(genuine, neo).apply(name);
		};

		StringBuilder report = new StringBuilder();
		for (var mixin : mixins.entrySet()) {
			MixinFit.Result with = MixinFit.evaluate(mixin.getValue(), withTwins);
			MixinFit.Result without = MixinFit.evaluate(mixin.getValue(), withoutTwins);
			report.append(mixin.getKey()).append(": without twins ").append(without.verdict()).append(' ').append(without.unresolved())
					.append("\n  with twins ").append(with.verdict()).append(' ').append(with.unresolved()).append('\n');
			assertTrue(with.hardUnresolved() < without.hardUnresolved(), "the twins resolve what was missing — " + report);
			if (mixin.getKey().startsWith("fixture")) {
				assertEquals(MixinFit.Verdict.FIT, with.verdict(), report.toString());
				continue;
			}
			for (String miss : with.unresolved()) {
				for (VanillaMemberTwinInjector.Row row : VanillaMemberTwinInjector.ROWS) {
					if (row.name().equals("<init>")) continue;
					assertFalse(miss.contains(row.name()), "still unresolved on a twinned member: " + miss + "\n" + report);
				}
			}
		}
		System.out.println(report);
	}

	private static Function<String, byte[]> fallback(Function<String, byte[]> genuine, Function<String, byte[]> neo) {
		return name -> {
			byte[] bytes = genuine.apply(name + ".class");
			if (bytes == null) bytes = neo.apply(name + ".class");
			if (bytes == null) {
				try (InputStream in = ClassLoader.getSystemResourceAsStream(name + ".class")) {
					bytes = in == null ? null : in.readAllBytes();
				} catch (java.io.IOException unreadable) {
					bytes = null;
				}
			}
			return bytes;
		};
	}

	/** {@code @Mixin(target)} with one {@code @Inject} at HEAD (RETURN for a constructor) of {@code method}. */
	private static byte[] headMixin(String target, String method, String desc, boolean constructor) {
		ClassWriter writer = new ClassWriter(0);
		String name = "fixture/twins/" + target.substring(target.lastIndexOf('/') + 1).replace('$', '_') + "Mixin";
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, null, "java/lang/Object", null);
		AnnotationVisitor mixin = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor targets = mixin.visitArray("targets");
		targets.visit(null, target.replace('/', '.'));
		targets.visitEnd();
		mixin.visitEnd();
		boolean isStatic = false;
		String callback = "Lorg/spongepowered/asm/mixin/injection/callback/" + (Type.getReturnType(desc).getSort() == Type.VOID
				? "CallbackInfo;" : "CallbackInfoReturnable;");
		MethodVisitor handler = writer.visitMethod(Opcodes.ACC_PRIVATE | (isStatic ? Opcodes.ACC_STATIC : 0), "fixture$hook",
				"(" + callback + ")V", null, null);
		AnnotationVisitor inject = handler.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
		AnnotationVisitor methods = inject.visitArray("method");
		methods.visit(null, method + desc);
		methods.visitEnd();
		AnnotationVisitor ats = inject.visitArray("at");
		AnnotationVisitor at = ats.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
		at.visit("value", constructor ? "RETURN" : "HEAD");
		at.visitEnd();
		ats.visitEnd();
		inject.visit("require", 1);
		inject.visitEnd();
		handler.visitCode();
		handler.visitInsn(Opcodes.RETURN);
		handler.visitMaxs(0, 2);
		handler.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	static byte[] fabricApiClass(String module, String name) throws Exception {
		Path api = TestFixtures.fabricApi();
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(api), "actual Fabric API fixture required");
		try (ZipFile z = new ZipFile(api.toFile())) {
			ZipEntry nested = z.stream().filter(e -> e.getName().startsWith("META-INF/jars/" + module + "-")).findFirst().orElseThrow();
			try (ZipInputStream inner = new ZipInputStream(z.getInputStream(nested))) {
				for (ZipEntry entry; (entry = inner.getNextEntry()) != null;) if (entry.getName().equals(name + ".class")) return inner.readAllBytes();
			}
		}
		throw new AssertionError(name + " is not in " + module);
	}

	private static byte[] jarClass(Path jar, String name) throws Exception {
		try (ZipFile z = new ZipFile(jar.toFile()); InputStream in = z.getInputStream(z.getEntry(name + ".class"))) {
			return in.readAllBytes();
		}
	}

	/**
	 * Vanilla's classes, frames expanded, read straight from the launcher's vanilla jar: the bytes the vanilla-reference
	 * sidecar's FABRIC index pins, which VanillaReferenceStagedTest proves is the merged base's own index. Not the
	 * production reader — on the genuine base that one answers only through the sidecar, and
	 * {@link #kernelBootsVanillaSourceGivesEveryCopiedBodyItsVanillaOriginalOnTheGenuineBase} drives it.
	 */
	static Function<String, ClassNode> vanilla() {
		Path jar = TestFixtures.vanillaJar();
		TestFixtures.requireFiles(Fixture.MC_LIBRARIES, "the vanilla client jar", jar);
		Function<String, byte[]> read = reader(jar);
		return owner -> {
			byte[] bytes = read.apply(owner + ".class");
			if (bytes == null) return null;
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
			return node;
		};
	}

	private static Set<String> owners() {
		Set<String> owners = new LinkedHashSet<>();
		for (VanillaMemberTwinInjector.Row row : VanillaMemberTwinInjector.ROWS) owners.add(row.owner());
		return owners;
	}

	private static List<VanillaMemberTwinInjector.Row> rows(String owner) {
		return VanillaMemberTwinInjector.ROWS.stream().filter(r -> r.owner().equals(owner)).toList();
	}

	private static boolean declares(ClassNode node, VanillaMemberTwinInjector.Row row) {
		return row.kind() == VanillaMemberTwinInjector.Kind.FIELD ? VanillaMemberTwinInjector.field(node, row.name(), row.desc()) != null
				: VanillaMemberTwinInjector.method(node, row.name(), row.desc()) != null;
	}

	private static List<MethodNode> addedMethods(ClassNode before, ClassNode after) {
		List<MethodNode> added = new ArrayList<>();
		for (MethodNode m : after.methods) if (VanillaMemberTwinInjector.method(before, m.name, m.desc) == null) added.add(m);
		return added;
	}

	private static boolean writes(MethodNode method, String name, String desc) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.name.equals(name) && f.desc.equals(desc)) return true;
		}
		return false;
	}

	private static List<Integer> opcodes(MethodNode method) {
		List<Integer> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() >= 0) out.add(insn.getOpcode());
		return out;
	}

	/** Every member reference of {@code method}: {@code owner.name desc}, a field's with a colon. */
	private static List<String> references(MethodNode method) {
		List<String> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call) out.add(call.owner + "." + call.name + call.desc);
			else if (insn instanceof FieldInsnNode field) out.add(field.owner + "." + field.name + ":" + field.desc);
			else if (insn instanceof InvokeDynamicInsnNode indy) {
				for (Object argument : indy.bsmArgs) if (argument instanceof Handle handle && handle.getTag() >= Opcodes.H_INVOKEVIRTUAL) {
					out.add(handle.getOwner() + "." + handle.getName() + handle.getDesc());
				}
			}
		}
		return out;
	}

	/** {@code jar}'s entries by resource path; a missing jar skips the test as a missing staged fixture. */
	static Function<String, byte[]> reader(Path jar) {
		TestFixtures.requireFiles(Fixture.STAGED, "the staged base", jar);
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

	/** Resolves a member reference up the declaring hierarchy over the staged jars and the JDK, with transformed overrides. */
	private static final class Linker {
		private final List<Function<String, byte[]>> jars = new ArrayList<>();
		private final Map<String, ClassNode> cache = new HashMap<>();

		Linker(List<Path> paths) {
			for (Path path : paths) jars.add(reader(path));
		}

		void override(ClassNode node) {
			cache.put(node.name, node);
		}

		boolean resolves(String reference) {
			int dot = reference.lastIndexOf('.', reference.indexOf('(') < 0 ? reference.indexOf(':') : reference.indexOf('('));
			String owner = reference.substring(0, dot);
			String member = reference.substring(dot + 1);
			if (owner.startsWith("[")) return true;
			// Minecraft's libraries (DataFixerUpper, fastutil, Guava…) are not what a twin can break: only the game's and
			// the carrier's own classes are judged.
			if (load(owner) == null && !owner.startsWith("net/minecraft/") && !owner.startsWith("com/mojang/blaze3d/")
					&& !owner.startsWith("net/neoforged/")) return true;
			boolean field = member.contains(":");
			String name = field ? member.substring(0, member.indexOf(':')) : member.substring(0, member.indexOf('('));
			String desc = field ? member.substring(member.indexOf(':') + 1) : member.substring(member.indexOf('('));
			return find(owner, name, desc, field, new LinkedHashSet<>());
		}

		private boolean find(String owner, String name, String desc, boolean field, Set<String> seen) {
			if (owner == null || !seen.add(owner)) return false;
			ClassNode node = load(owner);
			if (node == null) return false;
			if (field ? node.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals(desc))
					: node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc))) return true;
			if (find(node.superName, name, desc, field, seen)) return true;
			for (String parent : node.interfaces) if (find(parent, name, desc, field, seen)) return true;
			return false;
		}

		private ClassNode load(String owner) {
			return cache.computeIfAbsent(owner, name -> {
				byte[] bytes = null;
				for (Function<String, byte[]> jar : jars) if ((bytes = jar.apply(name + ".class")) != null) break;
				if (bytes == null) {
					try (InputStream in = ClassLoader.getSystemResourceAsStream(name + ".class")) {
						if (in != null) bytes = in.readAllBytes();
					} catch (java.io.IOException unreadable) {
						return null;
					}
				}
				if (bytes == null) return null;
				ClassNode node = new ClassNode();
				new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
				return node;
			});
		}
	}
}
