package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinDefaultPredicateTransport;

/** A renamed original/current pair passes through real Mixin, final-definition witnesses and instance Lambda remapping. */
class MixinDefaultPredicateTransportWeaveTest {
	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result adapted,off;
	private static final String CONFIG="predicate-transport.mixins.json",HOST="fixture/predicate/Host";

	@BeforeAll static void weave()throws Exception{
		Path source=Files.createDirectories(work.resolve("source"));
		Path entities=write(source,"Entities.java","""
				package fixture.predicate;
				public final class Entities {
				 public static int calls,nativeCalls;
				 public interface FrameRules {
				  private Frame self(){return (Frame)this;}
				  default boolean choose(Object context){return self().value().choose(self(),context);}
				 }
				 public interface ShapeRules {default boolean choose(Frame frame,Object context){return frame.value() instanceof Primary || frame.value() instanceof Residual;}}
				 public static class Shape implements ShapeRules{}
				 public static class Primary extends Shape{}
				 public static class Residual extends Shape{}
				 public static class Registered extends Shape{}
				 public static class NativeShape extends Shape {public boolean choose(Frame frame,Object context){nativeCalls++;return false;}}
				 public static class Frame implements FrameRules {private final Shape stored;public Frame(Shape stored){this.stored=stored;}public Shape value(){return stored;}}
				 public static class NativeFrame extends Frame {public NativeFrame(Shape stored){super(stored);}public boolean choose(Object context){nativeCalls++;return false;}}
				 public static boolean registered(Shape shape){calls++;return shape instanceof Registered;}
				}
				""");
		Path oldSource=Files.createDirectories(work.resolve("native-source"));
		Path nativeHost=write(oldSource,"Host.java","""
				package fixture.predicate;
				import fixture.predicate.Entities.*;
				public class Host {
				 public String live(Frame frame,Object context){Shape capture=frame.value();int result=0;boolean flag=false;
				  if(capture instanceof Primary || capture instanceof Residual){result=42;flag=true;}
				  return finish(result,flag);
				 }
				 public static String finish(int result,boolean flag){return result+":"+flag;}
				}
				""");
		Path nativeJar=WeaveHarness.fixture(work,"predicate-native",List.of(entities,nativeHost),Map.of(),List.of("-g"));
		byte[] original;try(ZipFile zip=new ZipFile(nativeJar.toFile())){original=zip.getInputStream(zip.getEntry(HOST+".class")).readAllBytes();}
		Path binary=work.resolve("native-host.bin");Files.write(binary,original);
		Path index=work.resolve("native-index.tsv");Files.writeString(index,"# forbric-native-reference-v1\n"+HOST+"\t"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original))+"\n");
		Path current=write(source,"Host.java","""
				package fixture.predicate;
				import fixture.predicate.Entities.*;
				public class Host {
				 public String live(Frame frame,Object context){int result=0;boolean flag=false;
				  if(frame.choose(context)){result=42;flag=true;}
				  return finish(result,flag);
				 }
				 public static String finish(int result,boolean flag){return result+":"+flag;}
				 private static String one(String label,Frame frame){Entities.calls=0;Entities.nativeCalls=0;return label+"="+new Host().live(frame,null)+",guest="+Entities.calls+",native="+Entities.nativeCalls;}
				 public static String probe(){return one("registered",new Frame(new Registered()))+";"+one("residual",new Frame(new Residual()))+";"+one("primary",new Frame(new Primary()))+";"+one("shapeOverride",new Frame(new NativeShape()))+";"+one("stateOverride",new NativeFrame(new Primary()));}
				}
				""");
		Path mixin=write(source,"OverlayMixin.java","""
				package fixture.predicate.mixin;
				import fixture.predicate.*;import fixture.predicate.Entities.*;
				import org.spongepowered.asm.mixin.Mixin;
				import org.spongepowered.asm.mixin.injection.At;
				import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
				import com.llamalad7.mixinextras.expression.Definition;
				import com.llamalad7.mixinextras.expression.Expression;
				import com.llamalad7.mixinextras.sugar.Local;
				@Mixin(Host.class) public class OverlayMixin {
				 @Definition(id="Kind",type=Primary.class) @Expression("? instanceof Kind")
				 @ModifyExpressionValue(method="live",at=@At("MIXINEXTRAS:EXPRESSION"),require=0)
				 private boolean modify(boolean original,@Local(name="capture") Shape capture){return Entities.registered(capture);}
				}
				""");
		Path config=write(source,CONFIG,"""
				{"required":false,"minVersion":"0.8","package":"fixture.predicate.mixin","compatibilityLevel":"JAVA_21","mixins":["OverlayMixin"],"injectors":{"defaultRequire":0},"mixinextras":{"minVersion":"0.5.4"}}
				""");
		String prefix="META-INF/forbric/native-reference/FABRIC/";
		fixture=WeaveHarness.fixture(work,"predicate-current",List.of(entities,current,mixin),Map.of(CONFIG,config,prefix+"index.tsv",index,prefix+HOST+".class.bin",binary),List.of("-g"));
		adapted=run("adapted","on");off=run("off","off");
	}

	@Test void theInstanceCallbackRunsOnceAndTheUnmodifiedResidualOrStillApplies()throws Exception{
		assertTrue(adapted.printed("registered=42:true,guest=1,native=0"),adapted.describe());
		assertTrue(adapted.printed("residual=42:true,guest=1,native=0"),adapted.describe());
		assertTrue(adapted.printed("primary=0:false,guest=1,native=0"),adapted.describe());
		assertTrue(adapted.printed("native-proved default predicate"),adapted.describe());
		WeaveHarness.assertWovenAndVerified(adapted,HOST,fixture);
	}
	@Test void concreteOverridesExecuteNativeOnceAndNeverExecuteTheGuest(){
		assertTrue(adapted.printed("shapeOverride=0:false,guest=0,native=1"),adapted.describe());
		assertTrue(adapted.printed("stateOverride=0:false,guest=0,native=1"),adapted.describe());
	}
	@Test void theOffControlDoesNotManufactureAnyHandlerExecution(){
		assertTrue(off.printed("registered=0:false,guest=0,native=0"),off.describe());
		assertTrue(off.printed("residual=42:true,guest=0,native=0"),off.describe());
		assertTrue(off.printed("primary=42:true,guest=0,native=0"),off.describe());
		assertFalse(off.printed("native-proved default predicate"),off.describe());
	}
	private static WeaveHarness.Result run(String label,String property)throws Exception{return WeaveHarness.run(work,label,fixture,CONFIG,"predicate-transport",Ecosystem.FABRIC,EnvType.CLIENT,"fixture.predicate.Host","probe",Map.of(MixinDefaultPredicateTransport.PROPERTY,property));}
	private static Path write(Path directory,String name,String value)throws Exception{Path path=directory.resolve(name);Files.writeString(path,value);return path;}
}
