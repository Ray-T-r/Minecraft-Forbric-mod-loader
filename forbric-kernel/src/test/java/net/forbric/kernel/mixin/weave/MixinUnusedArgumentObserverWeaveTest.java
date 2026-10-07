package net.forbric.kernel.mixin.weave;
import static org.junit.jupiter.api.Assertions.*;import java.nio.file.*;import java.util.*;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;
import net.fabricmc.api.EnvType;import net.forbric.api.Ecosystem;import net.forbric.kernel.mixin.MixinUnusedArgumentObserverAdapter;

class MixinUnusedArgumentObserverWeaveTest {
    @TempDir static Path work;private static Path fixture;private static WeaveHarness.Result on,off,override;
    @BeforeAll static void weave()throws Exception {
        Path source=Files.createDirectories(work.resolve("source"));List<Path> common=new ArrayList<>();
        common.add(write(source,"Tag.java","package fixture.observer;public final class Tag{}"));
        common.add(write(source,"Kind.java","package fixture.observer;public final class Kind{public final String name;public Kind(String name){this.name=name;}}"));
        common.add(write(source,"Holder.java","package fixture.observer;public interface Holder<T>{T value();}"));
        common.add(write(source,"Constants.java","""
            package fixture.observer;public class Constants {public static final Tag FIRST=new Tag(),SECOND=new Tag(),CUSTOM=new Tag();
              public static final Holder<Kind> FIRST_KIND=()->new Kind("first"),SECOND_KIND=()->new Kind("second"),SELECTED=()->new Kind("selected");}
            """));
        common.add(write(source,"Mapping.java","""
            package fixture.observer;public class Mapping {
              static Kind map(Tag tag){if(tag==Constants.FIRST)return Constants.FIRST_KIND.value();if(tag==Constants.SECOND)return Constants.SECOND_KIND.value();throw new IllegalArgumentException();}}
            """));
        String header="package fixture.observer;public class Actor {public int typedCalls,baseCalls;public String last;public boolean in=true,onGround=false,shallow=false;public Actor(){}"
                +"protected void legacy(Tag unused){baseCalls++;} public boolean in(){return in;}public boolean ground(){return onGround;}public boolean shallow(Tag tag){return height(tag)>0;}public double height(Tag tag){Mapping.map(tag);return shallow?1:0;} public void typed(Kind kind){typedCalls++;last=kind.name;}";
        Path original=write(work.resolve("original"),"Actor.java",header+"public void step(){if(in()&&(!ground()||!shallow(Constants.SECOND))){legacy(Constants.SECOND);}else{typed(Constants.SELECTED.value());}}}");
        List<Path> oldSources=new ArrayList<>(common);oldSources.add(original);Path nativeJar=WeaveHarness.fixture(work,"native",oldSources,Map.of());
        byte[] old;try(var jar=new java.util.zip.ZipFile(nativeJar.toFile())){old=jar.getInputStream(jar.getEntry("fixture/observer/Actor.class")).readAllBytes();}
        Path bytes=work.resolve("actor.bin");Files.write(bytes,old);String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(old));
        Path index=work.resolve("index.tsv");Files.writeString(index,"# forbric-native-reference-v1\nfixture/observer/Actor\t"+hash+"\n");
        Path current=write(source,"Actor.java",header+"""
            public void step(){if(!in()||ground()&&shallow(Constants.SECOND)){typed(Constants.SELECTED.value());}else{typed(Constants.SECOND_KIND.value());}}
            public String probe(){ProbeState.observerReads=0;step();return last+"|typed="+typedCalls+"|base="+baseCalls+"|observer="+ProbeState.observerReads;}}
            """);
        common.add(write(source,"ProbeState.java","package fixture.observer;public class ProbeState{public static int observerReads;}"));
        Path guest=write(source,"Guest.java","""
            package fixture.observer.mixin;import fixture.observer.*;import org.spongepowered.asm.mixin.Mixin;
            import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
            import com.llamalad7.mixinextras.sugar.*;import com.llamalad7.mixinextras.sugar.ref.*;import com.llamalad7.mixinextras.expression.*;
            @Mixin(Actor.class)public class Guest {
              @Inject(method="step",at=@At("HEAD"))private void share(CallbackInfo ci,@Share("picked")LocalRef<Tag> ref){ref.set(Constants.CUSTOM);}
              @ModifyArg(method="step",at=@At("MIXINEXTRAS:EXPRESSION"))
              @Definition(id="chosen",field="Lfixture/observer/Constants;SECOND:Lfixture/observer/Tag;")
              @Definition(id="old",method="Lfixture/observer/Actor;legacy(Lfixture/observer/Tag;)V")
              @Expression("this.old(chosen)")private Tag observe(Tag input,@Share("picked")LocalRef<Tag> ref){Tag custom=ref.get();return custom!=null?custom:input;}}
            """);
        String observer="observe$forbricobserver$"+Integer.toHexString("fixture/observer/mixin/Guest".hashCode());
        Path counter=write(source,"Counter.java","""
            package fixture.observer.counter;import fixture.observer.*;import org.spongepowered.asm.mixin.*;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;import com.llamalad7.mixinextras.sugar.ref.LocalRef;
            @Mixin(targets="fixture.observer.Actor")public class Counter {
              @Inject(method="%s",at=@At("HEAD"),require=0)private void count(Tag input,LocalRef<Tag> ref,CallbackInfoReturnable<Tag> ci){ProbeState.observerReads++;} }
            """.formatted(observer));
        Path special=write(source,"Special.java","package fixture.observer;public class Special extends Actor {protected void legacy(Tag tag){throw new AssertionError(\"retired override must not run\");} public void typed(Kind kind){typedCalls++;last=\"override:\"+kind.name;}}" );
        Path specialProbe=write(source,"SpecialProbe.java","package fixture.observer;public class SpecialProbe {public String probe(){Special actor=new Special();return actor.probe();}}" );
        Path config=write(source,"observer.mixins.json","{\"required\":true,\"package\":\"fixture.observer.mixin\",\"compatibilityLevel\":\"JAVA_21\",\"mixins\":[\"Guest\"],\"mixinextras\":{\"minVersion\":\"0.5.4\"},\"injectors\":{\"defaultRequire\":0}}");
        Path counterConfig=write(source,"counter.mixins.json","{\"required\":true,\"package\":\"fixture.observer.counter\",\"compatibilityLevel\":\"JAVA_21\",\"mixins\":[\"Counter\"],\"injectors\":{\"defaultRequire\":0}}");
        List<Path> sources=new ArrayList<>(common);sources.addAll(List.of(current,guest,counter,special,specialProbe));fixture=WeaveHarness.fixture(work,"observer",sources,Map.of("observer.mixins.json",config,"counter.mixins.json",counterConfig,"META-INF/forbric/native-reference/FABRIC/index.tsv",index,"META-INF/forbric/native-reference/FABRIC/fixture/observer/Actor.class.bin",bytes));
        on=run("on","fixture.observer.Actor",Map.of());off=run("off","fixture.observer.Actor",Map.of(MixinUnusedArgumentObserverAdapter.PROPERTY,"off"));override=run("override","fixture.observer.SpecialProbe",Map.of());
    }
    @Test void actualTypedArgumentAndVirtualOperationRemainOnce()throws Exception {
        assertTrue(on.printedLine(WeaveHarnessMain.DONE+" second|typed=1|base=0|observer=1"),on.describe());
        WeaveHarness.assertWovenAndVerified(on,"fixture/observer/Actor",fixture);
        org.objectweb.asm.tree.ClassNode node=new org.objectweb.asm.tree.ClassNode();new org.objectweb.asm.ClassReader(on.defined("fixture/observer/Actor")).accept(node,0);
        assertTrue(node.methods.stream().anyMatch(m->m.name.contains("$forbricobserver")),"original callback is preserved");
    }
    @Test void offAndConcreteOverridesRetainTheirNativeDispatch()throws Exception {
        assertTrue(off.printedLine(WeaveHarnessMain.DONE+" second|typed=1|base=0|observer=0"),off.describe());
        assertTrue(override.printedLine(WeaveHarnessMain.DONE+" override:second|typed=1|base=0|observer=0"),override.describe());
        WeaveHarness.assertWovenAndVerified(override,"fixture/observer/Actor",fixture);
    }
    private static WeaveHarness.Result run(String label,String type,Map<String,String> properties)throws Exception{return WeaveHarness.run(work,label,fixture,List.of(new WeaveHarness.Config("observer.mixins.json","observer",Ecosystem.FABRIC),new WeaveHarness.Config("counter.mixins.json","counter",Ecosystem.FABRIC)),List.of(),EnvType.SERVER,type,"probe",properties);}
    private static Path write(Path directory,String name,String source)throws Exception{Files.createDirectories(directory);Path path=directory.resolve(name);Files.writeString(path,source);return path;}
}
