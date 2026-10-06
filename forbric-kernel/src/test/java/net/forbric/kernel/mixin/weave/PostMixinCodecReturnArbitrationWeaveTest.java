package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.transform.PostMixinCodecReturnArbitration;
import net.forbric.kernel.transform.PostMixinCodecReturnArbitrationTest;

class PostMixinCodecReturnArbitrationWeaveTest {
	@TempDir Path work;
	@Test void aRealGuestHeadReturnKeepsItsCallbackAndStillArbitratesDifferentPayloadClasses() throws Exception {
		Map<String,String> sources = new LinkedHashMap<>(PostMixinCodecReturnArbitrationTest.SOURCES);
		sources.put("fixture.CodecProbe", """
				package fixture;
				import java.util.*;
				import net.minecraft.network.ConnectionProtocol;
				import net.minecraft.network.protocol.PacketFlow;
				import net.minecraft.network.codec.StreamCodec;
				import net.fabricmc.fabric.impl.networking.*;
				public class CodecProbe {
				 public static int heads;
				 public void run() {
				  Codec fabric=new Codec("fabric",RegistrationPayload.class), forge=new Codec("forge",net.minecraftforge.network.ForgePayload.class);
				  PayloadTypeRegistryImpl.SERVERBOUND_CONFIGURATION.entries.put("minecraft:register",new PayloadTypeRegistryImpl.Entry("type",fabric));
				  net.minecraftforge.network.NetworkRegistry.CODECS.put("minecraft:register",forge);
				  Fallback fallback=new Fallback();Lookup lookup=new Lookup(new HashMap<>(),ConnectionProtocol.CONFIGURATION,PacketFlow.SERVERBOUND,fallback);
				  lookup.guest=fabric;StreamCodec<Object,Object> chosen=lookup.lookup("minecraft:register");Buffer buffer=new Buffer();
				  try{chosen.encode(buffer,new net.minecraftforge.network.ForgePayload());System.out.println("[CodecReturn] encoded="+buffer.by);}
				  catch(ClassCastException expected){System.out.println("[CodecReturn] encoded=wrong-guest-class");}
				  System.out.println("[CodecReturn] heads="+heads+" body="+lookup.callbacks+" fallback="+fallback.calls);
				 }
				}
				""");
		sources.put("fixture.mixin.CodecHeadMixin", """
				package fixture.mixin;
				import fixture.*;
				import net.minecraft.network.codec.StreamCodec;
				import org.spongepowered.asm.mixin.*;
				import org.spongepowered.asm.mixin.injection.*;
				import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
				@Mixin(Lookup.class)
				public abstract class CodecHeadMixin {
				 @Shadow public Object guest;
				 @Inject(method="lookup",at=@At("HEAD"),cancellable=true)
				 private void choose(Object id,CallbackInfoReturnable<StreamCodec<Object,Object>> result){
				  CodecProbe.heads++;if(guest!=null)result.setReturnValue((StreamCodec<Object,Object>)guest);
				 }
				}
				""");
		List<Path> files = new ArrayList<>();
		for(var entry:sources.entrySet()){
			Path file=work.resolve(entry.getKey().replace('.','/')+".java");Files.createDirectories(file.getParent());Files.writeString(file,entry.getValue());files.add(file);
		}
		String config="codec-head.mixins.json";Path json=work.resolve(config);Files.writeString(json,"{\"required\":true,\"package\":\"fixture.mixin\",\"mixins\":[\"CodecHeadMixin\"],\"injectors\":{\"defaultRequire\":1}}");
		Path fixture=WeaveHarness.fixture(work,"codec-head",files,Map.of(config,json));
		WeaveHarness.Result repaired=WeaveHarness.run(work,"on",fixture,config,"codecheadprobe",Ecosystem.FABRIC,EnvType.CLIENT,"fixture.CodecProbe","run",Map.of());
		assertTrue(repaired.printed("[CodecReturn] encoded=forge"),repaired.describe());
		assertTrue(repaired.printed("[CodecReturn] heads=1 body=0 fallback=1"),repaired.describe());
		assertTrue(repaired.findings().stream().noneMatch(f->f.confirmedRequired()),repaired.describe());
		WeaveHarness.assertWovenAndVerified(repaired,"fixture/Lookup",fixture);
		WeaveHarness.Result control=WeaveHarness.run(work,"off",fixture,config,"codecheadprobe",Ecosystem.FABRIC,EnvType.CLIENT,"fixture.CodecProbe","run",Map.of(PostMixinCodecReturnArbitration.PROPERTY,"off"));
		assertTrue(control.printed("[CodecReturn] encoded=wrong-guest-class"),control.describe());
		assertTrue(control.printed("[CodecReturn] heads=1 body=0 fallback=0"),control.describe());
		assertFalse(control.printed("[CodecReturn] encoded=forge"),control.describe());
	}

	@Test void aRealOperationWrapperCanReturnBeforeItsOriginalAndStillArbitrateBothDirections() throws Exception {
		Map<String,String> sources = new LinkedHashMap<>(PostMixinCodecReturnArbitrationTest.SOURCES);
		sources.compute("fixture.Lookup", (key, source) -> source.replace("public int callbacks;", """
				public void write(Object id,Buffer buffer,Object payload){lookup(id).encode(buffer,payload);}
				public Object read(Object id,Buffer buffer){return lookup(id).decode(buffer);}
				public int callbacks;
				"""));
		sources.put("fixture.CodecProbe", """
				package fixture;
				import java.util.*;
				import net.minecraft.network.ConnectionProtocol;
				import net.minecraft.network.protocol.PacketFlow;
				import net.fabricmc.fabric.impl.networking.*;
				public class CodecProbe {
				 public static int wraps;
				 public void run() {
				  Codec fabric=new Codec("fabric",RegistrationPayload.class), forge=new Codec("forge",net.minecraftforge.network.ForgePayload.class),neo=new Codec("neo",net.neoforged.neoforge.network.payload.MinecraftRegisterPayload.class);
				  PayloadTypeRegistryImpl.SERVERBOUND_CONFIGURATION.entries.put("minecraft:register",new PayloadTypeRegistryImpl.Entry("type",fabric));
				  net.minecraftforge.network.NetworkRegistry.CODECS.put("minecraft:register",forge);
				  net.neoforged.neoforge.network.registration.NetworkRegistry.BUILTIN_PAYLOADS.put("minecraft:register",neo);
				  Fallback fallback=new Fallback();Map<Object,Object> locals=new HashMap<>();Lookup lookup=new Lookup(locals,ConnectionProtocol.CONFIGURATION,PacketFlow.SERVERBOUND,fallback);Buffer buffer=new Buffer();
				  lookup.guest=fabric;
				  try{lookup.write("minecraft:register",buffer,new net.minecraftforge.network.ForgePayload());System.out.println("[CodecWrap] forge="+buffer.by);}catch(ClassCastException expected){System.out.println("[CodecWrap] forge=wrong-guest-class");}
				  try{lookup.write("minecraft:register",buffer,new net.neoforged.neoforge.network.payload.MinecraftRegisterPayload());System.out.println("[CodecWrap] neo="+buffer.by);}catch(ClassCastException expected){System.out.println("[CodecWrap] neo=wrong-guest-class");}
				  lookup.write("minecraft:register",buffer,new RegistrationPayload());System.out.println("[CodecWrap] fabric="+buffer.by);
				  System.out.println("[CodecWrap] decode="+lookup.read("minecraft:register",buffer).getClass().getName());
				  Codec unknown=new Codec("guest",UnknownPayload.class);lookup.guest=unknown;lookup.write("guest:unknown",buffer,new UnknownPayload());System.out.println("[CodecWrap] unknown="+buffer.by+":"+lookup.read("guest:unknown",buffer).getClass().getName());
				  lookup.guest=null;locals.put("local:id",unknown);lookup.write("local:id",buffer,new UnknownPayload());System.out.println("[CodecWrap] local="+buffer.by);
				  fallback.codec=unknown;System.out.println("[CodecWrap] fallback="+lookup.read("fallback:id",buffer).getClass().getName());
				  System.out.println("[CodecWrap] wraps="+wraps+" body="+lookup.callbacks+" fallbackCalls="+fallback.calls);
				 }
				}
				""");
		sources.put("fixture.mixin.CodecOperationMixin", """
				package fixture.mixin;
				import fixture.*;
				import net.minecraft.network.codec.StreamCodec;
				import org.spongepowered.asm.mixin.*;
				import org.spongepowered.asm.mixin.injection.*;
				import com.llamalad7.mixinextras.injector.wrapoperation.*;
				import com.llamalad7.mixinextras.sugar.Local;
				@Mixin(Lookup.class)
				public abstract class CodecOperationMixin {
				 @Shadow public Object guest;
				 @WrapOperation(method={"write","read"},at=@At(value="INVOKE",target="Lfixture/Lookup;lookup(Ljava/lang/Object;)Lnet/minecraft/network/codec/StreamCodec;"))
				 private StreamCodec<Object,Object> choose(Lookup receiver,Object id,Operation<StreamCodec<Object,Object>> original,@Local(argsOnly=true) Buffer buffer){
				  CodecProbe.wraps++;if(buffer==null)throw new AssertionError("captured buffer lost");
				  if(guest!=null)return (StreamCodec<Object,Object>)guest;return original.call(receiver,id);
				 }
				}
				""");
		List<Path> files = new ArrayList<>();
		for (var entry : sources.entrySet()) { Path file=work.resolve(entry.getKey().replace('.','/')+".java");Files.createDirectories(file.getParent());Files.writeString(file,entry.getValue());files.add(file); }
		String config="codec-operation.mixins.json";Path json=work.resolve(config);Files.writeString(json,"{\"required\":true,\"package\":\"fixture.mixin\",\"mixins\":[\"CodecOperationMixin\"],\"injectors\":{\"defaultRequire\":1}}");
		Path fixture=WeaveHarness.fixture(work,"codec-operation",files,Map.of(config,json));
		WeaveHarness.Result repaired=WeaveHarness.run(work,"on",fixture,config,"codecoperationprobe",Ecosystem.FABRIC,EnvType.CLIENT,"fixture.CodecProbe","run",Map.of());
		for(String expected:List.of("[CodecWrap] forge=forge","[CodecWrap] neo=neo","[CodecWrap] fabric=fabric","[CodecWrap] decode=net.neoforged.neoforge.network.payload.MinecraftRegisterPayload","[CodecWrap] unknown=guest:fixture.UnknownPayload","[CodecWrap] local=guest","[CodecWrap] fallback=fixture.UnknownPayload","[CodecWrap] wraps=8 body=2 fallbackCalls=8")) assertTrue(repaired.printed(expected),repaired.describe());
		assertTrue(repaired.findings().stream().noneMatch(f->f.confirmedRequired()),repaired.describe());
		WeaveHarness.assertWovenAndVerified(repaired,"fixture/Lookup",fixture);
		WeaveHarness.Result control=WeaveHarness.run(work,"off",fixture,config,"codecoperationprobe",Ecosystem.FABRIC,EnvType.CLIENT,"fixture.CodecProbe","run",Map.of(PostMixinCodecReturnArbitration.PROPERTY,"off"));
		assertTrue(control.printed("[CodecWrap] forge=wrong-guest-class"),control.describe());assertTrue(control.printed("[CodecWrap] neo=wrong-guest-class"),control.describe());
		assertTrue(control.printed("[CodecWrap] decode=net.fabricmc.fabric.impl.networking.RegistrationPayload"),control.describe());
		assertTrue(control.printed("[CodecWrap] wraps=8 body=2 fallbackCalls=2"),control.describe());
		assertFalse(control.printed("[CodecWrap] forge=forge"),control.describe());
	}

	@Test void anOperationsChangedIdKeepsItsOwnUnifiedCodecAndCallsTheFactoryOnce() throws Exception {
		Map<String,String> sources=new LinkedHashMap<>(PostMixinCodecReturnArbitrationTest.SOURCES);
		sources.compute("fixture.Lookup",(key,source)->source.replace("public int callbacks;","public net.minecraft.network.codec.StreamCodec<Object,Object> fetch(Object id){return lookup(id);} public int callbacks;"));
		sources.put("fixture.CodecProbe","""
				package fixture;
				import java.util.*;
				import net.minecraft.network.ConnectionProtocol;
				import net.minecraft.network.protocol.PacketFlow;
				import net.minecraft.network.codec.StreamCodec;
				public class CodecProbe {
				 public static int wraps;
				 public void run(){
				  Map<Object,Object> locals=new HashMap<>();locals.put("guest:outer",new Codec("outer",net.fabricmc.fabric.impl.networking.RegistrationPayload.class));locals.put("guest:inner",new Codec("inner",UnknownPayload.class));
				  Fallback fallback=new Fallback();Lookup lookup=new Lookup(locals,ConnectionProtocol.CONFIGURATION,PacketFlow.SERVERBOUND,fallback);
				  StreamCodec<Object,Object> chosen=lookup.fetch("guest:outer");Buffer buffer=new Buffer();chosen.encode(buffer,new UnknownPayload());
				  System.out.println("[CodecChangedId] chosen="+chosen+" encode="+buffer.by+" decode="+chosen.decode(buffer).getClass().getName());
				  System.out.println("[CodecChangedId] wraps="+wraps+" body="+lookup.callbacks+" factory="+fallback.calls);
				 }
				}
				""");
		sources.put("fixture.mixin.CodecChangedIdMixin","""
				package fixture.mixin;
				import fixture.*;
				import net.minecraft.network.codec.StreamCodec;
				import org.spongepowered.asm.mixin.Mixin;
				import org.spongepowered.asm.mixin.injection.At;
				import com.llamalad7.mixinextras.injector.wrapoperation.*;
				@Mixin(Lookup.class) public class CodecChangedIdMixin {
				 @WrapOperation(method="fetch",at=@At(value="INVOKE",target="Lfixture/Lookup;lookup(Ljava/lang/Object;)Lnet/minecraft/network/codec/StreamCodec;"))
				 private StreamCodec<Object,Object> redirect(Lookup receiver,Object id,Operation<StreamCodec<Object,Object>> original){CodecProbe.wraps++;return original.call(receiver,"guest:inner");}
				}
				""");
		List<Path> files=new ArrayList<>();for(var entry:sources.entrySet()){Path file=work.resolve(entry.getKey().replace('.','/')+".java");Files.createDirectories(file.getParent());Files.writeString(file,entry.getValue());files.add(file);}
		String config="codec-changed-id.mixins.json";Path json=work.resolve(config);Files.writeString(json,"{\"required\":true,\"package\":\"fixture.mixin\",\"mixins\":[\"CodecChangedIdMixin\"],\"injectors\":{\"defaultRequire\":1}}");
		Path fixture=WeaveHarness.fixture(work,"codec-changed-id",files,Map.of(config,json));
		for(String mode:List.of("on","off")){
			WeaveHarness.Result result=WeaveHarness.run(work,mode,fixture,config,"codecchangedidprobe",Ecosystem.FABRIC,EnvType.CLIENT,"fixture.CodecProbe","run",mode.equals("on")?Map.of():Map.of(PostMixinCodecReturnArbitration.PROPERTY,"off"));
			assertTrue(result.printed("[CodecChangedId] chosen=ForbricCustomPayloadCodec[guest:inner] encode=inner decode=fixture.UnknownPayload"),result.describe());
			assertTrue(result.printed("[CodecChangedId] wraps=1 body=1 factory=1"),result.describe());
			org.objectweb.asm.tree.ClassNode node=new org.objectweb.asm.tree.ClassNode();new org.objectweb.asm.ClassReader(result.defined("fixture/Lookup")).accept(node,0);
			long hooks=node.methods.stream().filter(m->m.name.equals("fetch")).flatMap(m->java.util.stream.StreamSupport.stream(m.instructions.spliterator(),false)).filter(i->i instanceof org.objectweb.asm.tree.MethodInsnNode call&&call.name.equals("afterGuestCodec")).count();
			assertEquals(mode.equals("on")?1:0,hooks,"the changed-id path must really pass the outer arbitration hook in the on run");
			WeaveHarness.assertWovenAndVerified(result,"fixture/Lookup",fixture);
		}
	}
}
