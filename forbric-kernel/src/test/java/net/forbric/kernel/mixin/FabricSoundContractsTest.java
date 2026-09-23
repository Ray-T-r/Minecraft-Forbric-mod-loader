package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.file.*;
import java.net.*;
import javax.tools.ToolProvider;
import net.forbric.kernel.transform.FabricSoundContractTransformer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
@ResourceLock("system-properties")
class FabricSoundContractsTest {
 @TempDir Path root;
 private final FabricSoundContractTransformer transformer=new FabricSoundContractTransformer(n->true);
 private static final String SOUND=FabricSoundContractTransformer.SOUND,API=FabricSoundContractTransformer.API,ENGINE="net/minecraft/client/sounds/SoundEngine",MIXIN="net/fabricmc/fabric/mixin/client/sound/SoundEngineMixin";
 @AfterEach void reset(){System.clearProperty(FabricSoundContractTransformer.PROPERTY);}
 private ClassNode sound()throws Exception{ClassNode node=StagedFabricMixinFixture.game(SOUND,false);node.interfaces.add(API);return node;}
 private ClassNode adapted(ClassNode node){return MixinFit.parse(transformer.transform(SOUND.replace('/','.'),StagedFabricMixinFixture.bytes(node),null));}
 private ClassNode mixin()throws Exception{return StagedFabricMixinFixture.mixin("fabric-sound-api-v1",MIXIN);}
 @Test void actualNativeDefaultAndOriginalRedirectComposeOnce()throws Exception{
  ClassNode sound=adapted(sound()),engine=StagedFabricMixinFixture.game(ENGINE,false),mixin=mixin();
  assertEquals(1,FabricSoundMixinAdapter.adapt(mixin,n->n.equals(SOUND)?sound:engine));
  for(var pair:List.of(new Object[]{sound,"getStream"},new Object[]{mixin,"getStream"}))new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(((ClassNode)pair[0]).name,StagedFabricMixinFixture.method((ClassNode)pair[0],(String)pair[1]));
  assertEquals(0,FabricSoundMixinAdapter.adapt(mixin,n->n.equals(SOUND)?sound:engine));
 }
 @Test void missingGraftMissingApiUnknownBodyAndDisabledContractKeepNativeBytes()throws Exception{
  ClassNode node=StagedFabricMixinFixture.game(SOUND,false);byte[] bytes=StagedFabricMixinFixture.bytes(node);assertSame(bytes,transformer.transform(SOUND.replace('/','.'),bytes,null));
  node=sound();bytes=StagedFabricMixinFixture.bytes(node);assertSame(bytes,new FabricSoundContractTransformer(n->false).transform(SOUND.replace('/','.'),bytes,null));
  System.setProperty(FabricSoundContractTransformer.PROPERTY,"off");assertSame(bytes,transformer.transform(SOUND.replace('/','.'),bytes,null));System.clearProperty(FabricSoundContractTransformer.PROPERTY);
  StagedFabricMixinFixture.method(node,"getStream").instructions.insert(new InsnNode(Opcodes.NOP));bytes=StagedFabricMixinFixture.bytes(node);assertSame(bytes,transformer.transform(SOUND.replace('/','.'),bytes,null));
 }
 @Test void redirectCannotMoveWithoutTheDefaultDispatchOrWithChangedHandler()throws Exception{
  ClassNode nativeSound=sound(),engine=StagedFabricMixinFixture.game(ENGINE,false);assertEquals(0,FabricSoundMixinAdapter.adapt(mixin(),n->n.equals(SOUND)?nativeSound:engine));
  ClassNode changed=mixin(),sound=adapted(sound());StagedFabricMixinFixture.method(changed,"getStream").instructions.insert(new InsnNode(Opcodes.NOP));assertEquals(0,FabricSoundMixinAdapter.adapt(changed,n->n.equals(SOUND)?sound:engine));
 }
 @Test void executeActualAdaptedDefaultAndRedirectPreservingNativeOverridePriority()throws Exception{
  Map<String,String> sources=new LinkedHashMap<>();
  sources.put("net/minecraft/resources/Identifier.java","package net.minecraft.resources; public class Identifier {}");
  sources.put("net/minecraft/client/sounds/SoundBufferLibrary.java","package net.minecraft.client.sounds; public class SoundBufferLibrary {}");
  sources.put("net/minecraft/client/resources/sounds/Sound.java","package net.minecraft.client.resources.sounds; public class Sound {public final net.minecraft.resources.Identifier path=new net.minecraft.resources.Identifier();public net.minecraft.resources.Identifier getPath(){return path;}}");
  sources.put(API+".java","package net.fabricmc.fabric.api.client.sound.v1; public interface FabricSoundInstance {java.util.concurrent.CompletableFuture<?> getAudioStream(net.minecraft.client.sounds.SoundBufferLibrary library,net.minecraft.resources.Identifier path,boolean loop);}");
  sources.put(SOUND+".java","package net.minecraft.client.resources.sounds; public interface SoundInstance extends net.fabricmc.fabric.api.client.sound.v1.FabricSoundInstance {default java.util.concurrent.CompletableFuture<?> getStream(net.minecraft.client.sounds.SoundBufferLibrary library,Sound sound,boolean loop){return null;}}");
  sources.put(MIXIN+".java","package net.fabricmc.fabric.mixin.client.sound; public class SoundEngineMixin {}");
  sources.put("probe/FabricSound.java","package probe; public class FabricSound implements net.minecraft.client.resources.sounds.SoundInstance { public final java.util.concurrent.CompletableFuture<Object> result=new java.util.concurrent.CompletableFuture<>(); public Object library,path;public boolean loop;public int calls;public java.util.concurrent.CompletableFuture<?> getAudioStream(net.minecraft.client.sounds.SoundBufferLibrary l,net.minecraft.resources.Identifier p,boolean b){library=l;path=p;loop=b;calls++;return result;}}");
  sources.put("probe/NativeSound.java","package probe; public class NativeSound extends FabricSound { public final java.util.concurrent.CompletableFuture<Object> nativeResult=new java.util.concurrent.CompletableFuture<>(); public Object detail;public int nativeCalls;public java.util.concurrent.CompletableFuture<?> getStream(net.minecraft.client.sounds.SoundBufferLibrary l,net.minecraft.client.resources.sounds.Sound s,boolean b){library=l;detail=s;loop=b;nativeCalls++;return nativeResult;}}");
  List<String> args=new ArrayList<>(List.of("--release","21","-d",root.toString()));for(var source:sources.entrySet()){Path file=root.resolve(source.getKey());Files.createDirectories(file.getParent());Files.writeString(file,source.getValue());args.add(file.toString());}assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
  ClassNode sound=adapted(sound()),engine=StagedFabricMixinFixture.game(ENGINE,false),mixin=mixin();assertEquals(1,FabricSoundMixinAdapter.adapt(mixin,n->n.equals(SOUND)?sound:engine));
  for(ClassNode source:List.of(sound,mixin)){String name=source.name;ClassNode shell=MixinFit.parse(Files.readAllBytes(root.resolve(name+".class")));MethodNode method=StagedFabricMixinFixture.method(source,"getStream");method.access=Opcodes.ACC_PUBLIC;method.visibleAnnotations=null;method.invisibleAnnotations=null;shell.methods.removeIf(m->m.name.equals("getStream"));shell.methods.add(method);Files.write(root.resolve(name+".class"),StagedFabricMixinFixture.bytes(shell));}
  try(URLClassLoader loader=new URLClassLoader(new URL[]{root.toUri().toURL()},ClassLoader.getPlatformClassLoader())){
   Class<?> base=loader.loadClass(SOUND.replace('/','.')),library=loader.loadClass("net.minecraft.client.sounds.SoundBufferLibrary"),detail=loader.loadClass("net.minecraft.client.resources.sounds.Sound"),host=loader.loadClass(MIXIN.replace('/','.'));
   Object l=library.getConstructor().newInstance(),s=detail.getConstructor().newInstance(),h=host.getConstructor().newInstance();var redirect=host.getMethod("getStream",base,library,detail,boolean.class);
   for(boolean nativeOverride:new boolean[]{false,true}){Class<?> type=loader.loadClass(nativeOverride?"probe.NativeSound":"probe.FabricSound");Object instance=type.getConstructor().newInstance();assertSame(type.getField(nativeOverride?"nativeResult":"result").get(instance),redirect.invoke(h,instance,l,s,true));assertSame(l,type.getField("library").get(instance));assertTrue(type.getField("loop").getBoolean(instance));assertEquals(nativeOverride?0:1,type.getField("calls").getInt(instance));if(nativeOverride){assertSame(s,type.getField("detail").get(instance));assertEquals(1,type.getField("nativeCalls").getInt(instance));}else assertSame(detail.getField("path").get(s),type.getField("path").get(instance));}
  }
 }
}
