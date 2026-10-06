package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinSharedResultTransport;

/** A real guest shared-value redirect must stay unbound when the carrier changed another consumer input. */
class ConsumedResultOrderWeaveTest {
	@TempDir Path work;
	@Test void reversedSubtractionIsRejectedByTheRealWeaver()throws Exception{run("a-b","b-a",false,"-2");}
	@Test void exchangedPhiBranchesAreRejectedByTheRealWeaver()throws Exception{run("flag?a:b","flag?b:a",true,"1");}
	private void run(String nativeExpression,String currentExpression,boolean phi,String expectedIndex)throws Exception{
		String owner="net/minecraft/fixture/order/Host",arguments="List<String> old,Map<String,String> live,int a,int b"+(phi?",boolean flag":"");
		String header="package net.minecraft.fixture.order;import java.util.*;public class Host {public static int copies;public static void consume("+arguments+"){";
		Path sink=source("Sink.java","package net.minecraft.fixture.order;public class Sink{public static void accept(int size,int index){System.out.println(\"[SharedOrder] size=\"+size+\" index=\"+index);}}");
		Path original=source("native/Host.java",header+"int index="+nativeExpression+";Sink.accept(old.size(),index);}}");
		Path originalJar=WeaveHarness.fixture(work,"native",List.of(original,sink),Map.of());byte[] reference;
		try(JarFile jar=new JarFile(originalJar.toFile())){reference=jar.getInputStream(jar.getJarEntry(owner+".class")).readAllBytes();}
		Path current=source("current/Host.java",header+"int index="+currentExpression+";live.put(\"new\",\"value\");Sink.accept(live.size(),index);}public void run(){Map<String,String> live=new HashMap<>();live.put(\"old\",\"value\");consume(List.of(\"old\"),live,3,1"+(phi?",true":"")+");System.out.println(\"[SharedOrder] copies=\"+copies);}}");
		Path mixin=source("SnapshotMixin.java","""
				package fixture.order;
				import java.util.*;import net.minecraft.fixture.order.Host;
				import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;
				import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
				import com.llamalad7.mixinextras.sugar.Share;import com.llamalad7.mixinextras.sugar.ref.LocalRef;
				@Mixin(Host.class)public class SnapshotMixin {
				 @Inject(method="consume",at=@At("HEAD"),require=0)private static void snapshot(%s,CallbackInfo callback,@Share("snapshot")LocalRef<Map<String,String>> value){Host.copies++;value.set(new HashMap<>(live));}
				 @Redirect(method="consume",at=@At(value="INVOKE",target="Ljava/util/List;size()I"),require=0)private static int useSnapshot(List<String> old,@Share("snapshot")LocalRef<Map<String,String>> value){return value.get().size();}
				}
				""".formatted(arguments));
		String config="ordered-result.mixins.json";Path json=source(config,"{\"required\":false,\"package\":\"fixture.order\",\"mixins\":[\"SnapshotMixin\"],\"injectors\":{\"defaultRequire\":0}}");
		Path bin=work.resolve("native.bin"),index=work.resolve("native-index.tsv");Files.write(bin,reference);Files.writeString(index,"# forbric-native-reference-v1\n"+owner+"\t"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(reference))+"\n");
		Path fixture=WeaveHarness.fixture(work,"ordered",List.of(current,sink,mixin),Map.of(config,json,"META-INF/forbric/native-reference/FABRIC/index.tsv",index,"META-INF/forbric/native-reference/FABRIC/"+owner+".class.bin",bin));
		for(String mode:List.of("on","off")){
			WeaveHarness.Result result=WeaveHarness.run(work,mode,fixture,config,"orderedresultprobe",Ecosystem.FABRIC,EnvType.SERVER,owner.replace('/','.'),"run",mode.equals("on")?Map.of():Map.of(MixinSharedResultTransport.PROPERTY,"off"));
			assertTrue(result.printed("[SharedOrder] size=2 index="+expectedIndex),result.describe());assertTrue(result.printed("[SharedOrder] copies=1"),result.describe());
			assertFalse(result.printed("[SharedOrder] size=1"),result.describe());
			assertTrue(result.printed("handler=useSnapshot"),"the rejected consumer remains a reported optional miss: "+result.describe());
			WeaveHarness.assertWovenAndVerified(result,owner,fixture);
		}
	}
	private Path source(String name,String text)throws Exception{Path file=work.resolve(name);Files.createDirectories(file.getParent());Files.writeString(file,text);return file;}
}
