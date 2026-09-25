/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
/** Proven moved block-entity removal, a context-expanded call whose Fabric redirect is strictly a no-op, and Fabric's
 * per-screen draw events around NeoForge's screen-stack call. */
public final class FabricClientMixinAnchors {
 public static final String PROPERTY="forbric.fabricClientAnchors";
 private FabricClientMixinAnchors(){}
 public static int adapt(ClassNode mixin,Function<String,ClassNode> targets){
  if("off".equalsIgnoreCase(System.getProperty(PROPERTY,"on")))return 0;
  if(mixin.name.equals("net/fabricmc/fabric/mixin/event/lifecycle/client/LevelChunkMixin")
    ||mixin.name.equals("net/fabricmc/fabric/mixin/event/lifecycle/server/LevelChunkMixin"))return removal(mixin,targets);
  if(mixin.name.equals("net/fabricmc/fabric/mixin/client/renderer/block/render/LevelRendererMixin"))return render(mixin,targets);
  if(mixin.name.equals("net/fabricmc/fabric/mixin/screen/GuiMixin"))return screenExtract(mixin,targets);
  return 0;
 }
 private static final String SCREEN="net/minecraft/client/gui/screens/Screen",GRAPHICS="Lnet/minecraft/client/gui/GuiGraphicsExtractor;";
 private static final String OPERATION="com/llamalad7/mixinextras/injector/wrapoperation/Operation";
 private static final String EVENTS="net/fabricmc/fabric/api/client/screen/v1/ScreenEvents";
 private static final String STACK_CALL="(L"+SCREEN+";Ljava/util/Stack;"+GRAPHICS+"IIF)V";
 /** fabric-screen-api's ScreenEvents.beforeExtract/afterExtract (Jade's overlay on an open screen). Fabric wraps
  * Gui.extractRenderState's call of screen.extractRenderStateWithTooltipAndSubtitles; NeoForge's body instead hands
  * the top screen and its layer stack to ClientHooks.extractScreen, which draws the layers and then the screen from
  * another class, so the wrap bound nothing and neither event ever fired. The same handler now wraps that one call:
  * before-extract, the NeoForge screen draw (layers included), after-extract, on the screen Gui passes as the top. */
 private static int screenExtract(ClassNode mixin,Function<String,ClassNode> targets){
  String handlerDesc="(L"+SCREEN+";"+GRAPHICS+"IIFL"+OPERATION+";)V";
  MethodNode handler=find(mixin,"onExtractGui",handlerDesc);if(handler==null||group(handler))return 0;
  AnnotationNode wrap=MixinFit.injectorOf(handler);
  if(wrap==null||!wrap.desc.equals("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;")
    ||!MixinFit.stringList(MixinFit.value(wrap,"method")).equals(List.of("extractRenderState"))||MixinFit.value(wrap,"slice")!=null)return 0;
  List<AnnotationNode> ats=MixinFit.atNodes(wrap);
  if(ats.size()!=1||!"INVOKE".equals(MixinFit.value(ats.getFirst(),"value"))||MixinFit.value(ats.getFirst(),"ordinal")!=null
    ||!("L"+SCREEN+";extractRenderStateWithTooltipAndSubtitles("+GRAPHICS+"IIF)V").equals(MixinFit.value(ats.getFirst(),"target")))return 0;
  // The handler this reproduces: before-extract, the one draw, after-extract — no cancel, nothing else.
  if(calls(handler,EVENTS,"beforeExtract")!=1||calls(handler,EVENTS,"afterExtract")!=1||calls(handler,OPERATION,"call")!=1)return 0;
  ClassNode gui=targets.apply("net/minecraft/client/gui/Gui");if(gui==null)return 0;
  List<MethodNode> hosts=gui.methods.stream().filter(m->m.name.equals("extractRenderState")).toList();
  int direct=0,stacked=0;for(MethodNode host:hosts){direct+=calls(host,SCREEN,"extractRenderStateWithTooltipAndSubtitles");
   for(var i:host.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals("net/neoforged/neoforge/client/ClientHooks")&&c.name.equals("extractScreen")&&c.desc.equals(STACK_CALL))stacked++;}
  // Fabric's own anchor still present means its wrap binds as written; two stack calls would bracket twice.
  if(direct!=0||stacked!=1)return 0;
  String desc="(L"+SCREEN+";Ljava/util/Stack;"+GRAPHICS+"IIFL"+OPERATION+";)V";
  if(find(mixin,"forbric$onExtractScreens",desc)!=null)return 0;
  MethodNode moved=new MethodNode(Opcodes.ACC_PRIVATE,"forbric$onExtractScreens",desc,null,null);
  moved.visibleAnnotations=new ArrayList<>(List.of(wrap));
  if(handler.visibleAnnotations!=null)handler.visibleAnnotations.remove(wrap);if(handler.invisibleAnnotations!=null)handler.invisibleAnnotations.remove(wrap);
  set(ats.getFirst(),"target","Lnet/neoforged/neoforge/client/ClientHooks;extractScreen"+STACK_CALL);
  InsnList code=moved.instructions;
  event(code,"beforeExtract","BeforeExtract");
  code.add(new VarInsnNode(Opcodes.ALOAD,7));code.add(new IntInsnNode(Opcodes.BIPUSH,6));code.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));
  Object[][] args={{Opcodes.ALOAD,1,null},{Opcodes.ALOAD,2,null},{Opcodes.ALOAD,3,null},{Opcodes.ILOAD,4,"java/lang/Integer:(I)"},{Opcodes.ILOAD,5,"java/lang/Integer:(I)"},{Opcodes.FLOAD,6,"java/lang/Float:(F)"}};
  for(int i=0;i<args.length;i++){code.add(new InsnNode(Opcodes.DUP));code.add(new IntInsnNode(Opcodes.BIPUSH,i));code.add(new VarInsnNode((Integer)args[i][0],(Integer)args[i][1]));
   if(args[i][2] instanceof String box){String[] p=box.split(":");code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,p[0],"valueOf",p[1]+"L"+p[0]+";",false));}
   code.add(new InsnNode(Opcodes.AASTORE));}
  code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,OPERATION,"call","([Ljava/lang/Object;)Ljava/lang/Object;",true));code.add(new InsnNode(Opcodes.POP));
  event(code,"afterExtract","AfterExtract");
  code.add(new InsnNode(Opcodes.RETURN));moved.maxStack=7;moved.maxLocals=8;mixin.methods.add(moved);return 1;
 }
 /** {@code ScreenEvents.<name>(screen).invoker().<name>(screen, graphics, mouseX, mouseY, partialTick)} */
 private static void event(InsnList code,String name,String type){
  String callback=EVENTS+"$"+type;
  code.add(new VarInsnNode(Opcodes.ALOAD,1));code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,EVENTS,name,"(L"+SCREEN+";)Lnet/fabricmc/fabric/api/event/Event;",false));
  code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"net/fabricmc/fabric/api/event/Event","invoker","()Ljava/lang/Object;",false));code.add(new TypeInsnNode(Opcodes.CHECKCAST,callback));
  code.add(new VarInsnNode(Opcodes.ALOAD,1));code.add(new VarInsnNode(Opcodes.ALOAD,3));code.add(new VarInsnNode(Opcodes.ILOAD,4));code.add(new VarInsnNode(Opcodes.ILOAD,5));code.add(new VarInsnNode(Opcodes.FLOAD,6));
  code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,callback,name,"(L"+SCREEN+";"+GRAPHICS+"IIF)V",true));
 }
 private static int calls(MethodNode m,String owner,String name){int n=0;for(var i:m.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals(owner)&&c.name.equals(name))n++;return n;}
 private static int removal(ClassNode mixin,Function<String,ClassNode> targets){
  String owner="net/minecraft/world/level/chunk/LevelChunk",desc="(Lnet/minecraft/core/BlockPos;L"+owner+"$EntityCreationType;)Lnet/minecraft/world/level/block/entity/BlockEntity;";
  MethodNode handler=find(mixin,"onRemoveBlockEntity","(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;");ClassNode target=targets.apply(owner);
  if(handler==null||target==null||group(handler))return 0;MethodNode host=find(target,"getBlockEntity",desc);if(host==null)return 0;
  AnnotationNode injector=MixinFit.injectorOf(handler);if(injector==null||!injector.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;"))return 0;
  if(!MixinFit.stringList(MixinFit.value(injector,"method")).equals(List.of("getBlockEntity"+desc)))return 0;
  Object slice=MixinFit.value(injector,"slice");
  if(!(slice instanceof AnnotationNode sliced)||!(MixinFit.value(sliced,"from") instanceof AnnotationNode from)
    ||!("L"+owner+";createBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;").equals(MixinFit.value(from,"target")))return 0;
  List<AnnotationNode> ats=MixinFit.atNodes(injector).stream().filter(a->"Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;".equals(MixinFit.value(a,"target"))).toList();if(ats.size()!=1)return 0;
  Object originalOrdinal=MixinFit.value(ats.getFirst(),"ordinal");if(originalOrdinal!=null&&!Integer.valueOf(0).equals(originalOrdinal))return 0;
  try{
   Frame<SourceValue>[] frames=new Analyzer<>(new SourceInterpreter()).analyze(owner,host);int ordinal=0,selected=-1,matches=0,selectedInstruction=-1,factory=-1,factories=0;
   for(var instruction:host.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals(owner)&&call.name.equals("createBlockEntity")&&call.desc.equals("(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;")){factory=host.instructions.indexOf(instruction);factories++;}
   for(AbstractInsnNode instruction:host.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals("java/util/Map")&&call.name.equals("remove")&&call.desc.equals("(Ljava/lang/Object;)Ljava/lang/Object;")){
    Frame<SourceValue> frame=frames[host.instructions.indexOf(instruction)];
    if(frame!=null&&frame.getStackSize()>=2){SourceValue receiver=frame.getStack(frame.getStackSize()-2);if(receiver.insns.size()==1&&receiver.insns.iterator().next() instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.GETFIELD&&field.owner.equals(owner)&&field.name.equals("blockEntities")&&field.desc.equals("Ljava/util/Map;")){selected=ordinal;selectedInstruction=host.instructions.indexOf(instruction);matches++;}}
    ordinal++;
   }
   if(matches!=1||factories!=1||selectedInstruction>=factory)return 0;set(ats.getFirst(),"ordinal",selected);remove(injector,"slice");return 1;
  }catch(AnalyzerException malformed){return 0;}
 }
 private static int render(ClassNode mixin,Function<String,ClassNode> targets){
  String model="net/minecraft/client/renderer/block/dispatch/BlockStateModel",tail="Lnet/minecraft/util/RandomSource;Ljava/util/List;)V";
  MethodNode handler=find(mixin,"cancelCollectParts","(L"+model+";"+tail);if(handler==null||group(handler))return 0;
  List<AbstractInsnNode> code=new ArrayList<>();for(var i:handler.instructions)if(i.getOpcode()>=0)code.add(i);if(code.size()!=1||code.getFirst().getOpcode()!=Opcodes.RETURN)return 0;
  ClassNode target=targets.apply("net/minecraft/client/renderer/LevelRenderer");if(target==null)return 0;
  MethodNode host=find(target,"submitBlockDestroyAnimation","(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V");if(host==null)return 0;
  String extended="(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;"+tail;
  int matches=0;for(var i:host.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals(model)&&c.name.equals("collectParts")){if(c.desc.equals("("+tail))return 0;if(c.desc.equals(extended))matches++;}if(matches!=1)return 0;
  AnnotationNode redirect=MixinFit.injectorOf(handler);if(redirect==null||MixinFit.atNodes(redirect).size()!=1)return 0;AnnotationNode at=MixinFit.atNodes(redirect).getFirst();if(!("L"+model+";collectParts("+tail).equals(MixinFit.value(at,"target")))return 0;
  set(at,"target","L"+model+";collectParts"+extended);handler.desc="(L"+model+";"+extended.substring(1);handler.signature=null;handler.parameters=null;handler.visibleParameterAnnotations=null;handler.invisibleParameterAnnotations=null;handler.localVariables=null;handler.maxLocals=7;return 1;
 }
 private static boolean group(MethodNode m){for(var list:Arrays.asList(m.visibleAnnotations,m.invisibleAnnotations))if(list!=null&&list.stream().anyMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Group;")))return true;return false;}
 private static MethodNode find(ClassNode c,String n,String d){return c.methods.stream().filter(m->m.name.equals(n)&&m.desc.equals(d)).findFirst().orElse(null);}
 private static void set(AnnotationNode a,String key,Object value){for(int i=0;i<a.values.size();i+=2)if(key.equals(a.values.get(i))){a.values.set(i+1,value);return;}a.values.add(key);a.values.add(value);}
 private static void remove(AnnotationNode a,String key){for(int i=0;i<a.values.size();i+=2)if(key.equals(a.values.get(i))){a.values.remove(i+1);a.values.remove(i);return;}}
}
