package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import net.fabricmc.api.EnvType;
import net.forbric.api.CompatibilityFindings;
import net.forbric.kernel.fabric.FabricModDiscovery;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;

@ResourceLock("ModCatalog") @ResourceLock("system-properties")
class NestedCandidateSelectionTest {
	@TempDir Path root;
	@BeforeEach @AfterEach void reset() {
		DuplicateModArbiter.reset(); MultiLoaderArbiter.reset(); CompatibilityFindings.reset();
		for (String property : List.of("forbric.modOwner", "forbric.dupeIdPreference", "forbric.nestedDupePreference",
				"forbric.multiLoaderPreference", "forbric.arbitrationMaxNodes", "forbric.arbitrationTimeoutMillis")) System.clearProperty(property);
	}
	private Path mods() throws Exception { Path mods = root.resolve("mods"); Files.createDirectories(mods); return mods; }
	private Path install(String name, byte[] bytes) throws Exception { Path path = mods().resolve(name); Files.write(path, bytes); return path; }
	private DuplicateModArbiter.Decision decide() throws Exception { return DuplicateModArbiter.arbitrate(mods(), EnvType.CLIENT); }

	@Test void sameBasenameWithDifferentContentGetsTwoStablePhysicalFiles() throws Exception {
		byte[] a = fabric("lib_a", "1", Map.of(), "", Map.of());
		byte[] b = fabric("lib_b", "1", Map.of(), "", Map.of());
		Path first = install("a.jar", fabric("a", "1", Map.of("META-INF/jars/shared.jar", a), "", Map.of()));
		Path second = install("b.jar", fabric("b", "1", Map.of("META-INF/jars/shared.jar", b), "", Map.of()));
		String firstHash = NestedCandidateInventory.digest(first), secondHash = NestedCandidateInventory.digest(second);
		decide(); NestedCandidatePlan plan = DuplicateModArbiter.currentPlan();
		assertEquals(2, plan.nestedFiles().size());
		assertNotEquals(plan.nestedFiles().get(0).getParent(), plan.nestedFiles().get(1).getParent());
		assertTrue(plan.nestedFiles().stream().allMatch(p -> p.getFileName().toString().equals("shared.jar")));
		assertEquals(firstHash, NestedCandidateInventory.digest(first)); assertEquals(secondHash, NestedCandidateInventory.digest(second));
		FabricModDiscovery discovery = new FabricModDiscovery(EnvType.CLIENT, root.resolve("legacy-cache")); discovery.discover(mods());
		assertEquals(Set.of("a", "b", "lib_a", "lib_b"), discovery.getContainers().stream().map(c -> c.getMetadata().getId()).collect(java.util.stream.Collectors.toSet()));
		assertTrue(plan.verify(discovery.getClasspathJars()));
		assertFalse(Files.exists(root.resolve("legacy-cache")), "planned discovery must not extract through the old name/size cache");
	}

	@Test void oneSharedChildKeepsBothParentEdgesButIsLoadedOnce() throws Exception {
		byte[] shared = fabric("shared", "1", Map.of(), "", Map.of());
		install("a.jar", fabric("a", "1", Map.of("META-INF/jars/shared.jar", shared), "", Map.of()));
		install("b.jar", fabric("b", "1", Map.of("META-INF/jars/shared.jar", shared), "", Map.of()));
		decide(); var plan = DuplicateModArbiter.currentPlan();
		assertEquals(1, plan.nestedFiles().size());
		Path child = plan.nestedFiles().getFirst();
		assertEquals(2, plan.inventory().edges().stream().filter(e -> e.child().equals(child)).count());
		FabricModDiscovery discovery = new FabricModDiscovery(EnvType.CLIENT, root.resolve("old")); discovery.discover(mods());
		assertEquals(1, discovery.getContainers().stream().filter(c -> c.getMetadata().getId().equals("shared")).count());
	}

	@Test void aChildOfAnUnselectedParentCannotActivateItsParentOrLeakOntoTheClasspath() throws Exception {
		Path neo = install("host-neo.jar", neo("host", "1", Map.of(), Map.of(), Map.of()));
		Path fabric = install("host-fabric.jar", fabric("host", "1",
				Map.of("META-INF/jars/ghost.jar", fabric("ghost", "1", Map.of(), "", Map.of())), "", Map.of()));
		var decision = decide(); var plan = DuplicateModArbiter.currentPlan();
		assertFalse(decision.suppressed(neo)); assertTrue(decision.suppressed(fabric));
		assertTrue(plan.nestedFiles().isEmpty());
		assertTrue(plan.inventory().nodes().values().stream().anyMatch(n -> n.claim() != null && n.claim().modIds().contains("ghost")));
		FabricModDiscovery discovery = new FabricModDiscovery(EnvType.CLIENT, root.resolve("old")); discovery.discover(mods());
		assertTrue(discovery.getContainers().isEmpty());
	}

	@Test void aSodiumStyleWrapperAndSameIdPayloadStayTogetherWithoutCompetingForTheIdentity() throws Exception {
		byte[] payload = neo("sodium", "1", Map.of(), Map.of(), Map.of("payload.marker", new byte[] {1}));
		Path wrapper = install("sodium.jar", neo("sodium", "1", Map.of("META-INF/jarjar/implementation.jar", payload), Map.of(), Map.of()));
		var decision = decide(); var plan = DuplicateModArbiter.currentPlan();
		assertEquals(JointCandidateSelector.Status.SOLVED, plan.selection().status());
		assertFalse(decision.suppressed(wrapper)); assertEquals(1, plan.nestedFiles().size());
		assertFalse(decision.suppressed(plan.nestedFiles().getFirst()));
		assertTrue(plan.inventory().edges().getFirst().payload());
		assertTrue(plan.verify(plan.nestedFiles()));
		try (ZipFile zip = new ZipFile(plan.nestedFiles().getFirst().toFile())) { assertNotNull(zip.getEntry("payload.marker")); }
	}

	@Test void aNestedDependencyCanForceChangingTheTopLevelWinner() throws Exception {
		Path preferred = install("host-neo.jar", neo("host", "1", Map.of("META-INF/jarjar/shared.jar",
				neo("shared", "1", Map.of(), Map.of(), Map.of())), Map.of(), Map.of()));
		Path alternative = install("host-fabric.jar", fabric("host", "1", Map.of("META-INF/jars/shared.jar",
				fabric("shared", "2", Map.of(), "", Map.of())), "", Map.of()));
		install("consumer.jar", fabric("consumer", "1", Map.of(), ",\"depends\":{\"shared\":\">=2\"}", Map.of()));
		var decision = decide(); var plan = DuplicateModArbiter.currentPlan();
		assertEquals(JointCandidateSelector.Status.SOLVED, plan.selection().status());
		assertTrue(decision.suppressed(preferred)); assertFalse(decision.suppressed(alternative));
		assertEquals("2", plan.inventory().nodes().get(plan.nestedFiles().getFirst()).claim().versionOf("shared"));
	}

	@Test void jarJarRangesChooseOneArtifactAndActualDiscoveryDoesNotChooseTheHighestAgain() throws Exception {
		byte[] one = bytes(Map.of("version.txt", "one".getBytes(StandardCharsets.UTF_8)));
		byte[] two = bytes(Map.of("version.txt", "two".getBytes(StandardCharsets.UTF_8)));
		install("a.jar", neo("a", "1", Map.of("META-INF/jarjar/lib-1.jar", one),
				Map.of("META-INF/jarjar/lib-1.jar", new NestedCandidateInventory.Coordinate("example:lib", "[1,2)", "1")), Map.of()));
		install("b.jar", neo("b", "1", Map.of("META-INF/jarjar/lib-2.jar", two),
				Map.of("META-INF/jarjar/lib-2.jar", new NestedCandidateInventory.Coordinate("example:lib", "[1,3)", "2")), Map.of()));
		var decision = decide(); var plan = DuplicateModArbiter.currentPlan();
		assertEquals(JointCandidateSelector.Status.SOLVED, plan.selection().status());
		assertEquals(1, plan.nestedFiles().size()); assertEquals("lib-1.jar", plan.nestedFiles().getFirst().getFileName().toString());
		System.setProperty("forbric.nestedDupePreference", "fabric,minecraftforge,neoforge");
		assertSame(decision, DuplicateModArbiter.arbitrateNested(EnvType.CLIENT, plan.nestedFiles()));
		assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
	}

	@Test void aFabricParentAlsoDiscoversJarJarMetadataPathsOutsideTheUsualDirectories() throws Exception {
		String path = "private-libraries/unusual-location.jar";
		String metadata = "{\"jars\":[{\"path\":\"" + path + "\",\"identifier\":{\"group\":\"example\",\"artifact\":\"library\"},"
				+ "\"version\":{\"range\":\"[2,3)\",\"artifactVersion\":\"2\"}}]}";
		install("fabric-parent.jar", fabric("parent", "1", Map.of(), ",\"depends\":{\"library\":\">=2\"}",
				Map.of(path, fabric("library", "2", Map.of(), "", Map.of()),
						"META-INF/jarjar/metadata.json", metadata.getBytes(StandardCharsets.UTF_8))));
		decide(); var plan = DuplicateModArbiter.currentPlan();
		assertEquals(JointCandidateSelector.Status.SOLVED, plan.selection().status());
		assertEquals(1, plan.nestedFiles().size(), "JarJar declarations do not depend on which ecosystem owns the parent");
		assertEquals(path, plan.inventory().edges().getFirst().entry());
		FabricModDiscovery discovery = new FabricModDiscovery(EnvType.CLIENT, root.resolve("legacy")); discovery.discover(mods());
		assertEquals(Set.of("parent", "library"), discovery.getContainers().stream().map(c -> c.getMetadata().getId()).collect(java.util.stream.Collectors.toSet()));
		assertTrue(plan.verify(discovery.getClasspathJars()));
	}

	@Test void aRootCannotSupplyAClassThatOnlyItsSuppressedNestedCandidateContains() throws Exception {
		byte[] only = type("dep/OnlyInFabric");
		byte[] helper = fabric("helper", "1", Map.of(), "", Map.of("dep/OnlyInFabric.class", only));
		Path preferred = install("dep-neo.jar", neo("dep", "1", Map.of("META-INF/jarjar/helper.jar", helper), Map.of(), Map.of()));
		Path alternative = install("dep-fabric.jar", fabric("dep", "1", Map.of(), "", Map.of("dep/OnlyInFabric.class", only)));
		install("helper-neo.jar", neo("helper", "1", Map.of(), Map.of(), Map.of()));
		String config = "{\"required\":true,\"package\":\"app.mixin\",\"mixins\":[\"Need\"]}";
		install("app.jar", fabric("app", "1", Map.of(), ",\"depends\":{\"dep\":\"*\"},\"mixins\":[\"need.mixins.json\"]",
				Map.of("need.mixins.json", config.getBytes(StandardCharsets.UTF_8), "app/mixin/Need.class", mixin("app/mixin/Need", "dep/OnlyInFabric"))));
		System.setProperty("forbric.modOwner", "helper=neoforge");
		var decision = decide();
		assertTrue(decision.suppressed(preferred)); assertFalse(decision.suppressed(alternative));
		assertEquals(JointCandidateSelector.Status.SOLVED, DuplicateModArbiter.currentPlan().selection().status());
	}

	@Test void replacingASelectedCacheFileIsDetectedWithoutReArbitrating() throws Exception {
		install("a.jar", fabric("a", "1", Map.of("META-INF/jars/child.jar", fabric("child", "1", Map.of(), "", Map.of())), "", Map.of()));
		var decision = decide(); var plan = DuplicateModArbiter.currentPlan(); Path selected = plan.nestedFiles().getFirst();
		Files.write(selected, fabric("different", "2", Map.of(), "", Map.of()));
		assertFalse(plan.verify(plan.nestedFiles()));
		assertSame(decision, DuplicateModArbiter.arbitrateNested(EnvType.CLIENT, plan.nestedFiles()));
		assertTrue(CompatibilityFindings.confirmedRequired().stream().anyMatch(f -> f.id().equals("arbitration:materialization")));
	}

	private static byte[] fabric(String id, String version, Map<String, byte[]> children, String extra, Map<String, byte[]> resources) throws Exception {
		Map<String, byte[]> all = new LinkedHashMap<>(resources); all.putAll(children);
		String jars = String.join(",", children.keySet().stream().map(name -> "{\"file\":\"" + name + "\"}").toList());
		all.put("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version + "\",\"jars\":[" + jars + "]" + extra + "}").getBytes(StandardCharsets.UTF_8));
		return bytes(all);
	}
	private static byte[] neo(String id, String version, Map<String, byte[]> children, Map<String, NestedCandidateInventory.Coordinate> coordinates, Map<String, byte[]> resources) throws Exception {
		Map<String, byte[]> all = new LinkedHashMap<>(resources); all.putAll(children);
		all.put("META-INF/neoforge.mods.toml", ("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + id + "\"\nversion=\"" + version + "\"\n").getBytes(StandardCharsets.UTF_8));
		if (!coordinates.isEmpty()) {
			List<String> entries = new ArrayList<>();
			for (var entry : coordinates.entrySet()) { String[] parts = entry.getValue().id().split(":", 2);
				entries.add("{\"path\":\"" + entry.getKey() + "\",\"identifier\":{\"group\":\"" + parts[0] + "\",\"artifact\":\"" + parts[1] + "\"},\"version\":{\"range\":\"" + entry.getValue().range() + "\",\"artifactVersion\":\"" + entry.getValue().version() + "\"}}"); }
			all.put("META-INF/jarjar/metadata.json", ("{\"jars\":[" + String.join(",", entries) + "]}").getBytes(StandardCharsets.UTF_8));
		}
		return bytes(all);
	}
	private static byte[] bytes(Map<String, byte[]> resources) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			for (var entry : new TreeMap<>(resources).entrySet()) { ZipEntry part = new ZipEntry(entry.getKey()); part.setTime(0); zip.putNextEntry(part); zip.write(entry.getValue()); zip.closeEntry(); }
		}
		return bytes.toByteArray();
	}
	private static byte[] type(String name) { ClassWriter writer = new ClassWriter(0); writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null); writer.visitEnd(); return writer.toByteArray(); }
	private static byte[] mixin(String name, String target) {
		ClassWriter writer = new ClassWriter(0); writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		var annotation = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false); var values = annotation.visitArray("value");
		values.visit(null, Type.getObjectType(target)); values.visitEnd(); annotation.visitEnd(); writer.visitEnd(); return writer.toByteArray();
	}
}
