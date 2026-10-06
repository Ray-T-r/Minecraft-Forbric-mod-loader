package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinDefaultCallbackTransport;

class MixinDefaultCallbackTransportWeaveTest {
    @TempDir static Path work;
    private static Path fixture; private static WeaveHarness.Result on, off;
    private static Path ordinaryFixture, serialFixture; private static WeaveHarness.Result ordinary, serial;
    private static final String CAPABILITY = "fixture/capability/Capability";
    @BeforeAll static void weave() throws Exception {
        Path source = Files.createDirectories(work.resolve("source")); List<Path> common = new ArrayList<>();
        common.add(write(source, "Property.java", "package fixture.capability; public class Property {}"));
        common.add(write(source, "Point.java", "package fixture.capability; public class Point {}"));
        common.add(write(source, "Block.java", "package fixture.capability; public class Block {}"));
        common.add(write(source, "Tags.java", "package fixture.capability; public class Tags {public static final Object SPECIAL=new Object();}"));
        common.add(write(source, "Cell.java", """
            package fixture.capability;
            public class Cell {public final Block block;public final boolean tagged;public final String direction;public int tagReads;
              public Cell(Block block,boolean tagged,String direction){this.block=block;this.tagged=tagged;this.direction=direction;}
              public Block block(){return block;} public boolean marked(Object tag){tagReads++;return tag==Tags.SPECIAL&&tagged;}
              public Comparable<?> property(Property property){return direction;}}
            """));
        common.add(write(source, "Realm.java", "package fixture.capability; public class Realm {public Cell lookup(Point point){return new Cell(new Natural(),true,\"east\");}}"));
        common.add(write(source, "Hatch.java", "package fixture.capability; public class Hatch {public static final Property DIRECTION=new Property();}"));
        common.add(write(source, "Capability.java", """
            package fixture.capability;
            public interface Capability {default boolean permitted(Cell lower,Realm world,Point position,Cell upper){
              return lower.block() instanceof Rung && lower.property(Rung.DIRECTION)==upper.property(Hatch.DIRECTION);}}
            """));
        common.add(write(source, "Rung.java", """
            package fixture.capability;
            public class Rung extends Block implements Capability {public static final Property DIRECTION=new Property();
              public boolean permitted(Cell lower,Realm world,Point position,Cell upper){return Capability.super.permitted(lower,world,position,upper);}}
            """));
        common.add(write(source, "Natural.java", "package fixture.capability; public class Natural extends Block implements Capability {}"));
        common.add(write(source, "Override.java", """
            package fixture.capability;
            public class Override extends Natural {public int calls;public final boolean answer;
              public Override(boolean answer){this.answer=answer;}
              public boolean permitted(Cell lower,Realm world,Point position,Cell upper){calls++;return answer;}}
            """));
        String gate = "private boolean gate(Point point,Cell current){Cell below=new Realm().lookup(point);return below.block() instanceof Rung;}";
        Path original = write(work.resolve("original"), "Actor.java", "package fixture.capability; public class Actor {" + gate + "public boolean active(Point point,Cell cell){return gate(point,cell);}}");
        List<Path> oldSources = new ArrayList<>(common); oldSources.add(original);
        Path nativeJar = WeaveHarness.fixture(work, "native", oldSources, Map.of(), List.of("-g"));
        String actor = "fixture/capability/Actor"; byte[] originalBytes;
        try (var jar = new java.util.zip.ZipFile(nativeJar.toFile())) { originalBytes=jar.getInputStream(jar.getEntry(actor+".class")).readAllBytes(); }
        Path bytes = work.resolve("actor.bin"); Files.write(bytes, originalBytes);
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(originalBytes));
        Path index = work.resolve("index.tsv"); Files.writeString(index, "# forbric-native-reference-v1\n" + actor + "\t" + hash + "\n");
        Path current = write(source, "Actor.java", "package fixture.capability; public class Actor {" + gate + "public boolean active(Point point,Cell cell){return false;}}");
        Path mixin = write(source, "SourceMixin.java", """
            package fixture.capability.mixin;
            import fixture.capability.*;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;
            import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;import com.llamalad7.mixinextras.sugar.Local;
            @Mixin(Actor.class) public abstract class SourceMixin {
              @Inject(method="gate",at=@At(value="INVOKE_ASSIGN",target="Lfixture/capability/Realm;lookup(Lfixture/capability/Point;)Lfixture/capability/Cell;"),cancellable=true,allow=1)
              private void expand(Point point,Cell current,CallbackInfoReturnable<Boolean> info,@Local(name="below") Cell below){
                if(below.marked(Tags.SPECIAL)){if(below.block() instanceof Rung){if(below.property(Rung.DIRECTION)==current.property(Hatch.DIRECTION))info.setReturnValue(true);}else info.setReturnValue(true);}}
            }
            """);
        Path probe = write(source, "Probe.java", """
            package fixture.capability;
            public class Probe {
              private boolean ask(Cell lower,Cell upper){return ((Capability)lower.block()).permitted(lower,new Realm(),new Point(),upper);}
              public String probe(){Cell upper=new Cell(new Natural(),false,"east");
                Cell tagged=new Cell(new Natural(),true,"east"),untagged=new Cell(new Natural(),false,"east");
                Cell aligned=new Cell(new Rung(),true,"east"),wrong=new Cell(new Rung(),true,"west");
                Override reject=new Override(false),accept=new Override(true);
                Cell rejected=new Cell(reject,true,"east"),accepted=new Cell(accept,true,"east");
                return ask(tagged,upper)+"|"+ask(untagged,upper)+"|"+ask(aligned,upper)+"|"+ask(wrong,upper)
                  +"|"+ask(rejected,upper)+":"+reject.calls+":"+rejected.tagReads
                  +"|"+ask(accepted,upper)+":"+accept.calls+":"+accepted.tagReads+"|tagReads="+tagged.tagReads;}}
            """);
        Path config = work.resolve("capability.mixins.json");
        Files.writeString(config, "{\"required\":true,\"package\":\"fixture.capability.mixin\",\"compatibilityLevel\":\"JAVA_21\",\"mixins\":[\"SourceMixin\"],\"injectors\":{\"defaultRequire\":0}}");
        List<Path> sources = new ArrayList<>(common); sources.addAll(List.of(current,mixin,probe));
        fixture=WeaveHarness.fixture(work,"capability",sources,Map.of("capability.mixins.json",config,
                "META-INF/forbric/native-reference/FABRIC/index.tsv",index,"META-INF/forbric/native-reference/FABRIC/"+actor+".class.bin",bytes),List.of("-g"));
        on=run("on",Map.of());off=run("off",Map.of(MixinDefaultCallbackTransport.PROPERTY,"off"));
        for (boolean serialized : List.of(false, true)) {
            Path variant = Files.createDirectories(work.resolve(serialized ? "serial-source" : "ordinary-source"));
            String reference = serialized ? "(java.util.function.BiPredicate<Point,Cell>&java.io.Serializable)this::gate" : "this::gate";
            Path actorSource = write(variant, "Actor.java", "package fixture.capability; public class Actor {" + gate
                    + "public boolean active(Point point,Cell cell){return false;} public java.util.function.BiPredicate<Point,Cell> reference(){return " + reference + ";}}");
            Path referenceProbe = write(variant, "ReferenceProbe.java", """
                package fixture.capability;
                public class ReferenceProbe {public String probe(){return "methodref="+new Actor().reference().test(new Point(),new Cell(new Natural(),false,"east"));}}
                """);
            List<Path> variantSources = new ArrayList<>(common); variantSources.addAll(List.of(actorSource,mixin,referenceProbe));
            Path variantFixture = WeaveHarness.fixture(work,serialized ? "serial-reference" : "ordinary-reference",variantSources,Map.of("capability.mixins.json",config,
                    "META-INF/forbric/native-reference/FABRIC/index.tsv",index,"META-INF/forbric/native-reference/FABRIC/"+actor+".class.bin",bytes),List.of("-g"));
            WeaveHarness.Result result = WeaveHarness.run(work,serialized ? "serial" : "ordinary",variantFixture,"capability.mixins.json","capability-reference",
                    Ecosystem.FABRIC,EnvType.SERVER,"fixture.capability.ReferenceProbe","probe",Map.of());
            if (serialized) { serialFixture = variantFixture; serial = result; } else { ordinaryFixture = variantFixture; ordinary = result; }
        }
    }
    @Test void realDefaultHeadInvokesSourceCallbackAndNativeOverridesStayAuthoritative() throws Exception {
        assertTrue(on.printedLine(WeaveHarnessMain.DONE+" true|false|true|false|false:1:0|true:1:0|tagReads=1"),on.describe());
        WeaveHarness.assertWovenAndVerified(on,CAPABILITY,fixture);
    }
    @Test void offProvesTheTagExtensionWasMissingWhileNativeCapabilitiesStillWork() throws Exception {
        assertTrue(off.printedLine(WeaveHarnessMain.DONE+" false|false|true|false|false:1:0|true:1:0|tagReads=0"),off.describe());
        WeaveHarness.assertWovenAndVerified(off,CAPABILITY,fixture);
    }
    @Test void anOrdinaryMethodReferenceStillExecutesTheCallbackOnItsOriginalPrivateHelper() throws Exception {
        assertTrue(ordinary.printedLine(WeaveHarnessMain.DONE+" methodref=true"),ordinary.describe());
        WeaveHarness.assertWovenAndVerified(ordinary,"fixture/capability/Actor",ordinaryFixture);
    }
    @Test void aSerializableMethodReferenceStillExecutesTheCallbackOnItsOriginalPrivateHelper() throws Exception {
        assertTrue(serial.printedLine(WeaveHarnessMain.DONE+" methodref=true"),serial.describe());
        WeaveHarness.assertWovenAndVerified(serial,"fixture/capability/Actor",serialFixture);
    }
    private static WeaveHarness.Result run(String label,Map<String,String> properties)throws Exception{return WeaveHarness.run(work,label,fixture,"capability.mixins.json","capability-fixture",Ecosystem.FABRIC,EnvType.SERVER,"fixture.capability.Probe","probe",properties);}
    private static Path write(Path dir,String name,String source)throws Exception{Files.createDirectories(dir);Path file=dir.resolve(name);Files.writeString(file,source);return file;}
}
