package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The woven guest delegate and concrete native overrides keep their original dispatch and callback counts. */
class MixinDefaultPredicateAdapterWeaveTest {
 @TempDir Path work;
 @Test void finalDefinedDefaultsAndConcreteOverridesChooseTheCorrectPath() throws Exception {
  Path entities=source("Models.java", """
   package fixture.flags;
   public class Models {
    public static int guestCalls;
    public static class World {public int calls;public int value(){calls++;return 1;}}
    public static class Position { }
    public static class State { }
    public interface NativeDefaults {
     default int bits(World world,Position position,State state){return world.value();}
     default boolean matches(World world,Position position,State state,int mask){return (projection().bits(world,position,state)&mask)!=0;}
     private Core projection(){return (Core)this;}
    }
    public interface GuestDefaults {
     default int bits(World world,Position position,State state,double random){return ((Core)this).bits(world,position,state);}
     default boolean matches(World world,Position position,State state,double random,int mask){return (bits(world,position,state,random)&mask)!=0;}
    }
    public interface Core extends NativeDefaults,GuestDefaults {
     int bits();default boolean matches(int mask){return (bits()&mask)!=0;}
    }
    public static class Ordinary implements Core {public int bits(){return 0;}}
    public static class Guest extends Ordinary {public int calls;public int bits(World world,Position position,State state,double random){calls++;return 1;}}
    public static class Native extends Guest {public int nativeCalls;public boolean matches(World world,Position position,State state,int mask){nativeCalls++;return false;}}
   }
   """);
  Path host=source("Host.java", """
   package fixture.flags;import fixture.flags.Models.*;
   public class Host {
    public static boolean draw(Core model,World world,Position position,State state,int mask,double random){return model.matches(world,position,state,mask);}
    public static String probe(){World world=new World();Position pos=new Position();State state=new State();Ordinary ordinary=new Ordinary();Guest guest=new Guest();Native nativeModel=new Native();
     boolean a=draw(ordinary,world,pos,state,1,2.5),b=draw(guest,world,pos,state,1,2.5),c=draw(nativeModel,world,pos,state,1,2.5);
     return a+":"+b+":"+c+";source="+Models.guestCalls+";context="+world.calls+";guest="+guest.calls+";native="+nativeModel.nativeCalls+";shadowed="+nativeModel.calls;}
   }
   """);
  Path mixin=source("FlagsMixin.java", """
   package fixture.flags.mixin;
   import fixture.flags.*;import fixture.flags.Models.*;
   import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;
   import com.llamalad7.mixinextras.sugar.Local;
   @Mixin(Host.class) public class FlagsMixin {
    @Redirect(method="draw",at=@At(value="INVOKE",target="Lfixture/flags/Models$Core;matches(I)Z"),require=0)
    private static boolean choose(Core model,int mask,@Local(argsOnly=true) World world,@Local(argsOnly=true) Position position,@Local(argsOnly=true) State state,@Local(argsOnly=true) double random){
     Models.guestCalls++;return model.matches(world,position,state,random,mask);
    }
   }
   """);
  String config="default-flags.mixins.json";Path json=source(config,"""
   {"required":false,"compatibilityLevel":"JAVA_21","package":"fixture.flags.mixin","mixins":["FlagsMixin"],"injectors":{"defaultRequire":0}}
   """);
  Path fixture=WeaveHarness.fixture(work,"default-flags",List.of(entities,host,mixin),Map.of(config,json),List.of("-g"));
  var result=WeaveHarness.run(work,"on",fixture,config,"defaultflags",Ecosystem.FABRIC,EnvType.CLIENT,"fixture.flags.Host","probe",Map.of());
  assertTrue(result.printed(WeaveHarnessMain.DONE+" true:true:false;source=2;context=1;guest=1;native=1;shadowed=0"),result.describe());
  WeaveHarness.assertWovenAndVerified(result,"fixture/flags/Host",fixture);
  var control=WeaveHarness.run(work,"off",fixture,config,"defaultflags",Ecosystem.FABRIC,EnvType.CLIENT,"fixture.flags.Host","probe",Map.of("forbric.mixinDefaultPredicates","off"));
  assertTrue(control.printed(WeaveHarnessMain.DONE+" true:true:false;source=0;context=2;guest=0;native=1;shadowed=0"),control.describe());
 }
 private Path source(String name,String text)throws Exception{Path path=work.resolve(name);Files.writeString(path,text);return path;}
}
