package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class FabricClientMixinAnchorsTest {
 private final net.forbric.kernel.classloading.ForbricClassLoader loader=new net.forbric.kernel.classloading.ForbricClassLoader(new java.net.URL[0],getClass().getClassLoader());
 private static final String CHUNK="net/minecraft/world/level/chunk/LevelChunk",RENDER="net/minecraft/client/renderer/LevelRenderer";
 private ClassNode lifecycle()throws Exception{return StagedFabricMixinFixture.mixin("fabric-lifecycle-events-v1","net/fabricmc/fabric/mixin/event/lifecycle/client/LevelChunkMixin");}
 private ClassNode renderer()throws Exception{return StagedFabricMixinFixture.mixin("fabric-renderer-api-v1","net/fabricmc/fabric/mixin/client/renderer/block/render/LevelRendererMixin");}
 @AfterEach void clear()throws Exception{loader.close();System.clearProperty(MixinOperationSeamTransport.PROPERTY);System.clearProperty(MixinOperationSeamTransport.LOCALS_PROPERTY);System.clearProperty(FabricClientMixinAnchors.PROPERTY);System.clearProperty(net.forbric.kernel.transform.FabricItemContractTransformer.PROPERTY);}
 @Test void removedBlockEntityTargetsItsActualMapAndNeverThePendingNbtMap()throws Exception{
  ClassNode mixin=lifecycle(),target=StagedFabricMixinFixture.game(CHUNK,false);assertEquals(1,FabricClientMixinAnchors.adapt(mixin,n->target));
  MethodNode method=mixin.methods.stream().filter(m->m.name.equals("onRemoveBlockEntity")&&m.desc.equals("(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;")).findFirst().orElseThrow();AnnotationNode inject=MixinFit.injectorOf(method);
  assertNull(MixinFit.value(inject,"slice"));assertEquals(0,MixinFit.value(MixinFit.atNodes(inject).getFirst(),"ordinal"));assertEquals(0,FabricClientMixinAnchors.adapt(mixin,n->target));
 }
 @Test void actualDedicatedServerRemovalUsesTheSameProvenMapWithoutChangingItsCallbackBody()throws Exception{
  ClassNode mixin=StagedFabricMixinFixture.mixin("fabric-lifecycle-events-v1","net/fabricmc/fabric/mixin/event/lifecycle/server/LevelChunkMixin"),target=StagedFabricMixinFixture.game(CHUNK,false);
  MethodNode handler=mixin.methods.stream().filter(m->m.name.equals("onRemoveBlockEntity")&&m.desc.equals("(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;")).findFirst().orElseThrow();String body=MixinInstructionFingerprint.hash(handler);
  assertEquals(1,FabricClientMixinAnchors.adapt(mixin,n->target));AnnotationNode inject=MixinFit.injectorOf(handler);assertNull(MixinFit.value(inject,"slice"));assertEquals(0,MixinFit.value(MixinFit.atNodes(inject).getFirst(),"ordinal"));assertEquals(body,MixinInstructionFingerprint.hash(handler));
  assertEquals(0,FabricClientMixinAnchors.adapt(mixin,n->target));
 }
 @Test void wrongMapOrCallbackGroupCannotBorrowTheRemovalAnchor()throws Exception{
  ClassNode target=StagedFabricMixinFixture.game(CHUNK,false);for(MethodNode m:target.methods)if(m.name.equals("getBlockEntity"))for(var i:m.instructions)if(i instanceof FieldInsnNode f&&f.name.equals("blockEntities"))f.name="unprovedMap";
  assertEquals(0,FabricClientMixinAnchors.adapt(lifecycle(),n->target));
  ClassNode mixin=lifecycle(),real=StagedFabricMixinFixture.game(CHUNK,false);mixin.methods.stream().filter(m->m.name.equals("onRemoveBlockEntity")&&m.desc.equals("(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;")).findFirst().orElseThrow().visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Group;"));assertEquals(0,FabricClientMixinAnchors.adapt(mixin,n->real));
 }
 @Test void onlyTheActualNoopRedirectCanSuppressTheContextExpandedRenderingCall()throws Exception{
  ClassNode mixin=renderer(),target=StagedFabricMixinFixture.game(RENDER,false);assertEquals(1,FabricClientMixinAnchors.adapt(mixin,n->target));MethodNode handler=StagedFabricMixinFixture.method(mixin,"cancelCollectParts");assertEquals(6,Type.getArgumentTypes(handler.desc).length);assertEquals(7,handler.maxLocals);
  new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(mixin.name,handler);
  ClassNode changed=renderer();StagedFabricMixinFixture.method(changed,"cancelCollectParts").instructions.insert(new InsnNode(Opcodes.NOP));assertEquals(0,FabricClientMixinAnchors.adapt(changed,n->target));
 }
 @Test void theActualMiningHandlerHasOneNativeDecisionAndOneExplicitFabricFallback()throws Exception{
  ClassNode mixin=StagedFabricMixinFixture.mixin("fabric-item-api-v1","net/fabricmc/fabric/mixin/item/client/MultiPlayerGameModeMixin"),target=StagedFabricMixinFixture.game("net/minecraft/client/multiplayer/MultiPlayerGameMode",false);
  assertEquals(1,FabricMiningMixinAdapter.adapt(mixin,n->target));MethodNode handler=StagedFabricMixinFixture.method(mixin,"fabricItemContinueBlockBreakingInject");int nativeCalls=0,fabricCalls=0;
  for(var i:handler.instructions)if(i instanceof MethodInsnNode c){if(c.name.equals("shouldCauseBlockBreakReset"))nativeCalls++;if(c.name.equals("allowContinuingBlockBreaking"))fabricCalls++;}
  assertEquals(1,nativeCalls);assertEquals(1,fabricCalls);new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(mixin.name,handler);
  assertEquals(0,FabricMiningMixinAdapter.adapt(mixin,n->target));
 }
 private ClassNode screens()throws Exception{ClassNode source=StagedFabricMixinFixture.mixin("fabric-screen-api-v1","net/fabricmc/fabric/mixin/screen/GuiMixin");MixinStubRebind.noteEcosystem(source.name,net.forbric.api.Ecosystem.FABRIC);return source;}
 @Test void sourceScreenEventsKeepTheirEntireBodyAroundTheActualTopDraw()throws Exception{
  ClassNode mixin=screens(),gui=StagedFabricMixinFixture.game("net/minecraft/client/gui/Gui",false);
  MethodNode original=StagedFabricMixinFixture.method(mixin,"onExtractGui");String body=MixinInstructionFingerprint.hash(original);
  assertEquals(1,screenAdapt(mixin,gui));
  MethodNode kept=mixin.methods.stream().filter(method->method.name.contains("onExtractGui")&&method.desc.equals(original.desc)&&MixinFit.injectorOf(method)==null).findFirst().orElseThrow();
  assertEquals(body,MixinInstructionFingerprint.hash(kept));
  MethodNode moved=mixin.methods.stream().filter(method->method.name.endsWith("$invoke$scope")&&MixinFit.injectorOf(method)!=null).findFirst().orElseThrow();
  assertEquals(List.of("extractRenderState(Lnet/minecraft/client/DeltaTracker;ZZ)V"),MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(moved),"method")));
  assertEquals(GATEWAY_DRAW,MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(moved)).getFirst(),"target"));
  CarpetMixinAdapterTest.verify(mixin);assertEquals(0,screenAdapt(mixin,gui));
 }
 /** Where Fabric's own anchor exists the wrap binds as written; a handler whose bytecode does not verify is not carried. */
 @Test void screenExtractIsLeftAloneWhereFabricsOwnAnchorExistsOrItsHandlerDoesNotVerify()throws Exception{
  ClassNode vanilla=StagedFabricMixinFixture.game("net/minecraft/client/gui/Gui",true);assertEquals(0,screenAdapt(screens(),vanilla));
  ClassNode gui=StagedFabricMixinFixture.game("net/minecraft/client/gui/Gui",false),broken=screens();MethodNode handler=StagedFabricMixinFixture.method(broken,"onExtractGui");
  // An Operation.call with nothing on the stack: no JVM would load this handler.
  handler.instructions.insert(new MethodInsnNode(Opcodes.INVOKEINTERFACE,OPERATION,"call","([Ljava/lang/Object;)Ljava/lang/Object;",true));
  assertThrows(org.objectweb.asm.tree.analysis.AnalyzerException.class,()->new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(broken.name,handler));
  assertEquals(0,screenAdapt(broken,gui),"a handler that does not verify is not carried");
  assertNotNull(MixinFit.injectorOf(handler),"and keeps its own injector");
 }
 /** The handler is kept whole, never reimplemented around one draw: one that calls its Operation twice (and verifies)
  * is carried exactly as written, both draws included. */
 @Test void aScreenExtractHandlerThatDrawsTwiceIsCarriedAsWritten()throws Exception{
  ClassNode gui=StagedFabricMixinFixture.game("net/minecraft/client/gui/Gui",false),twice=screens();MethodNode handler=StagedFabricMixinFixture.method(twice,"onExtractGui");
  MethodInsnNode draw=null;for(var i:handler.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals(OPERATION)&&c.name.equals("call"))draw=c;
  assertNotNull(draw);int operation=(handler.access&Opcodes.ACC_STATIC)!=0?0:1;for(Type t:Arrays.copyOf(Type.getArgumentTypes(handler.desc),Type.getArgumentTypes(handler.desc).length-1))operation+=t.getSize();
  AbstractInsnNode start=draw;while(!(start instanceof VarInsnNode load&&load.getOpcode()==Opcodes.ALOAD&&load.var==operation))start=start.getPrevious();
  InsnList again=new InsnList();for(AbstractInsnNode i=start;i!=draw.getNext();i=i.getNext())if(i.getOpcode()>=0)again.add(i.clone(Map.of()));again.add(new InsnNode(Opcodes.POP));
  handler.instructions.insert(draw.getNext(),again);
  new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(twice.name,handler);
  String body=MixinInstructionFingerprint.hash(handler);
  assertEquals(1,screenAdapt(twice,gui),"a verified handler is carried whatever it does with its Operation");
  MethodNode kept=twice.methods.stream().filter(method->method.name.contains("onExtractGui")&&method.desc.equals(handler.desc)&&MixinFit.injectorOf(method)==null).findFirst().orElseThrow();
  assertEquals(body,MixinInstructionFingerprint.hash(kept),"its body is the one written, not a rebuilt before/draw/after");
  int draws=0;for(var i:kept.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals(OPERATION)&&c.name.equals("call"))draws++;assertEquals(2,draws);
  CarpetMixinAdapterTest.verify(twice);
 }
 @Test void nativeVanillaAndDisabledClientAdaptersRemainUnchanged()throws Exception{
	ClassNode vanillaChunk=StagedFabricMixinFixture.game(CHUNK,true);assertEquals(0,FabricClientMixinAnchors.adapt(lifecycle(),n->vanillaChunk));
  ClassNode vanilla=StagedFabricMixinFixture.game(RENDER,true);assertEquals(0,FabricClientMixinAnchors.adapt(renderer(),n->vanilla));
  System.setProperty(FabricClientMixinAnchors.PROPERTY,"off");ClassNode target=StagedFabricMixinFixture.game(RENDER,false);assertEquals(0,FabricClientMixinAnchors.adapt(renderer(),n->target));
  ClassNode mining=StagedFabricMixinFixture.mixin("fabric-item-api-v1","net/fabricmc/fabric/mixin/item/client/MultiPlayerGameModeMixin");System.setProperty(net.forbric.kernel.transform.FabricItemContractTransformer.PROPERTY,"off");assertEquals(0,FabricMiningMixinAdapter.adapt(mining,n->target));
 }

 private int screenAdapt(ClassNode mixin,ClassNode target){
  return MixinOperationSeamTransport.adapt(mixin,name->name.equals(target.name)?target:CarpetMixinAdapterTest.target(name),NativeCallTestEvidence.staged(),loader);
 }
 private static final String SCREEN_DRAW="Lnet/minecraft/client/gui/screens/Screen;extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";
 private static final String GATEWAY_DRAW="Lnet/minecraft/client/gui/Gui;drawScreen(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";
 private static final String OPERATION="com/llamalad7/mixinextras/injector/wrapoperation/Operation";
 /** A mixin on Gui with one closed {@code (CallbackInfo)V} {@code @Inject} (or {@code kind}) at the screen draw, {@code shift} AFTER/BEFORE/none. */
 private static ClassNode drawHook(String name,String kind,String shift,net.forbric.api.Ecosystem owner){
  ClassNode mixin=new ClassNode();mixin.version=Opcodes.V21;mixin.access=Opcodes.ACC_PUBLIC|Opcodes.ACC_ABSTRACT;mixin.name="test/screen/"+name;mixin.superName="java/lang/Object";
  AnnotationNode target=new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");target.values=new ArrayList<>(List.of("value",new ArrayList<>(List.of(Type.getObjectType("net/minecraft/client/gui/Gui")))));
  mixin.invisibleAnnotations=new ArrayList<>(List.of(target));
  MethodNode handler=new MethodNode(Opcodes.ACC_PRIVATE,"hookScreenRender","(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V",null,null);handler.instructions.add(new InsnNode(Opcodes.RETURN));handler.maxLocals=2;
  AnnotationNode at=new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");at.values=new ArrayList<>(List.of("value","INVOKE","target",SCREEN_DRAW));
  if(shift!=null){at.values.add("shift");at.values.add(new String[]{"Lorg/spongepowered/asm/mixin/injection/At$Shift;",shift});}
  AnnotationNode inject=new AnnotationNode(kind);inject.values=new ArrayList<>(List.of("method",new ArrayList<>(List.of("extractRenderState")),"at",new ArrayList<>(List.of(at))));
  handler.visibleAnnotations=new ArrayList<>(List.of(inject));mixin.methods.add(handler);
  if(owner!=null)MixinStubRebind.noteEcosystem(mixin.name,owner);
  return mixin;
 }
 /** The authored callback remains on its host while the native helper preserves both sides of the exact draw. */
 @Test void aSourceCallbackAtTheScreenDrawUsesTheActualInheritedGateway()throws Exception{
  ClassNode gui=StagedFabricMixinFixture.game("net/minecraft/client/gui/Gui",false);
  for(String shift:Arrays.asList("AFTER","BEFORE",null)){
   ClassNode mixin=drawHook("Draw"+shift,"Lorg/spongepowered/asm/mixin/injection/Inject;",shift,net.forbric.api.Ecosystem.FABRIC);
   assertEquals(1,screenAdapt(mixin,gui),String.valueOf(shift));
   AnnotationNode at=MixinFit.atNodes(MixinFit.injectorOf(mixin.methods.stream().filter(method->MixinFit.injectorOf(method)!=null).findFirst().orElseThrow())).getFirst();
   assertEquals(GATEWAY_DRAW,MixinFit.value(at,"target"));
   assertTrue(mixin.methods.stream().anyMatch(method->method.name.contains("hookScreenRender")&&MixinFit.injectorOf(method)==null),"the source callback is retained on its host; the bridge carries its side");
   assertEquals(0,screenAdapt(mixin,gui),"second adaptation is a no-op");
  }
  MixinStubRebind.forget();
 }
 @Test void unmatchedSignaturesMissingNativePointsAndAVanillaHostRemainUnchanged()throws Exception{
  ClassNode merged=StagedFabricMixinFixture.game("net/minecraft/client/gui/Gui",false),vanilla=StagedFabricMixinFixture.game("net/minecraft/client/gui/Gui",true);
  // A wrap or a redirect replaces the call: its handler is shaped by the call, which NeoForge's is not.
  for(String kind:List.of("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;","Lorg/spongepowered/asm/mixin/injection/Redirect;")){
   ClassNode mixin=drawHook("Wrap"+kind.length(),kind,null,net.forbric.api.Ecosystem.FABRIC);assertEquals(0,screenAdapt(mixin,merged),kind);
   assertEquals(SCREEN_DRAW,MixinFit.value(StagedFabricMixinFixture.at(mixin,"hookScreenRender"),"target"));
  }
  // MinecraftForge's own Gui draws through its drawScreen, and a NeoForge mod was written against NeoForge's call.
  for(net.forbric.api.Ecosystem owner:Arrays.asList(net.forbric.api.Ecosystem.FORGE,net.forbric.api.Ecosystem.NEOFORGE,null)){
   ClassNode mixin=drawHook("Owner"+owner,"Lorg/spongepowered/asm/mixin/injection/Inject;","AFTER",owner);assertEquals(0,screenAdapt(mixin,merged),String.valueOf(owner));
  }
  // Where the host still makes vanilla's call the injector binds as written.
  assertEquals(0,screenAdapt(drawHook("Vanilla","Lorg/spongepowered/asm/mixin/injection/Inject;","AFTER",net.forbric.api.Ecosystem.FABRIC),vanilla));
  ClassNode off=drawHook("Off","Lorg/spongepowered/asm/mixin/injection/Inject;","AFTER",net.forbric.api.Ecosystem.FABRIC);System.setProperty(MixinOperationSeamTransport.PROPERTY,"off");
  assertEquals(0,screenAdapt(off,merged));
  MixinStubRebind.forget();
 }

 private static final String GUI="net/minecraft/client/gui/Gui",INJECT="Lorg/spongepowered/asm/mixin/injection/Inject;",LOCAL="Lcom/llamalad7/mixinextras/sugar/Local;";
 private static final String TRACKER="Lnet/minecraft/client/DeltaTracker;",CANVAS="Lnet/minecraft/client/gui/GuiGraphicsExtractor;",CALLBACK="Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
 private static final String HOST=TRACKER+"ZZ",OBJECTS="Ljava/lang/Object;[Ljava/lang/Object;";
 /** Some other Fabric mod's mixin on Gui (no real mod's names): {@code paintOverScreen} right after the screen draw,
  * taking {@code parameters}, with {@code sugar[i]} — {annotation, key, value, …}, or null — on parameter {@code i}. */
 private static ClassNode painter(String name,String parameters,Object[]... sugar){
  ClassNode mixin=drawHook(name,INJECT,"AFTER",net.forbric.api.Ecosystem.FABRIC);MethodNode handler=mixin.methods.getFirst();
  handler.name="paintOverScreen";handler.desc="("+parameters+")V";Type[] types=Type.getArgumentTypes(handler.desc);
  handler.maxLocals=1+Arrays.stream(types).mapToInt(Type::getSize).sum();
  @SuppressWarnings("unchecked") List<AnnotationNode>[] annotations=new List[types.length];
  for(int i=0;i<sugar.length;i++)if(sugar[i]!=null){AnnotationNode a=new AnnotationNode((String)sugar[i][0]);a.values=new ArrayList<>(Arrays.asList(sugar[i]).subList(1,sugar[i].length));annotations[i]=new ArrayList<>(List.of(a));}
  handler.invisibleParameterAnnotations=annotations;handler.invisibleAnnotableParameterCount=types.length;
  return mixin;
 }
 private static Object[] local(Object... values){Object[] all=new Object[values.length+1];all[0]=LOCAL;System.arraycopy(values,0,all,1,values.length);return all;}
 private static MethodNode named(ClassNode mixin,java.util.function.Predicate<MethodNode> test){return mixin.methods.stream().filter(test).findFirst().orElseThrow();}
 /** The slots read right before the wrapper builds its Invocation (this, the host's arguments, the captured operands). */
 private static List<Integer> handedToTheBridge(MethodNode wrapper){
  InvokeDynamicInsnNode built=null;for(var i:wrapper.instructions)if(i instanceof InvokeDynamicInsnNode d&&d.desc.endsWith("Lnet/forbric/api/OperationSeams$Invocation;"))built=d;
  assertNotNull(built);List<Integer> slots=new ArrayList<>();for(AbstractInsnNode i=built.getPrevious();i instanceof VarInsnNode load;i=i.getPrevious())slots.addFirst(load.var);return slots;
 }
 /** The slots the bridge reads after the callback's own CallbackInfo, i.e. its @Local values, in parameter order. */
 private static List<Integer> handedToTheHandler(MethodNode bridge,String retained){
  List<Integer> slots=new ArrayList<>();boolean afterCallback=false;
  for(var i:bridge.instructions){if(i instanceof MethodInsnNode c&&c.name.equals("<init>")&&c.owner.equals("org/spongepowered/asm/mixin/injection/callback/CallbackInfo"))afterCallback=true;
   else if(i instanceof MethodInsnNode c&&c.name.equals(retained))break;else if(afterCallback&&i instanceof VarInsnNode load)slots.add(load.var);}
  return slots;
 }

 /**
  * A callback after the screen draw that also reads the draw's locals keeps them. Each {@code @Local} is proved in the
  * source Gui to be either the value it handed the draw (the canvas; the first mouse coordinate) or its own untouched
  * argument, and arrives from the gateway operand the carrier passes on to that draw unchanged.
  */
 @Test void aCallbackReadingTheDrawsLocalsReceivesTheGatewaysOwnValues()throws Exception{
  ClassNode gui=StagedFabricMixinFixture.game(GUI,false);
  ClassNode mixin=painter("Painter",HOST+CALLBACK+CANVAS+"I"+TRACKER,null,null,null,null,local("name",List.of("graphics")),local("ordinal",0),local("argsOnly",true));
  MethodNode original=StagedFabricMixinFixture.method(mixin,"paintOverScreen");String body=MixinInstructionFingerprint.hash(original),desc=original.desc;
  assertEquals(1,screenAdapt(mixin,gui));
  MethodNode kept=named(mixin,m->m.name.contains("paintOverScreen")&&m.desc.equals(desc)&&MixinFit.injectorOf(m)==null);
  assertEquals(body,MixinInstructionFingerprint.hash(kept),"the callback's body is the one written");
  for(int i=0;i<Type.getArgumentTypes(desc).length;i++)assertFalse(MixinFit.sugar(kept,i),"the retained body is no handler: no sugar is left for MixinExtras");
  MethodNode moved=named(mixin,m->m.name.endsWith("$invoke$scope")&&MixinFit.injectorOf(m)!=null);
  assertEquals(GATEWAY_DRAW,MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(moved)).getFirst(),"target"));
  // Wrapper (this; Gui, canvas, x, y, partialTick; Operation; tracker, level, loaded): this, the host's three
  // arguments, then the gateway's canvas (slot 2) and x (slot 3) — what the host hands the draw.
  assertEquals(List.of(0,7,8,9,2,3),handedToTheBridge(moved));
  MethodNode bridge=named(mixin,m->m.name.endsWith("$invoke")&&!m.name.endsWith("$scope"));
  assertEquals("("+HOST+CANVAS+"I"+OBJECTS+")Ljava/lang/Object;",bridge.desc);
  // The bridge (this; tracker, level, loaded, canvas, x; operation, arguments) passes canvas, x, then its own tracker.
  assertEquals(List.of(4,5,1),handedToTheHandler(bridge,kept.name));
  CarpetMixinAdapterTest.verify(mixin);assertEquals(0,screenAdapt(mixin,gui),"second adaptation is a no-op");
  MixinStubRebind.forget();
 }

 /**
  * A {@code @Local} that cannot be proved to be a value the draw itself receives keeps the whole callback where it was
  * written: a local the draw never sees, one its discriminators do not single out, a name the source never had, a
  * mutable reference a copy cannot write back, a {@code @Local} ahead of the callback's own parameters, another sugar,
  * and every one of them with the switch off.
  */
 @Test void aLocalTheDrawDoesNotProvablyReceiveLeavesTheCallbackAlone()throws Exception{
  ClassNode gui=StagedFabricMixinFixture.game(GUI,false);
  Map<String,ClassNode> lookAlikes=new LinkedHashMap<>();
  lookAlikes.put("a local the draw never receives",painter("Profiled",HOST+CALLBACK+"Lnet/minecraft/util/profiling/ProfilerFiller;",null,null,null,null,local()));
  lookAlikes.put("two int locals and no discriminator",painter("Either",HOST+CALLBACK+"I",null,null,null,null,local()));
  lookAlikes.put("a name the source never declared",painter("Misnamed",HOST+CALLBACK+CANVAS,null,null,null,null,local("name",List.of("canvas"))));
  lookAlikes.put("a reference the handler could write",painter("Written",HOST+CALLBACK+"Lcom/llamalad7/mixinextras/sugar/ref/LocalRef;",null,null,null,null,local()));
  lookAlikes.put("a local ahead of the callback",painter("Early",CANVAS+CALLBACK,local("name",List.of("graphics")),null));
  lookAlikes.put("shared state",painter("Shared",HOST+CALLBACK+"Lcom/llamalad7/mixinextras/sugar/ref/LocalIntRef;",null,null,null,null,new Object[]{"Lcom/llamalad7/mixinextras/sugar/Share;","value","painted"}));
  for(var lookAlike:lookAlikes.entrySet()){
   assertEquals(0,screenAdapt(lookAlike.getValue(),gui),lookAlike.getKey());
   assertEquals(SCREEN_DRAW,MixinFit.value(StagedFabricMixinFixture.at(lookAlike.getValue(),"paintOverScreen"),"target"),lookAlike.getKey());
   assertEquals(1,lookAlike.getValue().methods.size(),lookAlike.getKey());
  }
  System.setProperty(MixinOperationSeamTransport.LOCALS_PROPERTY,"off");
  ClassNode off=painter("Unswitched",HOST+CALLBACK+CANVAS,null,null,null,null,local("name",List.of("graphics")));
  assertEquals(0,screenAdapt(off,gui),"locals switched off");
  assertEquals(1,screenAdapt(drawHook("ClosedStill",INJECT,"AFTER",net.forbric.api.Ecosystem.FABRIC),gui),"a closed callback is carried with locals switched off");
  MixinStubRebind.forget();
 }

 /**
  * A local the source handed the draw is still refused when the carrier hands the same draw something else in that
  * place. The merged Gui passes NeoForge's real-time partial tick where vanilla passed the game-time one; a source that
  * kept vanilla's in a local cannot have that local filled from the carrier's operand, while its canvas still can.
  */
 @Test void aLocalWhoseOperandTheCarrierComputesDifferentlyIsRefused()throws Exception{
  var staged=NativeCallTestEvidence.staged();ClassNode gui=StagedFabricMixinFixture.game(GUI,false),vanilla=new ClassNode();
  staged.apply(net.forbric.api.Ecosystem.FABRIC,GUI).accept(vanilla);
  MethodNode host=named(vanilla,m->m.name.equals("extractRenderState")&&m.desc.equals("("+HOST+")V"));
  MethodInsnNode draw=null;for(var i:host.instructions)if(i instanceof MethodInsnNode c&&(c.owner+";"+c.name+c.desc).equals(SCREEN_DRAW.substring(1)))draw=c;
  assertNotNull(draw);assertEquals("getGameTimeDeltaTicks",((MethodInsnNode)draw.getPrevious()).name);
  LabelNode kept=new LabelNode(),end=host.localVariables.stream().filter(v->v.index==0).findFirst().orElseThrow().end;int slot=host.maxLocals++;
  InsnList store=new InsnList();store.add(new VarInsnNode(Opcodes.FSTORE,slot));store.add(kept);store.add(new VarInsnNode(Opcodes.FLOAD,slot));host.instructions.insertBefore(draw,store);
  host.localVariables.add(new LocalVariableNode("partialTick","F",null,kept,end,slot));
  java.util.function.BiFunction<net.forbric.api.Ecosystem,String,ClassNode> natives=(family,owner)->family==net.forbric.api.Ecosystem.FABRIC&&owner.equals(GUI)?vanilla:staged.apply(family,owner);
  ClassNode tick=painter("Ticked",HOST+CALLBACK+"F",null,null,null,null,local("name",List.of("partialTick")));
  assertEquals(0,MixinOperationSeamTransport.adapt(tick,name->name.equals(GUI)?gui:CarpetMixinAdapterTest.target(name),natives,loader),"the carrier's partial tick is not the source's");
  ClassNode canvas=painter("Canvassed",HOST+CALLBACK+CANVAS,null,null,null,null,local("name",List.of("graphics")));
  assertEquals(1,MixinOperationSeamTransport.adapt(canvas,name->name.equals(GUI)?gui:CarpetMixinAdapterTest.target(name),natives,loader),"the canvas still is");
  MixinStubRebind.forget();
 }

 private static final java.nio.file.Path LIQUIDBOUNCE=java.nio.file.Path.of("../build/lb-vfp/jars/liquidbounce-9889fe2.jar");
 private static final java.nio.file.Path LIQUIDBOUNCE_RELEASE=java.nio.file.Path.of("../build/lb-vfp/jars/liquidbounce-0.40.1+26.2-20260925.jar");
 /** Issue #59 on the released jars: their menu is drawn right after the screen with the draw's canvas, read through a
  * {@code @Local}. That callback alone is carried to the merged Gui's gateway, with the canvas the host draws with. */
 @Test void releasedLiquidBounceDrawsItsMenuWithTheCanvasTheGatewayDraws()throws Exception{liquidBounce(LIQUIDBOUNCE);}
 @Test void releasedLiquidBounceBuildDrawsItsMenuWithTheCanvasTheGatewayDraws()throws Exception{liquidBounce(LIQUIDBOUNCE_RELEASE);}
 private void liquidBounce(java.nio.file.Path jar)throws Exception{
  ClassNode gui=StagedFabricMixinFixture.game(GUI,false),mixin;
  net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.THIRD_PARTY,java.nio.file.Files.isRegularFile(jar),jar+" absent");
  try(var zip=new java.util.zip.ZipFile(jar.toFile())){var entry=zip.getEntry("net/ccbluex/liquidbounce/injection/mixins/minecraft/client/MixinGui.class");assertNotNull(entry);mixin=MixinFit.parse(zip.getInputStream(entry).readAllBytes());}
  MixinStubRebind.noteEcosystem(mixin.name,net.forbric.api.Ecosystem.FABRIC);
  MethodNode handler=StagedFabricMixinFixture.method(mixin,"hookScreenRender");String body=MixinInstructionFingerprint.hash(handler),desc=handler.desc;
  assertEquals("("+HOST+CALLBACK+CANVAS+")V",desc);assertNotNull(MixinStubRebind.sugar(handler,4,LOCAL),"the canvas is a MixinExtras @Local");
  List<String> others=new ArrayList<>();for(MethodNode m:mixin.methods)if(m!=handler&&MixinFit.injectorOf(m)!=null)others.add(m.name+m.desc+MixinFit.value(MixinFit.injectorOf(m),"method")+MixinFit.atNodes(MixinFit.injectorOf(m)).stream().map(a->MixinFit.value(a,"target")).toList());
  assertEquals(1,screenAdapt(mixin,gui),jar.toString());
  MethodNode kept=named(mixin,m->m.name.contains("hookScreenRender")&&m.desc.equals(desc)&&MixinFit.injectorOf(m)==null);
  assertEquals(body,MixinInstructionFingerprint.hash(kept));
  MethodNode moved=named(mixin,m->m.name.endsWith("$invoke$scope")&&MixinFit.injectorOf(m)!=null);
  assertEquals(List.of("extractRenderState("+HOST+")V"),MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(moved),"method")));
  assertEquals(GATEWAY_DRAW,MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(moved)).getFirst(),"target"));
  assertEquals(List.of(0,7,8,9,2),handedToTheBridge(moved),"the host's arguments, then the canvas the host hands its gateway");
  MethodNode bridge=named(mixin,m->m.name.endsWith("$invoke")&&!m.name.endsWith("$scope"));
  assertEquals("("+HOST+CANVAS+OBJECTS+")Ljava/lang/Object;",bridge.desc);assertEquals(List.of(4),handedToTheHandler(bridge,kept.name));
  List<String> after=new ArrayList<>();for(MethodNode m:mixin.methods)if(MixinFit.injectorOf(m)!=null&&m!=moved)after.add(m.name+m.desc+MixinFit.value(MixinFit.injectorOf(m),"method")+MixinFit.atNodes(MixinFit.injectorOf(m)).stream().map(a->MixinFit.value(a,"target")).toList());
  assertEquals(others,after,"nothing else in the mixin is touched");
  CarpetMixinAdapterTest.verify(mixin);assertEquals(0,screenAdapt(mixin,gui),"second adaptation is a no-op");
  MixinStubRebind.forget();
 }
}
