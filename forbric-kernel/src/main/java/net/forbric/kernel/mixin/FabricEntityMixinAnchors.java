/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Preserve Fabric entity callbacks at the corresponding stage of the pinned NeoForge body. Effects,
 * flight and monster checks retain their original handlers; the clear-all veto wraps NeoForge's per-effect question. Occupancy bridges its audited handled-result
 * contract to the native bed setter, including native beds with no vanilla OCCUPIED property. The elytra flight tick
 * (EntityElytraEvents.CUSTOM with tickElytra) moves from vanilla's glider-slot choice to NeoForge's empty-glider guard
 * just before it: behind that guard, custom flight with no glider item never reached Fabric's tick. */
public final class FabricEntityMixinAnchors {
 public static final String PROPERTY="forbric.fabricEntityAnchors";
 /** {@code -Dforbric.fabricElytraTickAnchor=off} leaves fabric-api's elytra flight tick at the glider-slot choice. */
 public static final String TICK_PROPERTY="forbric.fabricElytraTickAnchor";
 private static final String GET_RANDOM="Lnet/minecraft/util/Util;getRandom(Ljava/util/List;Lnet/minecraft/util/RandomSource;)Ljava/lang/Object;";
 private static final String LIVING="net/minecraft/world/entity/LivingEntity";
 private static final String BASE="net/fabricmc/fabric/mixin/entity/event/";
 private static final String EFFECT="Lnet/minecraft/world/effect/MobEffectInstance;";
 private static final String ENTITY="Lnet/minecraft/world/entity/Entity;";
 private static final String CI="Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
 private static final String CIR="Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
 private FabricEntityMixinAnchors() { }
 public static int adapt(ClassNode mixin,Function<String,ClassNode> targets) {
  if("off".equalsIgnoreCase(System.getProperty(PROPERTY,"on")))return 0;
  if(!List.of(BASE+"effect/LivingEntityMixin",BASE+"elytra/LivingEntityMixin",BASE+"ServerPlayerMixin",BASE+"LivingEntityMixin").contains(mixin.name))return 0;
  ClassNode target=targets.apply(mixin.name.equals(BASE+"ServerPlayerMixin")?"net/minecraft/server/level/ServerPlayer":LIVING);if(target==null)return 0;int changed=0;
  if(mixin.name.equals(BASE+"effect/LivingEntityMixin")) {
   MethodNode force=method(target,"forceAddEffect","("+EFFECT+ENTITY+")V");
   String old="L"+LIVING+";canBeAffected("+EFFECT+")Z";
   String moved="Lnet/neoforged/neoforge/common/CommonHooks;canMobEffectBeApplied(L"+LIVING+";"+EFFECT+ENTITY+")Z";
   if(force!=null && countCalls(force,LIVING,"canBeAffected","("+EFFECT+")Z")==0
      && countCalls(force,"net/neoforged/neoforge/common/CommonHooks","canMobEffectBeApplied","(L"+LIVING+";"+EFFECT+ENTITY+")Z")==1)
    changed+=move(mixin,"beforeForceAddEffect","("+EFFECT+ENTITY+CI+")V","forceAddEffect",null,"INVOKE",old,"INVOKE",moved);
   MethodNode remove=method(target,"removeAllEffects","()Z");
   if(remove!=null && countCalls(remove,"com/google/common/collect/Maps","newHashMap","(Ljava/util/Map;)Ljava/util/HashMap;")==0
      && countCalls(remove,"java/util/HashMap","<init>","(I)V")==1
      && countCalls(remove,"java/util/Map","isEmpty","()Z")==1
      && remove.instructions.iterator().hasNext() && countNew(remove,"java/util/HashMap")==1)
    changed+=move(mixin,"beforeRemoveAllEffects","("+CIR+")V","removeAllEffects",null,"INVOKE",
      "Lcom/google/common/collect/Maps;newHashMap(Ljava/util/Map;)Ljava/util/HashMap;","NEW","java/util/HashMap");
   if(remove!=null)changed+=earlyRemoveVeto(mixin,remove);
  } else if(mixin.name.equals(BASE+"elytra/LivingEntityMixin")) {
   MethodNode plain=method(target,"canGlide","()Z"),extended=method(target,"canGlide","(Z)Z");
   if(delegatesToAttributePath(plain) && attributeAfterMovementChecks(extended))
    changed+=move(mixin,"injectElytraCheck","("+CIR+")V","canGlide","canGlide(Z)Z","FIELD",
      "Lnet/minecraft/world/entity/EquipmentSlot;VALUES:Ljava/util/List;","FIELD",
      "Lnet/neoforged/neoforge/common/NeoForgeMod;GLIDING_FLIGHT:Lnet/minecraft/core/Holder;");
   if(!"off".equalsIgnoreCase(System.getProperty(TICK_PROPERTY,"on"))&&damageChoiceBehindEmptyGuard(method(target,"updateFallFlying","()V"))
     &&plainPoint(mixin,"injectElytraTick","("+CI+")V")) {
    int tick=move(mixin,"injectElytraTick","("+CI+")V","updateFallFlying()V",null,"INVOKE",GET_RANDOM,"INVOKE","Ljava/util/List;isEmpty()Z");
    if(tick>0)ForbricLog.info("[Forbric/Mixin] fabric-api's elytra flight tick now runs before NeoForge's empty-glider guard in "
      +"LivingEntity.updateFallFlying — custom flight without a glider item reaches EntityElytraEvents.CUSTOM(entity, true) again");
    changed+=tick;
   }
  }
  if(mixin.name.equals(BASE+"ServerPlayerMixin"))changed+=sleepLambda(mixin,target);
  if(mixin.name.equals(BASE+"LivingEntityMixin"))changed+=bedOccupation(mixin,target);
  if(changed>0)ForbricLog.info("[Forbric/Mixin] restored %d entity callback anchor(s) in %s at the corresponding native decision stage",changed,mixin.name.replace('/','.'));
  return changed;
 }
 private static int sleepLambda(ClassNode mixin,ClassNode target) {
  MethodNode handler=method(mixin,"hasNoMonstersNearby","(Ljava/util/List;Lnet/minecraft/core/BlockPos;)Z");
  MethodNode host=method(target,"startSleepInBed","(Lnet/minecraft/core/BlockPos;)Lcom/mojang/datafixers/util/Either;");
  if(handler==null||host==null||hasGroup(handler.visibleAnnotations)||hasGroup(handler.invisibleAnnotations)
    ||countCalls(host,"java/util/List","isEmpty","()Z")!=0)return 0;
  AnnotationNode inject=MixinFit.injectorOf(handler);
  if(inject==null||!inject.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;")
    ||!MixinFit.stringList(MixinFit.value(inject,"method")).equals(List.of("startSleepInBed")))return 0;
  List<AnnotationNode> points=MixinFit.atNodes(inject);
  if(points.size()!=1||!"Ljava/util/List;isEmpty()Z".equals(MixinFit.value(points.getFirst(),"target")))return 0;
  List<AbstractInsnNode> body=code(host);if(body.size()<4||!(body.get(0) instanceof VarInsnNode self)||self.var!=0||self.getOpcode()!=Opcodes.ALOAD
    ||!(body.get(1) instanceof VarInsnNode pos)||pos.var!=1||pos.getOpcode()!=Opcodes.ALOAD
    ||!(body.get(2) instanceof InvokeDynamicInsnNode capture)||!capture.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
    ||!capture.desc.equals("(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/core/BlockPos;)Ljava/util/function/Supplier;")
    ||!call(body.get(3),"java/util/function/Supplier","get","()Ljava/lang/Object;"))return 0;
  List<MethodNode> matches=new ArrayList<>();
  for(Object argument:capture.bsmArgs)if(argument instanceof org.objectweb.asm.Handle h && h.getOwner().equals(target.name)
    &&h.getName().startsWith("lambda$startSleepInBed$")&&h.getDesc().equals(host.desc)
    &&(h.getTag()==Opcodes.H_INVOKEVIRTUAL||h.getTag()==Opcodes.H_INVOKESPECIAL)) {
   MethodNode lambda=method(target,h.getName(),h.getDesc());if(lambda!=null&&countCalls(lambda,"java/util/List","isEmpty","()Z")==1)matches.add(lambda);
  }
  if(matches.size()!=1)return 0;
  set(inject,"method",new ArrayList<>(List.of(matches.getFirst().name+matches.getFirst().desc)));return 1;
 }
 private static final String STATE="net/minecraft/world/level/block/state/BlockState";
 private static final String LEVEL="net/minecraft/world/level/Level";
 private static final String POSITION="Lnet/minecraft/core/BlockPos;";
 private static final String BED_CALL="(L"+LEVEL+";"+POSITION+"L"+LIVING+";Z)V";
 // Exact instruction body in Fabric API 0.155.2's occupancy redirect. Frame/debug/access metadata is ignored.
 private static final String BED_BODY="f783fdec79ad88be77806d13c6c1804729677aa315cf9e8d6f1156351efee6e0";
 private static int bedOccupation(ClassNode mixin,ClassNode target) {
  MethodNode old=method(mixin,"setOccupiedState","(L"+LEVEL+";"+POSITION+"L"+STATE+";I)Z");
  if(old==null||hasGroup(old.visibleAnnotations)||hasGroup(old.invisibleAnnotations)||!bodyHash(old).equals(BED_BODY))return 0;
  AnnotationNode redirect=MixinFit.injectorOf(old);
  if(redirect==null||!redirect.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;"))return 0;
  List<String> selectors=MixinFit.stringList(MixinFit.value(redirect,"method"));
  if(!new java.util.HashSet<>(selectors).equals(java.util.Set.of("startSleeping","lambda$stopSleeping$0")))return 0;
  List<AnnotationNode> points=MixinFit.atNodes(redirect);
  if(points.size()!=1||!("L"+LEVEL+";setBlock("+POSITION+"L"+STATE+";I)Z").equals(MixinFit.value(points.getFirst(),"target")))return 0;
  for(String name:selectors){MethodNode host=method(target,name,"("+POSITION+")V");
   if(host==null||countCalls(host,LEVEL,"setBlock","("+POSITION+"L"+STATE+";I)Z")!=0||countCalls(host,STATE,"setBedOccupied",BED_CALL)!=1)return 0;
  }
  String desc="(L"+STATE+";L"+LEVEL+";"+POSITION+"L"+LIVING+";Z)V";
  if(method(mixin,"forbric$setBedOccupied",desc)!=null)return 0;
  MethodNode bridge=new MethodNode(Opcodes.ACC_PRIVATE,"forbric$setBedOccupied",desc,null,null);
  bridge.visibleAnnotations=new ArrayList<>(List.of(redirect));
  if(old.visibleAnnotations!=null)old.visibleAnnotations.remove(redirect);if(old.invisibleAnnotations!=null)old.invisibleAnnotations.remove(redirect);
  set(points.getFirst(),"target","L"+STATE+";setBedOccupied"+BED_CALL);
  InsnList code=bridge.instructions;LabelNode nativePath=new LabelNode(),perform=new LabelNode(),end=new LabelNode();
  code.add(new VarInsnNode(Opcodes.ALOAD,2));code.add(new VarInsnNode(Opcodes.ALOAD,3));
  code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,LEVEL,"getBlockState","("+POSITION+")L"+STATE+";",false));code.add(new VarInsnNode(Opcodes.ASTORE,6));
  String event="net/fabricmc/fabric/api/entity/event/v1/EntitySleepEvents",callback=event+"$SetBedOccupationState";
  code.add(new FieldInsnNode(Opcodes.GETSTATIC,event,"SET_BED_OCCUPATION_STATE","Lnet/fabricmc/fabric/api/event/Event;"));
  code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"net/fabricmc/fabric/api/event/Event","invoker","()Ljava/lang/Object;",false));
  code.add(new TypeInsnNode(Opcodes.CHECKCAST,callback));code.add(new VarInsnNode(Opcodes.ALOAD,4));code.add(new VarInsnNode(Opcodes.ALOAD,3));code.add(new VarInsnNode(Opcodes.ALOAD,6));code.add(new VarInsnNode(Opcodes.ILOAD,5));
  code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,callback,"setBedOccupationState","(L"+LIVING+";"+POSITION+"L"+STATE+";Z)Z",true));
  code.add(new JumpInsnNode(Opcodes.IFEQ,nativePath));code.add(new InsnNode(Opcodes.RETURN));
  code.add(nativePath);code.add(new FrameNode(Opcodes.F_APPEND,1,new Object[]{STATE},0,null));
  code.add(new VarInsnNode(Opcodes.ALOAD,6));code.add(new FieldInsnNode(Opcodes.GETSTATIC,"net/minecraft/world/level/block/BedBlock","OCCUPIED","Lnet/minecraft/world/level/block/state/properties/BooleanProperty;"));
  code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,STATE,"hasProperty","(Lnet/minecraft/world/level/block/state/properties/Property;)Z",false));code.add(new JumpInsnNode(Opcodes.IFNE,perform));
  code.add(new VarInsnNode(Opcodes.ALOAD,6));code.add(new VarInsnNode(Opcodes.ALOAD,2));code.add(new VarInsnNode(Opcodes.ALOAD,3));code.add(new VarInsnNode(Opcodes.ALOAD,4));
  code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,STATE,"isBed","(Lnet/minecraft/world/level/BlockGetter;"+POSITION+"L"+LIVING+";)Z",false));code.add(new JumpInsnNode(Opcodes.IFEQ,end));
  code.add(perform);code.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));
  code.add(new VarInsnNode(Opcodes.ALOAD,6));code.add(new VarInsnNode(Opcodes.ALOAD,2));code.add(new VarInsnNode(Opcodes.ALOAD,3));code.add(new VarInsnNode(Opcodes.ALOAD,4));code.add(new VarInsnNode(Opcodes.ILOAD,5));
  code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,STATE,"setBedOccupied",BED_CALL,false));code.add(end);code.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));code.add(new InsnNode(Opcodes.RETURN));
  bridge.maxStack=5;bridge.maxLocals=7;mixin.methods.add(bridge);return 1;
 }
 private static final String OPERATION="com/llamalad7/mixinextras/injector/wrapoperation/Operation";
 private static final String EFFECT_REMOVED="(L"+LIVING+";"+EFFECT+")Z";
 private static final String ALLOW_EARLY="net/fabricmc/fabric/api/entity/event/v1/effect/ServerMobEffectEvents$AllowEarlyRemove";
 private static final String CONTEXT="Lnet/fabricmc/fabric/api/entity/event/v1/effect/EffectEventContext;";
 /** Fabric's ALLOW_EARLY_REMOVE veto for "clear every effect" (milk, /effect clear). Fabric wraps vanilla's
  * activeEffects.clear() and puts a vetoed effect back; NeoForge's body has no clear() — it asks
  * EventHooks.onEffectRemoved once per effect and keeps the effect when that answers true. The same veto therefore
  * becomes a wrap of that one call: NeoForge keeping it keeps it, otherwise Fabric's listeners decide. A vetoed effect
  * is also never handed to onEffectsRemoved, which the clear()-and-put-back original could not avoid. */
 private static int earlyRemoveVeto(ClassNode mixin,MethodNode remove) {
  if(countCalls(remove,"java/util/Map","clear","()V")!=0||countCalls(remove,"net/neoforged/neoforge/event/EventHooks","onEffectRemoved",EFFECT_REMOVED)!=1)return 0;
  MethodNode old=method(mixin,"allowRemoveAllEffects","(Ljava/util/Map;L"+OPERATION+";)V");
  if(old==null||hasGroup(old.visibleAnnotations)||hasGroup(old.invisibleAnnotations))return 0;
  AnnotationNode wrap=MixinFit.injectorOf(old);
  if(wrap==null||!wrap.desc.equals("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;")
    ||!MixinFit.stringList(MixinFit.value(wrap,"method")).equals(List.of("removeAllEffects")))return 0;
  List<AnnotationNode> points=MixinFit.atNodes(wrap);
  if(points.size()!=1||!"INVOKE".equals(MixinFit.value(points.getFirst(),"value"))||!"Ljava/util/Map;clear()V".equals(MixinFit.value(points.getFirst(),"target")))return 0;
  // The handler this reproduces: one clear() through the Operation, then one ALLOW_EARLY_REMOVE question per effect,
  // putting back the ones it vetoes — and nothing else a listener could observe.
  if(countCalls(old,OPERATION,"call","([Ljava/lang/Object;)Ljava/lang/Object;")!=1
    ||countCalls(old,ALLOW_EARLY,"allowEarlyRemove","("+EFFECT+"L"+LIVING+";"+CONTEXT+")Z")!=1
    ||countCalls(old,"java/util/Map","put","(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")!=1
    ||method(mixin,"isClient","()Z")==null||method(mixin,"self","()L"+LIVING+";")==null)return 0;
  String desc="(L"+LIVING+";"+EFFECT+"L"+OPERATION+";)Z";
  if(method(mixin,"forbric$allowEarlyRemove",desc)!=null)return 0;
  MethodNode handler=new MethodNode(Opcodes.ACC_PRIVATE,"forbric$allowEarlyRemove",desc,null,null);
  handler.visibleAnnotations=new ArrayList<>(List.of(wrap));
  if(old.visibleAnnotations!=null)old.visibleAnnotations.remove(wrap);if(old.invisibleAnnotations!=null)old.invisibleAnnotations.remove(wrap);
  set(points.getFirst(),"target","Lnet/neoforged/neoforge/event/EventHooks;onEffectRemoved"+EFFECT_REMOVED);
  InsnList code=handler.instructions;LabelNode removable=new LabelNode(),server=new LabelNode(),allowed=new LabelNode();
  code.add(new VarInsnNode(Opcodes.ALOAD,3));code.add(new InsnNode(Opcodes.ICONST_2));code.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));
  code.add(new InsnNode(Opcodes.DUP));code.add(new InsnNode(Opcodes.ICONST_0));code.add(new VarInsnNode(Opcodes.ALOAD,1));code.add(new InsnNode(Opcodes.AASTORE));
  code.add(new InsnNode(Opcodes.DUP));code.add(new InsnNode(Opcodes.ICONST_1));code.add(new VarInsnNode(Opcodes.ALOAD,2));code.add(new InsnNode(Opcodes.AASTORE));
  code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,OPERATION,"call","([Ljava/lang/Object;)Ljava/lang/Object;",true));
  code.add(new TypeInsnNode(Opcodes.CHECKCAST,"java/lang/Boolean"));code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"java/lang/Boolean","booleanValue","()Z",false));
  code.add(new JumpInsnNode(Opcodes.IFEQ,removable));code.add(new InsnNode(Opcodes.ICONST_1));code.add(new InsnNode(Opcodes.IRETURN));
  code.add(removable);code.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));
  code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,mixin.name,"isClient","()Z",false));
  code.add(new JumpInsnNode(Opcodes.IFEQ,server));code.add(new InsnNode(Opcodes.ICONST_0));code.add(new InsnNode(Opcodes.IRETURN));
  code.add(server);code.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));
  code.add(new FieldInsnNode(Opcodes.GETSTATIC,"net/fabricmc/fabric/api/entity/event/v1/effect/ServerMobEffectEvents","ALLOW_EARLY_REMOVE","Lnet/fabricmc/fabric/api/event/Event;"));
  code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"net/fabricmc/fabric/api/event/Event","invoker","()Ljava/lang/Object;",false));
  code.add(new TypeInsnNode(Opcodes.CHECKCAST,ALLOW_EARLY));code.add(new VarInsnNode(Opcodes.ALOAD,2));
  code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,mixin.name,"self","()L"+LIVING+";",false));
  code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"net/fabricmc/fabric/impl/entity/event/effect/MobEffectUtil","getCommandContext","()"+CONTEXT,false));
  code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,ALLOW_EARLY,"allowEarlyRemove","("+EFFECT+"L"+LIVING+";"+CONTEXT+")Z",true));
  code.add(new JumpInsnNode(Opcodes.IFNE,allowed));code.add(new InsnNode(Opcodes.ICONST_1));code.add(new InsnNode(Opcodes.IRETURN));
  code.add(allowed);code.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));code.add(new InsnNode(Opcodes.ICONST_0));code.add(new InsnNode(Opcodes.IRETURN));
  handler.maxStack=5;handler.maxLocals=4;mixin.methods.add(handler);return 1;
 }
 static String bodyHash(MethodNode original) { return MixinInstructionFingerprint.hash(original); }

 private static int move(ClassNode mixin,String handlerName,String handlerDesc,String selector,String replacementSelector,
   String oldKind,String oldTarget,String newKind,String newTarget) {
  MethodNode handler=method(mixin,handlerName,handlerDesc);if(handler==null)return 0;
  if(hasGroup(handler.visibleAnnotations)||hasGroup(handler.invisibleAnnotations))return 0;
  AnnotationNode injector=MixinFit.injectorOf(handler);
  if(injector==null||!injector.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;"))return 0;
  List<String> selected=MixinFit.stringList(MixinFit.value(injector,"method"));
  if(selected.size()!=1||!selected.getFirst().equals(selector))return 0;
  List<AnnotationNode> points=MixinFit.atNodes(injector);
  if(points.size()!=1)return 0;
  AnnotationNode at=points.getFirst();
  if(!oldKind.equals(MixinFit.value(at,"value"))||!oldTarget.equals(MixinFit.value(at,"target")))return 0;
  if(!oldKind.equals(newKind)&&(MixinFit.value(at,"shift")!=null||MixinFit.value(at,"by")!=null))return 0;
  set(at,"value",newKind);set(at,"target",newTarget);
  if(replacementSelector!=null)set(injector,"method",new ArrayList<>(List.of(replacementSelector)));
  return 1;
 }
 /**
  * NeoForge's updateFallFlying: {@code list = …toList(); if (list.isEmpty()) skip; slot = Util.getRandom(list, random)}
  * — the one isEmpty, straight before the one getRandom, both on the same list, and the guard skipping to the label
  * the odd-tens test ({@code ticks % 2 != 0}) skips to. The only way to the guard is falling through that test: no
  * branch in between and no label there anything jumps to. So the guard is reached exactly where vanilla reached the
  * slot choice, and on the path that skips it.
  */
 static boolean damageChoiceBehindEmptyGuard(MethodNode method) {
  if(method==null||countCalls(method,"java/util/List","isEmpty","()Z")!=1
    ||countCalls(method,"net/minecraft/util/Util","getRandom","(Ljava/util/List;Lnet/minecraft/util/RandomSource;)Ljava/lang/Object;")!=1)return false;
  List<AbstractInsnNode> code=code(method);
  int g=-1;for(int i=0;i<code.size();i++)if(call(code.get(i),"java/util/List","isEmpty","()Z"))g=i;
  if(g<3||g+5>=code.size())return false;
  if(!call(code.get(g-3),"java/util/stream/Stream","toList","()Ljava/util/List;")
    ||!(code.get(g-2) instanceof VarInsnNode store)||store.getOpcode()!=Opcodes.ASTORE
    ||!(code.get(g-1) instanceof VarInsnNode load)||load.getOpcode()!=Opcodes.ALOAD||load.var!=store.var
    ||!(code.get(g+1) instanceof JumpInsnNode guard)||guard.getOpcode()!=Opcodes.IFNE
    ||!(code.get(g+2) instanceof VarInsnNode list)||list.getOpcode()!=Opcodes.ALOAD||list.var!=store.var
    ||!(code.get(g+3) instanceof VarInsnNode self)||self.getOpcode()!=Opcodes.ALOAD||self.var!=0
    ||!(code.get(g+4) instanceof FieldInsnNode random)||!random.name.equals("random")||!random.desc.equals("Lnet/minecraft/util/RandomSource;")
    ||!call(code.get(g+5),"net/minecraft/util/Util","getRandom","(Ljava/util/List;Lnet/minecraft/util/RandomSource;)Ljava/lang/Object;"))return false;
  int k=-1;
  for(int i=0;i<g;i++)if(code.get(i) instanceof JumpInsnNode jump&&jump.label==guard.label){if(k>=0)return false;k=i;}
  if(k<2||code.get(k).getOpcode()!=Opcodes.IFNE||code.get(k-1).getOpcode()!=Opcodes.IREM||code.get(k-2).getOpcode()!=Opcodes.ICONST_2)return false;
  for(int i=k+1;i<g;i++)if(code.get(i) instanceof JumpInsnNode||code.get(i) instanceof TableSwitchInsnNode||code.get(i) instanceof LookupSwitchInsnNode)return false;
  java.util.Set<LabelNode> between=new java.util.HashSet<>();
  for(AbstractInsnNode i=code.get(k).getNext();i!=code.get(g);i=i.getNext())if(i instanceof LabelNode label)between.add(label);
  for(var i:method.instructions){
   if(i instanceof JumpInsnNode jump&&between.contains(jump.label))return false;
   if(i instanceof TableSwitchInsnNode t&&(between.contains(t.dflt)||t.labels.stream().anyMatch(between::contains)))return false;
   if(i instanceof LookupSwitchInsnNode l&&(between.contains(l.dflt)||l.labels.stream().anyMatch(between::contains)))return false;
  }
  if(method.tryCatchBlocks!=null)for(var block:method.tryCatchBlocks)if(between.contains(block.handler))return false;
  return true;
 }
 /** An @Inject whose one point is a plain INVOKE: no shift, by, ordinal, opcode, slice or captured locals to reinterpret. */
 static boolean plainPoint(ClassNode mixin,String name,String desc) {
  MethodNode handler=method(mixin,name,desc);if(handler==null)return false;
  if(hasGroup(handler.visibleAnnotations)||hasGroup(handler.invisibleAnnotations))return false;
  AnnotationNode injector=MixinFit.injectorOf(handler);
  if(injector==null||MixinFit.value(injector,"slice")!=null||MixinFit.value(injector,"locals")!=null)return false;
  List<AnnotationNode> points=MixinFit.atNodes(injector);
  if(points.size()!=1)return false;
  for(String key:List.of("shift","by","ordinal","opcode"))if(MixinFit.value(points.getFirst(),key)!=null)return false;
  return true;
 }
 private static boolean delegatesToAttributePath(MethodNode method) {
  if(method==null)return false;List<AbstractInsnNode> code=code(method);
  return code.size()==4&&code.get(0) instanceof VarInsnNode v&&v.getOpcode()==Opcodes.ALOAD&&v.var==0
    &&code.get(1).getOpcode()==Opcodes.ICONST_1&&code.get(2) instanceof MethodInsnNode m
    &&m.getOpcode()==Opcodes.INVOKEVIRTUAL&&m.owner.equals(LIVING)&&m.name.equals("canGlide")&&m.desc.equals("(Z)Z")
    &&code.get(3).getOpcode()==Opcodes.IRETURN;
 }
 private static boolean attributeAfterMovementChecks(MethodNode method) {
  if(method==null)return false;List<AbstractInsnNode> code=code(method);if(code.size()<13)return false;
  // onGround, passenger and levitation all veto before either the attribute or equipment decision.
  if(!call(code.get(1),LIVING,"onGround","()Z")||!call(code.get(4),LIVING,"isPassenger","()Z")
    ||!call(code.get(8),LIVING,"hasEffect","(Lnet/minecraft/core/Holder;)Z"))return false;
  if(!(code.get(2) instanceof JumpInsnNode a)||!(code.get(5) instanceof JumpInsnNode b)||!(code.get(9) instanceof JumpInsnNode c)
    ||a.getOpcode()!=Opcodes.IFNE||b.getOpcode()!=Opcodes.IFNE||c.getOpcode()!=Opcodes.IFNE||a.label!=b.label||a.label!=c.label)return false;
  return code.get(11) instanceof FieldInsnNode field && field.getOpcode()==Opcodes.GETSTATIC
    &&field.owner.equals("net/neoforged/neoforge/common/NeoForgeMod")&&field.name.equals("GLIDING_FLIGHT")
    &&field.desc.equals("Lnet/minecraft/core/Holder;")&&countField(method,field)==1;
 }
 private static int countField(MethodNode method,FieldInsnNode wanted){int count=0;for(var i:method.instructions)if(i instanceof FieldInsnNode f&&f.owner.equals(wanted.owner)&&f.name.equals(wanted.name)&&f.desc.equals(wanted.desc))count++;return count;}
 private static int countNew(MethodNode method,String owner){int count=0;for(var i:method.instructions)if(i instanceof TypeInsnNode t&&t.getOpcode()==Opcodes.NEW&&t.desc.equals(owner))count++;return count;}
 private static int countCalls(MethodNode method,String owner,String name,String desc){int count=0;for(var i:method.instructions)if(call(i,owner,name,desc))count++;return count;}
 private static boolean call(AbstractInsnNode instruction,String owner,String name,String desc){return instruction instanceof MethodInsnNode c&&c.owner.equals(owner)&&c.name.equals(name)&&c.desc.equals(desc);}
 private static boolean hasGroup(List<AnnotationNode> annotations){return annotations!=null&&annotations.stream().anyMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Group;"));}
 private static List<AbstractInsnNode> code(MethodNode method){List<AbstractInsnNode> out=new ArrayList<>();for(var i:method.instructions)if(i.getOpcode()>=0)out.add(i);return out;}
 private static MethodNode method(ClassNode node,String name,String desc){return node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElse(null);}
 private static void set(AnnotationNode annotation,String key,Object value){for(int i=0;i<annotation.values.size();i+=2)if(key.equals(annotation.values.get(i))){annotation.values.set(i+1,value);return;}annotation.values.add(key);annotation.values.add(value);}
}
