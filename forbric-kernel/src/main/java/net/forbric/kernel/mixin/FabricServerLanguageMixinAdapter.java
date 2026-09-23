/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
/** Merge Fabric's strings into the native mutable map; retain NeoForge's separate component map and capture. */
public final class FabricServerLanguageMixinAdapter {
 public static final String PROPERTY="forbric.fabricServerLanguage";
 private static final String MIXIN="net/fabricmc/fabric/mixin/resource/server/LanguageMixin",TARGET="net/minecraft/locale/Language";
 private static final String MAP="Ljava/util/Map;",CONSUMER="Ljava/util/function/BiConsumer;";
 private FabricServerLanguageMixinAdapter(){}
 public static int adapt(ClassNode mixin,Function<String,ClassNode> targets){
  if(!mixin.name.equals(MIXIN)||"off".equalsIgnoreCase(System.getProperty(PROPERTY,"on")))return 0;
  ClassNode target=targets.apply(TARGET);if(target==null)return 0;int count=0;
  MethodNode create=method(mixin,"create","("+MAP+")"+MAP),load=method(target,"loadDefault","()L"+TARGET+";");
  if(create!=null&&load!=null&&!group(create)&&MixinInstructionFingerprint.hash(create).equals("3ac4ff2d2897b3648f326af6daac8724b3b28e196e2457c05790753d24f13aee")
    &&calls(load,"java/util/Map","copyOf","("+MAP+")"+MAP)==0
    &&calls(load,"net/neoforged/neoforge/server/LanguageHook","captureLanguageMap","("+MAP+MAP+")V")==1){
   AnnotationNode redirect=MixinFit.injectorOf(create);
   if(redirect!=null&&redirect.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;")&&MixinFit.atNodes(redirect).size()==1){
    AnnotationNode at=MixinFit.atNodes(redirect).getFirst();
    if(("Ljava/util/Map;copyOf("+MAP+")"+MAP).equals(MixinFit.value(at,"target"))){
     if(create.visibleAnnotations!=null)create.visibleAnnotations.remove(redirect);
     if(create.invisibleAnnotations!=null)create.invisibleAnnotations.remove(redirect);
     create.name="forbric$mergeFabricLanguages";
     MethodNode shim=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,"forbric$captureLanguageMap","("+MAP+MAP+")V",null,null);
     shim.visibleAnnotations=new ArrayList<>(List.of(redirect));set(at,"target","Lnet/neoforged/neoforge/server/LanguageHook;captureLanguageMap("+MAP+MAP+")V");
     shim.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));shim.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,MIXIN,create.name,create.desc,false));shim.instructions.add(new InsnNode(Opcodes.POP));
     shim.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));shim.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));shim.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"net/neoforged/neoforge/server/LanguageHook","captureLanguageMap","("+MAP+MAP+")V",false));shim.instructions.add(new InsnNode(Opcodes.RETURN));shim.maxStack=2;shim.maxLocals=2;mixin.methods.add(shim);count++;
    }
   }
  }
  MethodNode read=method(mixin,"readCorrectVanillaResource","(Ljava/lang/Class;Ljava/lang/String;)Ljava/io/InputStream;");
  MethodNode old=method(target,"parseTranslations","("+CONSUMER+"Ljava/lang/String;)V"),current=method(target,"parseTranslations","("+CONSUMER+CONSUMER+"Ljava/lang/String;)V");
  if(read!=null&&old!=null&&current!=null&&!group(read)
    &&calls(old,"java/lang/Class","getResourceAsStream","(Ljava/lang/String;)Ljava/io/InputStream;")==0
    &&calls(old,TARGET,"parseTranslations",current.desc)==1
    &&calls(current,"java/lang/Class","getResourceAsStream","(Ljava/lang/String;)Ljava/io/InputStream;")==1){
   AnnotationNode redirect=MixinFit.injectorOf(read);
   if(redirect!=null&&MixinFit.stringList(MixinFit.value(redirect,"method")).equals(List.of("parseTranslations"+old.desc))){set(redirect,"method",new ArrayList<>(List.of("parseTranslations"+current.desc)));count++;}
  }
  return count;
 }
 private static MethodNode method(ClassNode c,String name,String desc){return c.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElse(null);}
 private static int calls(MethodNode m,String owner,String name,String desc){int n=0;for(var i:m.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals(owner)&&c.name.equals(name)&&c.desc.equals(desc))n++;return n;}
 private static boolean group(MethodNode m){for(var list:Arrays.asList(m.visibleAnnotations,m.invisibleAnnotations))if(list!=null&&list.stream().anyMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Group;")))return true;return false;}
 private static void set(AnnotationNode a,String key,Object value){for(int i=0;i<a.values.size();i+=2)if(key.equals(a.values.get(i))){a.values.set(i+1,value);return;}a.values.add(key);a.values.add(value);}
}
