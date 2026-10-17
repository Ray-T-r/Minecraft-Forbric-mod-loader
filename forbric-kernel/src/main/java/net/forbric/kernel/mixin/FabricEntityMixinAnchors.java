/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Preserve Fabric entity callbacks at the corresponding stage of the pinned NeoForge body: the monster check of the sleep
 * start, the bed occupancy (its audited handled-result contract bridged to the native bed setter, including native beds with
 * no vanilla OCCUPIED property) and the sleeping direction. The effect, elytra and clear-all callbacks this class once moved
 * are now moved by mechanisms that know no mod: {@link CarrierDecisions}, {@link CarrierGuardedPoints} and the scoped clear
 * relay ({@link net.forbric.kernel.transform.ScopedClearRelayInjector}).
 *
 * <p>Every injector is read as Mixin reads it, never by spelling: its selectors by the method they bind in the merged class,
 * its point by the member it names in the method it was written for ({@link MixinCallbackShape#names}). */
public final class FabricEntityMixinAnchors {
 public static final String PROPERTY="forbric.fabricEntityAnchors";
 private static final String LIVING="net/minecraft/world/entity/LivingEntity";
 private static final String BASE="net/fabricmc/fabric/mixin/entity/event/";
 private FabricEntityMixinAnchors() { }
 public static int adapt(ClassNode mixin,Function<String,ClassNode> targets) {
  return adapt(mixin,targets,NativeGameReferences::reference);
 }
 /** {@code references} gives the class the mod was compiled against, where a point written without its owner or
  * descriptor is read ({@link MixinCallbackShape#names}); without it, only a point spelled with both names a member.
  * Selectors are bound as Mixin binds them in the merged class ({@link MixinCallbackShape#binds}), never by spelling. */
 static int adapt(ClassNode mixin,Function<String,ClassNode> targets,java.util.function.BiFunction<net.forbric.api.Ecosystem,String,ClassNode> references) {
  if("off".equalsIgnoreCase(System.getProperty(PROPERTY,"on")))return 0;
  Function<String,ClassNode> sources=owner->references==null?null:references.apply(MixinStubRebind.ecosystemOf(mixin.name),owner);
  if(!mixin.name.equals(BASE+"LivingEntityMixin"))return 0;
  String owner=LIVING;
  ClassNode target=targets.apply(owner);if(target==null)return 0;int changed=0;ClassNode source=sources.apply(owner);
  if(mixin.name.equals(BASE+"LivingEntityMixin"))changed+=bedOccupation(mixin,target,source);
  if(mixin.name.equals(BASE+"LivingEntityMixin"))changed+=sleepDirection(mixin,target,source);
  if(changed>0)ForbricLog.info("[Forbric/Mixin] restored %d entity callback anchor(s) in %s at the corresponding native decision stage",changed,mixin.name.replace('/','.'));
  return changed;
 }
 private static final String STATE="net/minecraft/world/level/block/state/BlockState";
 private static final String LEVEL="net/minecraft/world/level/Level";
 private static final String POSITION="Lnet/minecraft/core/BlockPos;";
 private static final String BED_CALL="(L"+LEVEL+";"+POSITION+"L"+LIVING+";Z)V";
 // Exact instruction body in Fabric API 0.155.2's occupancy redirect. Frame/debug/access metadata is ignored.
 private static final String BED_BODY="f783fdec79ad88be77806d13c6c1804729677aa315cf9e8d6f1156351efee6e0";
 private static int bedOccupation(ClassNode mixin,ClassNode target,ClassNode source) {
  MethodNode old=method(mixin,"setOccupiedState","(L"+LEVEL+";"+POSITION+"L"+STATE+";I)Z");
  if(old==null||hasGroup(old.visibleAnnotations)||hasGroup(old.invisibleAnnotations)||!bodyHash(old).equals(BED_BODY))return 0;
  AnnotationNode redirect=MixinFit.injectorOf(old);
  if(redirect==null||!redirect.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;"))return 0;
  // The methods Mixin binds the redirect to in the merged class (the sleep start and the wake-up lambda), however its
  // selectors are written: each one of them must have lost vanilla's setBlock to NeoForge's setBedOccupied.
  List<MethodNode> hosts=MixinTargetSelectors.bound(old,target);
  if(hosts==null||hosts.isEmpty())return 0;
  List<MethodNode> written=source==null?null:MixinTargetSelectors.bound(old,source);
  List<AnnotationNode> points=MixinFit.atNodes(redirect);
  if(points.size()!=1||!MixinCallbackShape.namesInEach(points.getFirst(),"L"+LEVEL+";setBlock("+POSITION+"L"+STATE+";I)Z",written))return 0;
  for(MethodNode host:hosts){
   if((host.access&Opcodes.ACC_STATIC)!=0||countCalls(host,LEVEL,"setBlock","("+POSITION+"L"+STATE+";I)Z")!=0
     ||countCalls(host,STATE,"setBedOccupied",BED_CALL)!=1)return 0;
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
 /** fabric-entity-events-v1 5.0.4/5.0.5's onGetSleepingDirection: one Operation.call, then MODIFY_SLEEPING_DIRECTION. */
 static final String SLEEP_DIRECTION_BODY="6a7ab73c3bbee71f32b7af99490b87be878b9208bda2296f9240638ad59d7a73";
 private static final String DIRECTION="Lnet/minecraft/core/Direction;";
 /** Fabric's MODIFY_SLEEPING_DIRECTION wraps vanilla's BedBlock.getBedOrientation in LivingEntity.getBedOrientation —
  * what the sleeping body is drawn and the sleeping camera faced by. NeoForge's body asks the block instead
  * (isBed, getBedDirection) and never calls it, so the wrap bound nowhere. The same event is asked on the answer:
  * when the entity has a sleeping position, the native direction (FACING for a vanilla bed, the block's own for a
  * modded one, null for a non-bed spot) goes through the listeners, as vanilla's did. */
 private static int sleepDirection(ClassNode mixin,ClassNode target,ClassNode source) {
  MethodNode host=method(target,"getBedOrientation","()"+DIRECTION);
  if(host==null||countCalls(host,"net/minecraft/world/level/block/BedBlock","getBedOrientation","(Lnet/minecraft/world/level/BlockGetter;"+POSITION+")"+DIRECTION)!=0
    ||countCalls(host,STATE,"isBed","(Lnet/minecraft/world/level/BlockGetter;"+POSITION+"L"+LIVING+";)Z")!=1
    ||countCalls(host,STATE,"getBedDirection","(Lnet/minecraft/world/level/LevelReader;"+POSITION+")"+DIRECTION)!=1
    ||countCalls(host,LIVING,"getSleepingPos","()Ljava/util/Optional;")!=1)return 0;
  MethodNode old=method(mixin,"onGetSleepingDirection","(Lnet/minecraft/world/level/BlockGetter;"+POSITION+"L"+OPERATION+";)"+DIRECTION);
  if(old==null||hasGroup(old.visibleAnnotations)||hasGroup(old.invisibleAnnotations)||!bodyHash(old).equals(SLEEP_DIRECTION_BODY))return 0;
  AnnotationNode wrap=MixinFit.injectorOf(old);
  if(wrap==null||!wrap.desc.equals("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;")
    ||!MixinCallbackShape.binds(old,target,host.name+host.desc))return 0;
  List<AnnotationNode> points=MixinFit.atNodes(wrap);
  if(points.size()!=1||!"INVOKE".equals(MixinFit.value(points.getFirst(),"value"))
    ||!MixinCallbackShape.names(points.getFirst(),"Lnet/minecraft/world/level/block/BedBlock;getBedOrientation(Lnet/minecraft/world/level/BlockGetter;"+POSITION+")"+DIRECTION,
      MixinCallbackShape.written(old,source)))return 0;
  String desc="("+DIRECTION+")"+DIRECTION;
  if(method(mixin,"forbric$modifySleepingDirection",desc)!=null)return 0;
  if(old.visibleAnnotations!=null)old.visibleAnnotations.remove(wrap);if(old.invisibleAnnotations!=null)old.invisibleAnnotations.remove(wrap);
  MethodNode handler=new MethodNode(Opcodes.ACC_PRIVATE,"forbric$modifySleepingDirection",desc,null,null);
  AnnotationNode at=new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");at.values=new ArrayList<>(List.of("value","RETURN"));
  AnnotationNode modify=new AnnotationNode("Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;");
  modify.values=new ArrayList<>(List.of("method",new ArrayList<>(List.of("getBedOrientation()"+DIRECTION)),"at",new ArrayList<>(List.of(at))));
  handler.visibleAnnotations=new ArrayList<>(List.of(modify));
  // Every return in the body as it stands when injections are prepared (an @Overwrite's included). An early return
  // another mixin's cancellable @Inject adds is not seen — it bypassed Fabric's own wrap of the inner call too. Without
  // a sleeping position the answer is passed through untouched, which is when vanilla never reached the wrapped call.
  InsnList code=handler.instructions;LabelNode fire=new LabelNode();
  code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new TypeInsnNode(Opcodes.CHECKCAST,LIVING));
  code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,LIVING,"getSleepingPos","()Ljava/util/Optional;",false));
  code.add(new InsnNode(Opcodes.ACONST_NULL));code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"java/util/Optional","orElse","(Ljava/lang/Object;)Ljava/lang/Object;",false));
  code.add(new TypeInsnNode(Opcodes.CHECKCAST,"net/minecraft/core/BlockPos"));code.add(new VarInsnNode(Opcodes.ASTORE,2));
  code.add(new VarInsnNode(Opcodes.ALOAD,2));code.add(new JumpInsnNode(Opcodes.IFNONNULL,fire));
  code.add(new VarInsnNode(Opcodes.ALOAD,1));code.add(new InsnNode(Opcodes.ARETURN));
  code.add(fire);code.add(new FrameNode(Opcodes.F_APPEND,1,new Object[]{"net/minecraft/core/BlockPos"},0,null));
  String event="net/fabricmc/fabric/api/entity/event/v1/EntitySleepEvents",callback=event+"$ModifySleepingDirection";
  code.add(new FieldInsnNode(Opcodes.GETSTATIC,event,"MODIFY_SLEEPING_DIRECTION","Lnet/fabricmc/fabric/api/event/Event;"));
  code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"net/fabricmc/fabric/api/event/Event","invoker","()Ljava/lang/Object;",false));
  code.add(new TypeInsnNode(Opcodes.CHECKCAST,callback));
  code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new TypeInsnNode(Opcodes.CHECKCAST,LIVING));code.add(new VarInsnNode(Opcodes.ALOAD,2));code.add(new VarInsnNode(Opcodes.ALOAD,1));
  code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,callback,"modifySleepDirection","(L"+LIVING+";"+POSITION+DIRECTION+")"+DIRECTION,true));
  code.add(new InsnNode(Opcodes.ARETURN));
  handler.maxStack=4;handler.maxLocals=3;mixin.methods.add(handler);
  ForbricLog.info("[Forbric/Mixin] fabric-api's sleeping direction now modifies LivingEntity.getBedOrientation's answer — "
    +"NeoForge's body asks the bed block instead of BedBlock.getBedOrientation, so the wrap bound nowhere");
  return 1;
 }
 private static final String OPERATION="com/llamalad7/mixinextras/injector/wrapoperation/Operation";
 static String bodyHash(MethodNode original) { return MixinInstructionFingerprint.hash(original); }

 private static int countCalls(MethodNode method,String owner,String name,String desc){int count=0;for(var i:method.instructions)if(call(i,owner,name,desc))count++;return count;}
 private static boolean call(AbstractInsnNode instruction,String owner,String name,String desc){return instruction instanceof MethodInsnNode c&&c.owner.equals(owner)&&c.name.equals(name)&&c.desc.equals(desc);}
 private static boolean hasGroup(List<AnnotationNode> annotations){return annotations!=null&&annotations.stream().anyMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Group;"));}
 private static List<AbstractInsnNode> code(MethodNode method){List<AbstractInsnNode> out=new ArrayList<>();for(var i:method.instructions)if(i.getOpcode()>=0)out.add(i);return out;}
 private static MethodNode method(ClassNode node,String name,String desc){return node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElse(null);}
 private static void set(AnnotationNode annotation,String key,Object value){for(int i=0;i<annotation.values.size();i+=2)if(key.equals(annotation.values.get(i))){annotation.values.set(i+1,value);return;}annotation.values.add(key);annotation.values.add(value);}
}
