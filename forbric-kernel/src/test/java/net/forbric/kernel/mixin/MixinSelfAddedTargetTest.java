package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;import java.util.*;import org.junit.jupiter.api.Test;import org.objectweb.asm.*;import org.objectweb.asm.tree.*;
class MixinSelfAddedTargetTest {
 @Test void selfAddedConcreteUniqueTargetIsCheckedFromItsBodyRatherThanItsName() {
  ClassNode target=target(),mixin=mixin();MethodNode helper=helper();mixin.methods.add(helper);MethodNode handler=handler();mixin.methods.add(handler);
  assertEquals(MixinFit.Verdict.FIT,MixinFit.evaluate(bytes(mixin),p->p.equals(target.name+".class")?bytes(target):null).verdict());
  ClassNode view=MixinFit.withSelfAddedMethods(mixin,target);assertNotSame(target,view);assertEquals(1,view.methods.size());assertTrue(target.methods.isEmpty());
  assertNull(MixinFit.stillRejected(mixin,handler,n->target));
 }
 @Test void aMissingMemberInsideAUniqueHelperStillFailsAndAnInjectorCannotFabricateItsOwnTarget(){
  ClassNode target=target(),mixin=mixin();MethodNode helper=helper();helper.instructions.clear();helper.instructions.add(new InsnNode(Opcodes.RETURN));mixin.methods.add(helper);mixin.methods.add(handler());
  assertEquals(MixinFit.Verdict.PARTIAL,MixinFit.evaluate(bytes(mixin),p->bytes(target)).verdict());
  mixin=mixin();MethodNode handler=handler();handler.name="sourcePiece";handler.visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/Unique;"));mixin.methods.add(handler);
  assertSame(target,MixinFit.withSelfAddedMethods(mixin,target));
 }
 @Test void anExistingNativeMethodWinsOverSameNamedUniqueCode(){ClassNode target=target(),mixin=mixin();target.methods.add(new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,"sourcePiece","()V",null,null));mixin.methods.add(helper());assertSame(target,MixinFit.withSelfAddedMethods(mixin,target));}
 static ClassNode target(){ClassNode n=new ClassNode();n.version=Opcodes.V21;n.access=Opcodes.ACC_PUBLIC;n.name="fixture/Target";n.superName="java/lang/Object";return n;}
 static ClassNode mixin(){ClassNode n=target();n.name="fixture/Guest";AnnotationNode a=new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");a.values=new ArrayList<>(List.of("value",List.of(Type.getObjectType("fixture/Target"))));n.invisibleAnnotations=new ArrayList<>(List.of(a));return n;}
 static MethodNode helper(){MethodNode h=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,"sourcePiece","()V",null,null);h.visibleAnnotations=new ArrayList<>(List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/Unique;")));h.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"fixture/Action","run","()V",false));h.instructions.add(new InsnNode(Opcodes.RETURN));return h;}
 static MethodNode handler(){MethodNode h=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,"callback","(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V",null,null);AnnotationNode at=new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");at.values=new ArrayList<>(List.of("value","INVOKE","target","Lfixture/Action;run()V"));AnnotationNode a=new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");a.values=new ArrayList<>(List.of("method",List.of("sourcePiece"),"at",List.of(at)));h.visibleAnnotations=new ArrayList<>(List.of(a));h.instructions.add(new InsnNode(Opcodes.RETURN));h.maxLocals=1;return h;}
 static byte[] bytes(ClassNode n){ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_MAXS);n.accept(w);return w.toByteArray();}
}
