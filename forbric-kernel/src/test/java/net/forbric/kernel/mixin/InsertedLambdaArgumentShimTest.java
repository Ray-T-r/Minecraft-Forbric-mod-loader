package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.file.*;
import java.util.zip.*;
import net.forbric.kernel.transform.DuplicateLambdaPruneInjector;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class InsertedLambdaArgumentShimTest {
 private static final String OLD="(JLjava/lang/Object;Ljava/lang/Object;D)V",NEW="(JLjava/lang/Object;Ljava/lang/String;Ljava/lang/Object;D)V",CI="Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
 @AfterEach void reset(){System.clearProperty(InsertedLambdaArgumentShim.PROPERTY);}
 public static class Shell {
  public static Object[] seen;
  private static void capture(long number,Object first,Object second,double fraction,CallbackInfo callback){seen=new Object[]{number,first,second,fraction,callback};callback.cancel();}
 }
 private ClassNode shell()throws Exception{
  ClassNode node;try(var in=Shell.class.getResourceAsStream("InsertedLambdaArgumentShimTest$Shell.class")){node=MixinFit.parse(in.readAllBytes());}
  node.nestHostClass=null;node.innerClasses.clear();
  AnnotationNode mixin=new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");mixin.values=new ArrayList<>(List.of("value",List.of(Type.getObjectType("probe/Target"))));node.invisibleAnnotations=new ArrayList<>(List.of(mixin));
  AnnotationNode at=new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");at.values=new ArrayList<>(List.of("value","INVOKE","target","Lprobe/Anchor;call()V","ordinal",0));
  AnnotationNode inject=new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");inject.values=new ArrayList<>(List.of("method",new ArrayList<>(List.of("lambda$render$0"+OLD)),"at",List.of(at),"cancellable",true));
  StagedFabricMixinFixture.method(node,"capture").visibleAnnotations=new ArrayList<>(List.of(inject));return node;
 }
 private ClassNode target(){
  ClassNode target=new ClassNode();target.name="probe/Target";target.superName="java/lang/Object";
  MethodNode lambda=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,"lambda$render$0",NEW,null,null);lambda.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"probe/Anchor","call","()V",false));lambda.instructions.add(new InsnNode(Opcodes.RETURN));target.methods.add(lambda);
  MethodNode caller=new MethodNode(Opcodes.ACC_PUBLIC,"render","()V",null,null);caller.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,target.name,lambda.name,lambda.desc,false));target.methods.add(caller);
  DuplicateLambdaPruneInjector.recordDroppedForTest(target.name,lambda.name,OLD);return target;
 }
 @Test void repeatedArgumentsHaveOneOrderedMappingButAmbiguousOrReorderedSequencesAreRejected(){
  assertArrayEquals(new int[]{0,1,3,4},InsertedLambdaArgumentShim.uniqueEmbedding(Type.getArgumentTypes(OLD),Type.getArgumentTypes(NEW)));
  assertNull(InsertedLambdaArgumentShim.uniqueEmbedding(Type.getArgumentTypes("(Ljava/lang/Object;)V"),Type.getArgumentTypes("(Ljava/lang/Object;Ljava/lang/Object;)V")));
  assertNull(InsertedLambdaArgumentShim.uniqueEmbedding(Type.getArgumentTypes("(IJ)V"),Type.getArgumentTypes("(JDI)V")));
  assertNull(InsertedLambdaArgumentShim.uniqueEmbedding(Type.getArgumentTypes(OLD),Type.getArgumentTypes(OLD)));
 }
 @Test void actualJvmShimPreservesRepeatedObjectPositionsWideSlotsAndCancellation()throws Exception{
  ClassNode shell=shell(),target=target();assertEquals(1,InsertedLambdaArgumentShim.adapt(shell,n->target));assertEquals(0,InsertedLambdaArgumentShim.adapt(shell,n->target));
  MethodNode shim=StagedFabricMixinFixture.method(shell,"forbric$expanded$capture");new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(shell.name,shim);
  byte[] bytes=StagedFabricMixinFixture.bytes(shell);Class<?> defined=new ClassLoader(getClass().getClassLoader()){Class<?> define(){return defineClass(shell.name.replace('/','.'),bytes,0,bytes.length);}}.define();
  var method=defined.getDeclaredMethod(shim.name,long.class,Object.class,String.class,Object.class,double.class,CallbackInfo.class);method.setAccessible(true);Object first=new Object(),second=new Object();CallbackInfo callback=new CallbackInfo("render",true);method.invoke(null,123456789012L,first,"inserted",second,3.25d,callback);
  Object[] seen=(Object[])defined.getField("seen").get(null);assertArrayEquals(new Object[]{123456789012L,first,second,3.25d,callback},seen);assertTrue(callback.isCancelled());
 }
 @Test void actualLitematicaOpaqueAndTranslucentHandlersFollowThePrunedLiveLambda()throws Exception{
  Path jar;try(var files=Files.list(Path.of("run/client-merged-pack/mods"))){jar=files.filter(p->p.getFileName().toString().contains("litematica")&&p.toString().endsWith(".jar")).findFirst().orElseThrow();}
  ClassNode mixin;try(ZipFile z=new ZipFile(jar.toFile())){mixin=MixinFit.parse(z.getInputStream(z.getEntry("fi/dy/masa/litematica/mixin/render/MixinLevelRenderer.class")).readAllBytes());}
  String owner="net/minecraft/client/renderer/LevelRenderer";ClassNode target=StagedFabricMixinFixture.game(owner,false);target=MixinFit.parse(new DuplicateLambdaPruneInjector().transform(owner.replace('/','.'),StagedFabricMixinFixture.bytes(target),null));ClassNode finalTarget=target;
  assertEquals(2,InsertedLambdaArgumentShim.adapt(mixin,n->finalTarget));
  for(String suffix:List.of("Opaque","Translucent")){MethodNode shim=StagedFabricMixinFixture.method(mixin,"forbric$expanded$litematica_renderMainSection_"+suffix);assertEquals(12,Type.getArgumentTypes(shim.desc).length);assertEquals("Lorg/joml/Matrix4fc;",Type.getArgumentTypes(shim.desc)[4].getDescriptor());new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(mixin.name,shim);assertNull(MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin,"litematica_renderMainSection_"+suffix)));}
 }
 @Test void existingOldBodyUnreferencedLambdaMissingAnchorAndWrongOrdinalRefuse()throws Exception{
  for(int mode=0;mode<4;mode++){ClassNode mixin=shell(),target=target();if(mode==0)target.methods.add(new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,"lambda$render$0",OLD,null,null));if(mode==1)target.methods.removeIf(m->m.name.equals("render"));if(mode==2)StagedFabricMixinFixture.method(target,"lambda$render$0").instructions.clear();if(mode==3){AnnotationNode at=StagedFabricMixinFixture.at(mixin,"capture");at.values.set(at.values.indexOf("ordinal")+1,1);}assertEquals(0,InsertedLambdaArgumentShim.adapt(mixin,n->target),"mode="+mode);}
 }
 @Test void groupLocalsSugarAndStaticMismatchCannotBorrowTheShim()throws Exception{
  for(int mode=0;mode<4;mode++){ClassNode mixin=shell(),target=target();MethodNode handler=StagedFabricMixinFixture.method(mixin,"capture");AnnotationNode inject=MixinFit.injectorOf(handler);if(mode==0)handler.visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Group;"));if(mode==1){inject.values.add("locals");inject.values.add(new String[]{"Lorg/spongepowered/asm/mixin/injection/callback/LocalCapture;","CAPTURE_FAILHARD"});}if(mode==2){handler.visibleParameterAnnotations=new List[5];handler.visibleParameterAnnotations[0]=List.of(new AnnotationNode("Lcom/llamalad7/mixinextras/sugar/Local;"));}if(mode==3)handler.access&=~Opcodes.ACC_STATIC;assertEquals(0,InsertedLambdaArgumentShim.adapt(mixin,n->target),"mode="+mode);}
 }
 @Test void absentPrunerEvidenceAndExplicitOffRefuse()throws Exception{
  ClassNode mixin=shell(),target=target();target.name="probe/NeverPruned";assertEquals(0,InsertedLambdaArgumentShim.adapt(mixin,n->target));System.setProperty(InsertedLambdaArgumentShim.PROPERTY,"off");assertEquals(0,InsertedLambdaArgumentShim.adapt(shell(),n->target()));
 }
}
