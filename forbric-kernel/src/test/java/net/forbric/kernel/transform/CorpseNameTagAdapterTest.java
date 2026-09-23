package net.forbric.kernel.transform;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.InvocationTargetException;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
@ResourceLock("system-properties")
class CorpseNameTagAdapterTest {
 @TempDir Path root;
 private static final Path OLD=Path.of(System.getenv().getOrDefault("FORBRIC_OLD","../forbric-loader"));
 @AfterEach void reset(){System.clearProperty(CorpseNameTagAdapter.PROPERTY);}
 private ClassNode parse(byte[] bytes){ClassNode c=new ClassNode();new ClassReader(bytes).accept(c,0);return c;}
 private byte[] bytes(ClassNode c){ClassWriter w=new ClassWriter(0);c.accept(w);return w.toByteArray();}
 private ClassNode read(Path jar,String name)throws Exception{try(ZipFile z=new ZipFile(jar.toFile())){return parse(z.getInputStream(z.getEntry(name+".class")).readAllBytes());}}
 private ClassNode corpse()throws Exception{return read(Path.of("run/client-merged-pack/mods/corpse-neoforge-1.1.17+26.2.jar"),CorpseNameTagAdapter.TARGET);}
 private Map<String,ClassNode> declarations()throws Exception{return new HashMap<>(Map.of(
  CorpseNameTagAdapter.NATIVE,read(OLD.resolve("run/neoforge-runtime/neoforge-runtime.jar"),CorpseNameTagAdapter.NATIVE),
  CorpseNameTagAdapter.ATTRIBUTES,read(OLD.resolve("run/merged-base/patched-mc-merged-26.2.jar"),CorpseNameTagAdapter.ATTRIBUTES)));}
 private byte[] adapt(ClassNode c,Map<String,ClassNode> declarations){return new CorpseNameTagAdapter(declarations::get).transform(c.name.replace('/','.'),bytes(c),null);}
 private MethodNode constructor(ClassNode c){return c.methods.stream().filter(m->m.name.equals("<init>")&&m.desc.equals(CorpseNameTagAdapter.CONSTRUCTOR)).findFirst().orElseThrow();}
 @Test void actualReviewedConstructorMovesOneReadAndKeepsTheOriginalJarAndOtherInstructions()throws Exception{
  ClassNode original=corpse(),changed=parse(adapt(original,declarations()));int migrated=0;
  var before=constructor(original).instructions.toArray();var after=constructor(changed).instructions.toArray();assertEquals(before.length,after.length);
  for(int i=0;i<before.length;i++)if(before[i] instanceof FieldInsnNode a&&after[i] instanceof FieldInsnNode b){
   if(a.name.equals("NAMETAG_DISTANCE")){assertEquals(CorpseNameTagAdapter.ATTRIBUTES,b.owner);assertEquals("NAME_TAG_DISTANCE",b.name);assertEquals(a.desc,b.desc);migrated++;}
   else{assertEquals(a.owner,b.owner);assertEquals(a.name,b.name);assertEquals(a.desc,b.desc);}
  }
  assertEquals(1,migrated);assertTrue(Arrays.stream(constructor(corpse()).instructions.toArray()).anyMatch(i->i instanceof FieldInsnNode f&&f.name.equals("NAMETAG_DISTANCE")));
  byte[] once=bytes(changed);assertSame(once,new CorpseNameTagAdapter(declarations()::get).transform(changed.name.replace('/','.'),once,null));
 }
 @Test void nonzeroBehaviorChangedBodyExistingLegacyFieldAndAbsentReplacementRefuse()throws Exception{
  for(int mode=0;mode<5;mode++){
   ClassNode c=corpse();var declarations=declarations();
   if(mode==0)constructor(c).instructions.insert(new InsnNode(Opcodes.NOP));
   if(mode==1)for(var i:constructor(c).instructions.toArray())if(i.getOpcode()==Opcodes.DCONST_0){constructor(c).instructions.set(i,new InsnNode(Opcodes.DCONST_1));break;}
   if(mode==2)declarations.get(CorpseNameTagAdapter.NATIVE).fields.add(new FieldNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"NAMETAG_DISTANCE",CorpseNameTagAdapter.HOLDER,null,null));
   if(mode==3)declarations.get(CorpseNameTagAdapter.ATTRIBUTES).fields.removeIf(f->f.name.equals("NAME_TAG_DISTANCE"));
   if(mode==4)System.setProperty(CorpseNameTagAdapter.PROPERTY,"off");
   byte[] data=bytes(c);assertSame(data,new CorpseNameTagAdapter(declarations::get).transform(c.name.replace('/','.'),data,null),"mode="+mode);System.clearProperty(CorpseNameTagAdapter.PROPERTY);
  }
 }
 @Test void executeTheActualConstructorBeforeAndAfterWithoutOpeningAWindow()throws Exception{
  Map<String,String> sources=new LinkedHashMap<>();
  sources.put("net/minecraft/client/multiplayer/ClientLevel.java","package net.minecraft.client.multiplayer;public class ClientLevel {}");
  sources.put("com/mojang/authlib/GameProfile.java","package com.mojang.authlib;public class GameProfile {}");
  sources.put("net/minecraft/core/Holder.java","package net.minecraft.core;public interface Holder<T> {}");
  sources.put("net/minecraft/world/entity/ai/attributes/Attributes.java","package net.minecraft.world.entity.ai.attributes;public class Attributes {public static final net.minecraft.core.Holder<Object> NAME_TAG_DISTANCE=new net.minecraft.core.Holder<>(){};}");
  sources.put("net/minecraft/world/entity/ai/attributes/AttributeInstance.java","package net.minecraft.world.entity.ai.attributes;public class AttributeInstance {public double value=64;public void setBaseValue(double value){this.value=value;}}");
  sources.put("net/minecraft/world/entity/ai/attributes/AttributeMap.java","package net.minecraft.world.entity.ai.attributes;public class AttributeMap {public final AttributeInstance instance=new AttributeInstance();public AttributeInstance getInstance(net.minecraft.core.Holder<?> key){if(key!=Attributes.NAME_TAG_DISTANCE)throw new AssertionError();return instance;}}");
  sources.put("net/minecraft/world/entity/EquipmentSlot.java","package net.minecraft.world.entity;public enum EquipmentSlot {HEAD}");
  sources.put("net/minecraft/world/item/ItemStack.java","package net.minecraft.world.item;public class ItemStack {}");
  sources.put("net/neoforged/neoforge/common/NeoForgeMod.java","package net.neoforged.neoforge.common;public class NeoForgeMod {}");
  sources.put("net/neoforged/neoforge/common/ModConfigSpec.java","package net.neoforged.neoforge.common;public class ModConfigSpec {public static class BooleanValue {public Object get(){return true;}}}");
  sources.put("de/maxhenkel/corpse/ServerConfig.java","package de.maxhenkel.corpse;public class ServerConfig {public net.neoforged.neoforge.common.ModConfigSpec.BooleanValue renderEquipment=new net.neoforged.neoforge.common.ModConfigSpec.BooleanValue();}");
  sources.put("de/maxhenkel/corpse/CorpseMod.java","package de.maxhenkel.corpse;public class CorpseMod {public static final ServerConfig SERVER_CONFIG=new ServerConfig();}");
  sources.put("net/minecraft/client/player/RemotePlayer.java","package net.minecraft.client.player;public class RemotePlayer {public final Object level,profile;public double xo,yo,zo;public Object slot,stack;public double[] position;public final net.minecraft.world.entity.ai.attributes.AttributeMap attributes=new net.minecraft.world.entity.ai.attributes.AttributeMap();public RemotePlayer(net.minecraft.client.multiplayer.ClientLevel level,com.mojang.authlib.GameProfile profile){this.level=level;this.profile=profile;}public net.minecraft.world.entity.ai.attributes.AttributeMap getAttributes(){return attributes;}public void setItemSlot(net.minecraft.world.entity.EquipmentSlot slot,net.minecraft.world.item.ItemStack stack){this.slot=slot;this.stack=stack;}public void setPos(double x,double y,double z){position=new double[]{x,y,z};}}");
  sources.put(CorpseNameTagAdapter.TARGET+".java","package de.maxhenkel.corpse.entities;public class DummyPlayer extends net.minecraft.client.player.RemotePlayer {public byte model;public DummyPlayer(net.minecraft.client.multiplayer.ClientLevel l,com.mojang.authlib.GameProfile p){super(l,p);}}");
  List<String> args=new ArrayList<>(List.of("--release","21","-d",root.toString()));for(var entry:sources.entrySet()){Path file=root.resolve(entry.getKey());Files.createDirectories(file.getParent());Files.writeString(file,entry.getValue());args.add(file.toString());}assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
  Path output=root.resolve(CorpseNameTagAdapter.TARGET+".class");byte[] shell=Files.readAllBytes(output);
  for(boolean repaired:new boolean[]{false,true}){
   ClassNode body=repaired?parse(adapt(corpse(),declarations())):corpse(),c=parse(shell);c.methods.add(constructor(body));Files.write(output,bytes(c));
   try(URLClassLoader loader=new URLClassLoader(new URL[]{root.toUri().toURL()},ClassLoader.getPlatformClassLoader())){
    Class<?> level=loader.loadClass("net.minecraft.client.multiplayer.ClientLevel"),profile=loader.loadClass("com.mojang.authlib.GameProfile"),target=loader.loadClass(CorpseNameTagAdapter.TARGET.replace('/','.')),slots=loader.loadClass("net.minecraft.world.entity.EquipmentSlot"),stack=loader.loadClass("net.minecraft.world.item.ItemStack");
    Object world=level.getConstructor().newInstance(),user=profile.getConstructor().newInstance(),slot=slots.getEnumConstants()[0],item=stack.getConstructor().newInstance();@SuppressWarnings({"rawtypes","unchecked"}) EnumMap equipment=new EnumMap(slots);equipment.put(slot,item);
    var ctor=target.getConstructor(level,profile,EnumMap.class,byte.class);
    if(!repaired){assertInstanceOf(NoSuchFieldError.class,assertThrows(InvocationTargetException.class,()->ctor.newInstance(world,user,equipment,(byte)7)).getCause());continue;}
    Object instance=ctor.newInstance(world,user,equipment,(byte)7);assertSame(world,target.getField("level").get(instance));assertSame(user,target.getField("profile").get(instance));assertSame(item,target.getField("stack").get(instance));assertSame(slot,target.getField("slot").get(instance));assertEquals((byte)7,target.getField("model").get(instance));assertArrayEquals(new double[]{0,0,0},(double[])target.getField("position").get(instance));
    Object map=target.getField("attributes").get(instance),attribute=map.getClass().getField("instance").get(map);assertEquals(0d,attribute.getClass().getField("value").getDouble(attribute));
   }
  }
 }
}
