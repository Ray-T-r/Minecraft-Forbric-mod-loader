/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Preserve fabric-api's bed occupancy callback at the corresponding stage of the pinned NeoForge body: its audited
 * handled-result contract bridged to the native bed setter, including native beds with no vanilla OCCUPIED property. The
 * effect, elytra, sleeping-direction and clear-all callbacks this class once moved are now moved by mechanisms that know no
 * mod: {@link CarrierDecisions}, {@link CarrierGuardedPoints}, {@link CarrierReturnedCalls} and the scoped clear relay
 * ({@link net.forbric.kernel.transform.ScopedClearRelayInjector}).
 *
 * <p>Why the occupancy stays named: NeoForge's {@code setBedOccupied} default makes vanilla's {@code setBlock} for a bed that
 * inherits it (so {@link CarrierDefaultChains} could move the redirect there, were it bound to one method), but a carrier
 * mod's bed overrides it with no vanilla call and no OCCUPIED property; fabric-api's event must still be asked for those, and
 * its handler only knows how to do that from the vanilla call's values — which such a bed never computes. Asking the event
 * there means writing the handler's contract (event, then the property check), which is reading the mod, not its shape.
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
 static String bodyHash(MethodNode original) { return MixinInstructionFingerprint.hash(original); }

 private static int countCalls(MethodNode method,String owner,String name,String desc){int count=0;for(var i:method.instructions)if(call(i,owner,name,desc))count++;return count;}
 private static boolean call(AbstractInsnNode instruction,String owner,String name,String desc){return instruction instanceof MethodInsnNode c&&c.owner.equals(owner)&&c.name.equals(name)&&c.desc.equals(desc);}
 private static boolean hasGroup(List<AnnotationNode> annotations){return annotations!=null&&annotations.stream().anyMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Group;"));}
 private static List<AbstractInsnNode> code(MethodNode method){List<AbstractInsnNode> out=new ArrayList<>();for(var i:method.instructions)if(i.getOpcode()>=0)out.add(i);return out;}
 private static MethodNode method(ClassNode node,String name,String desc){return node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElse(null);}
 private static void set(AnnotationNode annotation,String key,Object value){for(int i=0;i<annotation.values.size();i+=2)if(key.equals(annotation.values.get(i))){annotation.values.set(i+1,value);return;}annotation.values.add(key);annotation.values.add(value);}
}
