package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinCollectionSourceAdapter;

/** The actual Mixin remaps the generated Supplier handle and executes the adapted guest callback. */
class MixinCollectionSourceWeaveTest {
	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result adapted, off, mutate, reassign, failure;
	private static final String CONFIG = "collection-source.mixins.json", HOST = "fixture/collection/Host";

	@BeforeAll static void weave() throws Exception {
		Path source = Files.createDirectories(work.resolve("sources"));
		Path input = write(source, "Input.java", """
				package fixture.collection;
				import java.util.*;
				public final class Input {
				 public static final List<Object> SOURCE=List.of("old-A","old-B");
				 public static int replacementCalls;
				 public static String action="none";
				 public static List<Object> replacement(){replacementCalls++;
				  if(action.equals("mutate"))Holder.DIMENSION.set(0,"mutated-dimension");
				  if(action.equals("reassign"))Holder.DIMENSION=new TrackingList(List.of("reassigned-dimension"));
				  if(action.equals("throw"))throw new IllegalStateException("source failed");
				  return List.of("replacement-B");}
				}
				""");
		Path holder = write(source, "Holder.java", """
				package fixture.collection;
				import java.util.*;import java.util.stream.*;
				public final class Holder {
				 public static final List<Object> COPY=new ArrayList<>(Input.SOURCE);
				 public static final List<Object> VIEW=Collections.unmodifiableList(COPY);
				 public static List<Object> DIMENSION=List.of("dimension");
				 public static Stream<Object> expanded(){return Stream.concat(VIEW.stream(),DIMENSION.stream());}
				}
				""");
		Path tracking = write(source,"TrackingList.java","""
				package fixture.collection;import java.util.*;import java.util.stream.*;
				public final class TrackingList extends ArrayList<Object>{
				 public static int factories,closes;public TrackingList(List<Object> values){super(values);}
				 @Override public Stream<Object> stream(){factories++;return super.stream().onClose(()->closes++);}
				}
				""");
		Path host = write(source, "Host.java", """
				package fixture.collection;
				import java.util.*;
				public final class Host {
				 public static List<Object> live(){return Holder.expanded().toList();}
				 public static String probe(){return run("none");}
				 public static String mutate(){return run("mutate");}public static String reassign(){return run("reassign");}public static String failure(){return run("throw");}
				 private static String run(String action){Holder.COPY.add("carrier");Holder.DIMENSION=new TrackingList(List.of("dimension"));Input.action=action;
				  String result;try{result=live().toString();}catch(IllegalStateException failed){result=failed.getMessage();}
				  return result+"; replacementCalls="+Input.replacementCalls+"; factories="+TrackingList.factories+"; closes="+TrackingList.closes;}
				}
				""");
		Path mixin = write(source, "SourceMixin.java", """
				package fixture.collection.mixin;
				import java.util.*;import fixture.collection.*;
				import org.spongepowered.asm.mixin.Mixin;
				import org.spongepowered.asm.mixin.injection.At;
				import org.spongepowered.asm.mixin.injection.Redirect;
				@Mixin(Host.class)
				public class SourceMixin {
				 @Redirect(method="live",at=@At(value="FIELD",target="Lfixture/collection/Input;SOURCE:Ljava/util/List;"),require=0)
				 private static List<Object> replace(){return Input.replacement();}
				}
				""");
		Path config = write(source, CONFIG, """
				{"required":false,"minVersion":"0.8","package":"fixture.collection.mixin","compatibilityLevel":"JAVA_21","mixins":["SourceMixin"],"injectors":{"defaultRequire":0}}
				""");
		fixture = WeaveHarness.fixture(work,"collection-source",List.of(input,holder,tracking,host,mixin),Map.of(CONFIG,config));
		adapted = run("adapted","on","probe"); off = run("off","off","probe");
		mutate = run("mutate","on","mutate"); reassign=run("reassign","on","reassign"); failure=run("failure","on","failure");
	}

	@Test void theRealWovenWrapperRemapsItsCallbackAndPreservesBothCarrierSuffixes() throws Exception {
		assertTrue(adapted.printed(WeaveHarnessMain.DONE+" [replacement-B, carrier, dimension]; replacementCalls=1; factories=1; closes=1"),adapted.describe());
		assertTrue(adapted.printed("follows a proved collection copy-prefix"),adapted.describe());
		assertFalse(adapted.printed("BootstrapMethodError"),adapted.describe());
		WeaveHarness.assertWovenAndVerified(adapted,HOST,fixture);
	}
	@Test void callbackChangesToSuffixContentsAndFieldReferencesReachTheRealNativeGetter()throws Exception{
		assertTrue(mutate.printed(WeaveHarnessMain.DONE+" [replacement-B, carrier, mutated-dimension]; replacementCalls=1; factories=1; closes=1"),mutate.describe());
		assertTrue(reassign.printed(WeaveHarnessMain.DONE+" [replacement-B, carrier, reassigned-dimension]; replacementCalls=1; factories=1; closes=1"),reassign.describe());
		WeaveHarness.assertWovenAndVerified(mutate,HOST,fixture);WeaveHarness.assertWovenAndVerified(reassign,HOST,fixture);
	}
	@Test void aThrowingSourceNeverCreatesOrConsumesTheNativeStream(){
		assertTrue(failure.printed(WeaveHarnessMain.DONE+" source failed; replacementCalls=1; factories=0; closes=0"),failure.describe());
	}

	@Test void theOffControlNeverCallsTheOriginalRedirectAndKeepsTheNativeList() {
		assertTrue(off.printed(WeaveHarnessMain.DONE+" [old-A, old-B, carrier, dimension]; replacementCalls=0; factories=1; closes=0"),off.describe());
		assertFalse(off.printed("follows a proved collection copy-prefix"),off.describe());
		assertFalse(off.printed("[replacement-B, carrier, dimension]"),off.describe());
	}

	private static WeaveHarness.Result run(String label,String setting,String probe)throws Exception{
		return WeaveHarness.run(work,label,fixture,CONFIG,"collection-source",Ecosystem.FABRIC,EnvType.SERVER,
				"fixture.collection.Host",probe,Map.of(MixinCollectionSourceAdapter.PROPERTY,setting));
	}
	private static Path write(Path directory,String name,String text)throws Exception{Path file=directory.resolve(name);Files.writeString(file,text);return file;}
}
