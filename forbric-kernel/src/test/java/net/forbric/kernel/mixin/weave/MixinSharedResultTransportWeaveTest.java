package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinSharedResultTransport;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

/** Actual MixinExtras shared producer/consumer lifecycle, with the carrier-only off control. */
class MixinSharedResultTransportWeaveTest {
    @TempDir static Path work;
    private static WeaveHarness.Result on, off;
    private static Path fixture;
    private static WeaveHarness.Result closedOn, closedOff;
    private static Path closedFixture;
    private static final String OWNER = "fixture/sharedresult/Operator";

    @BeforeAll static void weaveBoth() throws Exception {
        Path source = Files.createDirectories(work.resolve("source"));
        List<Path> common = new ArrayList<>();
        common.add(write(source, "Snapshot.java", """
            package fixture.sharedresult;
            public final class Snapshot {
              public static int copies; public int count,reads,projections; public String component; public boolean copied;
              public Snapshot(int count,String component){this.count=count;this.component=component;}
              public Snapshot copy(){copies++;Snapshot s=new Snapshot(count,component);s.copied=true;return s;}
              public Holder holder(){projections++;return new Holder(this);}
              public Result query(){reads++;return new Result(this);}
            }
            """));
        common.add(write(source, "Holder.java", """
            package fixture.sharedresult;
            public final class Holder { private final Snapshot source; public Holder(Snapshot s){source=s;}
              public Result oldResult(){return source.query();} }
            """));
        common.add(write(source, "Result.java", """
            package fixture.sharedresult;
            public final class Result {private final Snapshot source;public Result(Snapshot s){source=s;}
              public String describe(){return source.count+":"+source.component+":"+source.copied+":"+source.reads;} }
            """));
        common.add(write(source, "Collector.java", """
            package fixture.sharedresult;
            public final class Collector { public static String accept(String value){return value;} }
            """));
        String header = "package fixture.sharedresult; public class Operator { public String evaluate(Snapshot fuel){ Holder old=fuel.holder(); fuel.count=0;fuel.component=\"changed\";";
        String consumer = "String value=result==null?\"empty\":result.describe();return Collector.accept(value);}";
        Path originalSource = write(work.resolve("original"), "Operator.java", header + "Result result=old.oldResult();" + consumer + "}");
        List<Path> referenceSources = new ArrayList<>(common); referenceSources.add(originalSource);
        Path referenceJar = WeaveHarness.fixture(work, "reference", referenceSources, Map.of());
        byte[] original;
        try (java.util.zip.ZipFile jar = new java.util.zip.ZipFile(referenceJar.toFile())) {
            original = jar.getInputStream(jar.getEntry(OWNER + ".class")).readAllBytes();
        }
        Path originalBytes = work.resolve("original.class.bin"); Files.write(originalBytes, original);
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original));
        Path index = work.resolve("index.tsv"); Files.writeString(index, "# forbric-native-reference-v1\n" + OWNER + "\t" + hash + "\n");
        Path target = write(source, "Operator.java", header + "Result result=fuel.query();" + consumer + """
            public String probe(){Snapshot.copies=0;Snapshot s=new Snapshot(1,"initial");return evaluate(s)+"|liveReads="+s.reads+"|copies="+Snapshot.copies;}}
            """);
        Path mixin = write(source, "SharedSnapshotMixin.java", """
            package fixture.sharedresult.mixin;
            import fixture.sharedresult.*;
            import org.spongepowered.asm.mixin.Mixin;
            import org.spongepowered.asm.mixin.injection.*;
            import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
            import com.llamalad7.mixinextras.sugar.Share;
            import com.llamalad7.mixinextras.sugar.ref.LocalRef;
            @Mixin(Operator.class) public class SharedSnapshotMixin {
              @Inject(method="evaluate",at=@At("HEAD"))
              private static void capture(Snapshot fuel,CallbackInfoReturnable<String> ci,@Share("copy") LocalRef<Snapshot> ref){ref.set(fuel.copy());}
              @Redirect(method="evaluate",at=@At(value="INVOKE",target="Lfixture/sharedresult/Holder;oldResult()Lfixture/sharedresult/Result;"),require=0)
              private static Result value(Holder unused,@Share("copy") LocalRef<Snapshot> ref){return ref.get().query();}
            }
            """);
        Path config = work.resolve("shared.mixins.json");
        Files.writeString(config, "{\"required\":true,\"package\":\"fixture.sharedresult.mixin\",\"compatibilityLevel\":\"JAVA_21\",\"mixins\":[\"SharedSnapshotMixin\"],\"injectors\":{\"defaultRequire\":0}}");
        List<Path> sources = new ArrayList<>(common); sources.add(target); sources.add(mixin);
        fixture = WeaveHarness.fixture(work, "shared", sources, Map.of("shared.mixins.json", config,
                "META-INF/forbric/native-reference/FABRIC/index.tsv", index,
                "META-INF/forbric/native-reference/FABRIC/" + OWNER + ".class.bin", originalBytes));
        on = run("on", Map.of()); off = run("off", Map.of(MixinSharedResultTransport.PROPERTY, "off"));
        weaveClosed(common, consumer);
    }

    private static void weaveClosed(List<Path> common, String consumer) throws Exception {
        String header = "package fixture.sharedresult; public interface Operator { default String evaluate(Snapshot fuel){";
        Path originalSource = write(work.resolve("closed-original"), "Operator.java",
                header + "Holder old=fuel.holder();Result result=old.oldResult();" + consumer + "}");
        List<Path> referenceSources = new ArrayList<>(common); referenceSources.add(originalSource);
        Path referenceJar = WeaveHarness.fixture(work, "closed-reference", referenceSources, Map.of());
        byte[] original;
        try (java.util.zip.ZipFile jar = new java.util.zip.ZipFile(referenceJar.toFile())) {
            original = jar.getInputStream(jar.getEntry(OWNER + ".class")).readAllBytes();
        }
        Path originalBytes = work.resolve("closed-original.class.bin"); Files.write(originalBytes, original);
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original));
        Path index = work.resolve("closed-index.tsv");
        Files.writeString(index, "# forbric-native-reference-v1\n" + OWNER + "\t" + hash + "\n");
        Path source = Files.createDirectories(work.resolve("closed-source"));
        Path target = write(source, "Operator.java", header + "Result result=fuel.query();" + consumer + "}");
        Path probe = write(source, "Probe.java", """
            package fixture.sharedresult;
            public class Probe implements Operator {
              public String probe(){Snapshot s=new Snapshot(3,"initial");return evaluate(s)+"|projections="+s.projections;}}
            """);
        Path mixin = write(source, "ClosedShareMixin.java", """
            package fixture.sharedresult.closed;
            import fixture.sharedresult.*;
            import org.spongepowered.asm.mixin.Mixin;
            import org.spongepowered.asm.mixin.injection.*;
            import com.llamalad7.mixinextras.injector.wrapoperation.*;
            import com.llamalad7.mixinextras.sugar.Share;
            import com.llamalad7.mixinextras.sugar.ref.LocalRef;
            @Mixin(Operator.class) public interface ClosedShareMixin {
              @WrapOperation(method="evaluate",at=@At(value="INVOKE",target="Lfixture/sharedresult/Snapshot;holder()Lfixture/sharedresult/Holder;"),require=0)
              private static Holder capture(Snapshot receiver,Operation<Holder> original,@Share("identity") LocalRef<Snapshot> ref){ref.set(receiver);return original.call(receiver);}
              @Redirect(method="evaluate",at=@At(value="INVOKE",target="Lfixture/sharedresult/Holder;oldResult()Lfixture/sharedresult/Result;"),require=0)
              private static Result value(Holder unused,@Share("identity") LocalRef<Snapshot> ref){return ref.get().query();}
            }
            """);
        Path config = work.resolve("closed.mixins.json");
        Files.writeString(config, "{\"required\":true,\"package\":\"fixture.sharedresult.closed\",\"compatibilityLevel\":\"JAVA_21\",\"mixins\":[\"ClosedShareMixin\"],\"injectors\":{\"defaultRequire\":0}}");
        List<Path> sources = new ArrayList<>(common); sources.add(target); sources.add(probe); sources.add(mixin);
        closedFixture = WeaveHarness.fixture(work, "closed-shared", sources, Map.of("closed.mixins.json", config,
                "META-INF/forbric/native-reference/FABRIC/index.tsv", index,
                "META-INF/forbric/native-reference/FABRIC/" + OWNER + ".class.bin", originalBytes));
        closedOn = WeaveHarness.run(work, "closed-on", closedFixture, "closed.mixins.json", "closed-fixture", Ecosystem.FABRIC,
                EnvType.SERVER, "fixture.sharedresult.Probe", "probe", Map.of());
        closedOff = WeaveHarness.run(work, "closed-off", closedFixture, "closed.mixins.json", "closed-fixture", Ecosystem.FABRIC,
                EnvType.SERVER, "fixture.sharedresult.Probe", "probe", Map.of(MixinSharedResultTransport.PROPERTY, "off"));
    }

    @Test void sourceSharedCopyFeedsTheRealWovenRedirectOnce() throws Exception {
        assertTrue(on.printedLine(WeaveHarnessMain.DONE + " 1:initial:true:1|liveReads=0|copies=1"), on.describe());
        WeaveHarness.assertWovenAndVerified(on, OWNER, fixture);
    }
    @Test void offKeepsTheHeadCopyButCarrierReadsItsMutatedLiveInput() throws Exception {
        assertTrue(off.printedLine(WeaveHarnessMain.DONE + " 0:changed:false:1|liveReads=1|copies=1"), off.describe());
        WeaveHarness.assertWovenAndVerified(off, OWNER, fixture);
        assertFalse(on.output().equals(off.output()));
    }
    @Test void closedGroupRestoresTheSourceProjectionOnceBeforeTheOriginalConsumer() throws Exception {
        assertTrue(closedOn.printedLine(WeaveHarnessMain.DONE + " 3:initial:false:1|projections=1"), closedOn.describe());
        WeaveHarness.assertWovenAndVerified(closedOn, OWNER, closedFixture);
        ClassNode woven = new ClassNode(); new ClassReader(closedOn.defined(OWNER)).accept(woven, 0);
        for (String suffix : List.of("$forbricsharedproducer", "$forbricsharedresult")) {
            List<MethodNode> originals = woven.methods.stream().filter(method -> method.name.contains(suffix)).toList();
            assertEquals(1, originals.size(), "one preserved original callback " + suffix);
            MethodNode original = originals.getFirst();
            long calls = woven.methods.stream().flatMap(method -> Arrays.stream(method.instructions.toArray()))
                    .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                    .filter(call -> call.owner.equals(OWNER) && call.name.equals(original.name) && call.desc.equals(original.desc)).count();
            assertEquals(1, calls, "the woven wrapper invokes the original callback exactly once");
        }
    }
    @Test void offLeavesTheCarrierGetterWithoutRestoringTheSourceProjection() throws Exception {
        assertTrue(closedOff.printedLine(WeaveHarnessMain.DONE + " 3:initial:false:1|projections=0"), closedOff.describe());
        WeaveHarness.assertWovenAndVerified(closedOff, OWNER, closedFixture);
    }
    private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
        return WeaveHarness.run(work, label, fixture, "shared.mixins.json", "shared-fixture", Ecosystem.FABRIC,
                EnvType.SERVER, OWNER.replace('/', '.'), "probe", properties);
    }
    private static Path write(Path directory, String name, String source) throws Exception {
        Files.createDirectories(directory); Path path = directory.resolve(name); Files.writeString(path, source); return path;
    }
}
