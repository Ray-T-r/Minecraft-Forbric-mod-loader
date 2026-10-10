package net.forbric.kernel.mixin.weave;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;import java.util.*;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import net.fabricmc.api.EnvType;import net.forbric.api.Ecosystem;import net.forbric.kernel.mixin.MixinOperationSeamTransport;

class MixinOperationSeamTransportWeaveTest implements Opcodes {
    @TempDir Path work;
    /** The guest mixin configs declare compatibilityLevel JAVA_25, which Mixin refuses to set on an older runtime. */
    @org.junit.jupiter.api.BeforeEach void java25(){net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.JAVA_25,Runtime.version().feature()>=25,"the guest mixin configs declare compatibilityLevel JAVA_25, which Mixin sets only on Java 25");}
    @Test void theOriginalOperationCanReplaceTheReceiverSkipOrRepeatOnlyTheTopDrawAfterNativeCancellation()throws Exception {
        Map<String,String> sources=new LinkedHashMap<>();
        sources.put("audit.OperationProbe","""
            package audit;public class OperationProbe{public static java.util.List<String>log=new java.util.ArrayList<>();public void run(){unknown.Dispatch host=new unknown.Dispatch();for(int value:new int[]{1,2,3,-1,4}){log.clear();host.draw(value);System.out.println("[Operation] "+value+" "+log);}log.clear();host.direct(new unknown.Context(),7);System.out.println("[Operation] direct "+log);}}
            """);
        sources.put("unknown.Context","package unknown;public class Context{}");
        sources.put("unknown.Widget","package unknown;public class Widget{public final String name;public static final Widget replacement=new Widget(\"replacement\");public Widget(String name){this.name=name;}public void present(Context context,int value){audit.OperationProbe.log.add(\"draw:\"+name+\":\"+value);}}");
        sources.put("unknown.Gateway","""
            package unknown;public abstract class Gateway{private static final Widget layer=new Widget("layer");protected abstract Widget widget();protected void gateway(Context context,int value){once(layer,context,99);once(widget(),context,value);}public void direct(Context context,int value){once(widget(),context,value);}private static void once(Widget widget,Context context,int value){audit.OperationProbe.log.add("pre:"+widget.name);if(value==-1){audit.OperationProbe.log.add("cancel:"+widget.name);return;}widget.present(context,value);audit.OperationProbe.log.add("post:"+widget.name);}}
            """);
        sources.put("unknown.Dispatch","package unknown;public class Dispatch extends Gateway{private final Widget widget=new Widget(\"top\");public Widget widget(){return widget;}public void draw(int value){Context context=new Context();gateway(context,value);}}");
        sources.put("unrelated.Subscriber","""
            package unrelated;import unknown.*;import com.llamalad7.mixinextras.injector.wrapoperation.*;import org.spongepowered.asm.mixin.*;import org.spongepowered.asm.mixin.injection.*;
            @Mixin(Dispatch.class)public class Subscriber{@Shadow private Widget widget;
            @WrapOperation(method="draw",at=@At(value="INVOKE",target="Lunknown/Widget;present(Lunknown/Context;I)V"))
            private void arbitrary(Widget receiver,Context context,int value,Operation<Void> original){if(widget!=receiver)throw new AssertionError("host receiver changed");audit.OperationProbe.log.add("before:"+receiver.name+":"+value);if(value!=2){original.call(Widget.replacement,context,value+10);if(value==3)original.call(Widget.replacement,context,value+20);}audit.OperationProbe.log.add("after:"+receiver.name);}}
            """);
        List<Path> files=new ArrayList<>();for(var entry:sources.entrySet()){Path path=work.resolve("src").resolve(entry.getKey().replace('.','/')+".java");Files.createDirectories(path.getParent());Files.writeString(path,entry.getValue());files.add(path);}
        Path config=work.resolve("operation.mixins.json");Files.writeString(config,"{\"required\":true,\"compatibilityLevel\":\"JAVA_25\",\"package\":\"unrelated\",\"mixins\":[\"Subscriber\"],\"injectors\":{\"defaultRequire\":1}}");
        Path current=WeaveHarness.fixture(work,"operations",files,Map.of("operation.mixins.json",config),List.of("-g"));Path fixture=NativeWeaveReferences.with(work,current,Map.of("unknown/Dispatch",source()));
        var configs=List.of(new WeaveHarness.Config("operation.mixins.json","unknown-operation",Ecosystem.FABRIC));
        var on=WeaveHarness.run(work,"on",fixture,configs,List.of(),EnvType.SERVER,"audit.OperationProbe","run",Map.of());
        assertTrue(on.printed("[Operation] 1 [pre:layer, draw:layer:99, post:layer, pre:top, before:top:1, draw:replacement:11, after:top, post:top]"),on.describe());
        assertTrue(on.printed("[Operation] 2 [pre:layer, draw:layer:99, post:layer, pre:top, before:top:2, after:top, post:top]"),on.describe());
        assertTrue(on.printed("[Operation] 3 [pre:layer, draw:layer:99, post:layer, pre:top, before:top:3, draw:replacement:13, draw:replacement:23, after:top, post:top]"),on.describe());
        assertTrue(on.printed("[Operation] -1 [pre:layer, draw:layer:99, post:layer, pre:top, cancel:top]"),on.describe());
        assertTrue(on.printed("[Operation] direct [pre:top, draw:top:7, post:top]"),on.describe());
        assertFalse(on.output().contains("injection warning"),on.describe());
        assertFalse(on.printed("skipped at"),on.describe());assertTrue(on.findings().stream().noneMatch(f->f.id().startsWith("mixin-seam:")),on.findings().toString());
        WeaveHarness.assertWovenAndVerified(on,"unknown/Dispatch",fixture);
        Path second=work.resolve("src/second/Listener.java");Files.createDirectories(second.getParent());Files.writeString(second,"""
            package second;import unknown.*;import com.llamalad7.mixinextras.injector.wrapoperation.*;import org.spongepowered.asm.mixin.*;import org.spongepowered.asm.mixin.injection.*;
            @Mixin(value=Dispatch.class,priority=2000)public class Listener{
            @WrapOperation(method="draw",at=@At(value="INVOKE",target="Lunknown/Widget;present(Lunknown/Context;I)V"))
            private void another(Widget receiver,Context context,int value,Operation<Void> original){audit.OperationProbe.log.add("second.before:"+receiver.name+":"+value);original.call(receiver,context,value);audit.OperationProbe.log.add("second.after:"+receiver.name);}}
            """);files.add(second);Path secondConfig=work.resolve("second.mixins.json");Files.writeString(secondConfig,"{\"required\":true,\"compatibilityLevel\":\"JAVA_25\",\"package\":\"second\",\"mixins\":[\"Listener\"]}");
        Path multiple=WeaveHarness.fixture(work,"multiple",files,Map.of("operation.mixins.json",config,"second.mixins.json",secondConfig),List.of("-g"));multiple=NativeWeaveReferences.with(work,multiple,Map.of("unknown/Dispatch",source()));
        var both=WeaveHarness.run(work,"both",multiple,List.of(configs.getFirst(),new WeaveHarness.Config("second.mixins.json","second-operation",Ecosystem.FABRIC)),List.of(),EnvType.SERVER,"audit.OperationProbe","run",Map.of());
        assertTrue(both.printed("second.before:"),both.describe());assertTrue(both.printed("draw:replacement:11"),both.describe());assertFalse(both.output().contains("injection warning"),both.describe());
        Path dispatch=work.resolve("src/unknown/Dispatch.java");Files.writeString(dispatch,sources.get("unknown.Dispatch").replace("gateway(context,value)","widget.present(context,value)"));
        Path nativeFixture=WeaveHarness.fixture(work,"native-operation-order",files,Map.of("operation.mixins.json",config,"second.mixins.json",secondConfig),List.of("-g"));nativeFixture=NativeWeaveReferences.with(work,nativeFixture,Map.of("unknown/Dispatch",source()));
        var nativeRun=WeaveHarness.run(work,"native-order",nativeFixture,List.of(configs.getFirst(),new WeaveHarness.Config("second.mixins.json","second-operation",Ecosystem.FABRIC)),List.of(),EnvType.SERVER,"audit.OperationProbe","run",Map.of());
        String transported=both.output().lines().filter(line->line.startsWith("[Operation] 1 ")).findFirst().orElseThrow();String originalOrder=nativeRun.output().lines().filter(line->line.startsWith("[Operation] 1 ")).findFirst().orElseThrow();
        assertEquals(originalOrder,transported.replace("pre:layer, draw:layer:99, post:layer, pre:top, ","").replace(", post:top", ""),"source wrappers retain the actual native Mixin priority order");
        Files.writeString(dispatch,sources.get("unknown.Dispatch"));
        files.remove(second);
        var off=WeaveHarness.run(work,"off",fixture,configs,List.of(),EnvType.SERVER,"audit.OperationProbe","run",Map.of(MixinOperationSeamTransport.PROPERTY,"off"));
        assertTrue(off.printed("[Operation] 1 [pre:layer, draw:layer:99, post:layer, pre:top, draw:top:1, post:top]"),off.describe());assertFalse(off.printed("before:top"),off.describe());
        Path subscriber=work.resolve("src/unrelated/Subscriber.java");Files.writeString(subscriber,"""
            package unrelated;import unknown.*;import org.spongepowered.asm.mixin.*;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
            @Mixin(Dispatch.class)public class Subscriber{@Shadow private Widget widget;
            @Inject(id="custom",method="draw",at=@At(id="point",value="INVOKE",target="Lunknown/Widget;present(Lunknown/Context;I)V",shift=At.Shift.AFTER))
            private void originalAfter(int value,CallbackInfo ci){if(!ci.getId().equals("custom:point")||ci.isCancellable()||!widget.name.equals("top"))throw new AssertionError("callback metadata/host changed");audit.OperationProbe.log.add("injected:"+value);}}
            """);
        Path injected=WeaveHarness.fixture(work,"inject",files,Map.of("operation.mixins.json",config),List.of("-g"));injected=NativeWeaveReferences.with(work,injected,Map.of("unknown/Dispatch",source()));
        var inject=WeaveHarness.run(work,"inject",injected,configs,List.of(),EnvType.SERVER,"audit.OperationProbe","run",Map.of());
        assertTrue(inject.printed("[Operation] 1 [pre:layer, draw:layer:99, post:layer, pre:top, draw:top:1, injected:1, post:top]"),inject.describe());assertTrue(inject.printed("[Operation] -1 [pre:layer, draw:layer:99, post:layer, pre:top, cancel:top]"),inject.describe());assertFalse(inject.output().contains("injection warning"),inject.describe());
        Files.writeString(subscriber,sources.get("unrelated.Subscriber"));
        Path observer=work.resolve("src/observer/Changed.java");Files.createDirectories(observer.getParent());Files.writeString(observer,"""
            package observer;import unknown.*;import org.spongepowered.asm.mixin.*;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
            @Mixin(Gateway.class)public class Changed{@Inject(method="once",at=@At("HEAD"))private static void altered(Widget widget,Context context,int value,CallbackInfo ci){audit.OperationProbe.log.add("observer:"+widget.name);}}
            """);files.add(observer);Path changedConfig=work.resolve("changed.mixins.json");Files.writeString(changedConfig,"{\"required\":true,\"compatibilityLevel\":\"JAVA_25\",\"package\":\"observer\",\"mixins\":[\"Changed\"]}");
        Path changed=WeaveHarness.fixture(work,"changed-helper",files,Map.of("operation.mixins.json",config,"changed.mixins.json",changedConfig),List.of("-g"));changed=NativeWeaveReferences.with(work,changed,Map.of("unknown/Dispatch",source()));
        var mutated=WeaveHarness.run(work,"changed-helper",changed,List.of(configs.getFirst(),new WeaveHarness.Config("changed.mixins.json","changed-operation",Ecosystem.FABRIC)),List.of(),EnvType.SERVER,"audit.OperationProbe","run",Map.of());
        // Another mod's mixin into the carrier helper coexists natively: the host still draws, the gateway and helper
        // run as written (with that mixin), and only the transported operation is skipped -- reported once, naming it.
        assertTrue(mutated.printed("[Operation] 1 [observer:layer, pre:layer, draw:layer:99, post:layer, observer:top, pre:top, draw:top:1, post:top]"),mutated.describe());
        assertTrue(mutated.printed("[Operation] 3 [observer:layer, pre:layer, draw:layer:99, post:layer, observer:top, pre:top, draw:top:3, post:top]"),mutated.describe());
        assertTrue(mutated.printed("[Operation] -1 [observer:layer, pre:layer, draw:layer:99, post:layer, observer:top, pre:top, cancel:top]"),mutated.describe());
        assertTrue(mutated.printed("[Operation] direct [observer:top, pre:top, draw:top:7, post:top]"),mutated.describe());
        assertFalse(mutated.printed("before:top"),mutated.describe());
        List<String> warnings=mutated.output().lines().filter(line->line.contains("source operation skipped at")).toList();
        assertEquals(1,warnings.size(),mutated.describe());
        assertTrue(warnings.getFirst().contains("mixin=unrelated.Subscriber")&&warnings.getFirst().contains("unknown.Gateway.once")&&warnings.getFirst().contains("observer.Changed"),mutated.describe());
        List<WeaveHarness.Finding> findings=mutated.findings().stream().filter(f->f.id().startsWith("mixin-seam:operation.mixins.json:unrelated.Subscriber#arbitrary(")).toList();
        assertEquals(1,findings.size(),mutated.findings()+"\n"+mutated.describe());
        assertEquals("unknown-operation",findings.getFirst().modId());assertEquals("CONFIRMED",findings.getFirst().confidence());assertFalse(findings.getFirst().required());
        assertFalse(mutated.printed("final body witness"),mutated.describe());
        files.remove(observer);
        // A look-alike into another method of the same gateway/helper class leaves the proved bodies alone.
        Path neighbour=work.resolve("src/observer/Neighbour.java");Files.writeString(neighbour,"""
            package observer;import unknown.*;import org.spongepowered.asm.mixin.*;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
            @Mixin(Gateway.class)public class Neighbour{@Inject(method="direct",at=@At("HEAD"))private void beside(Context context,int value,CallbackInfo ci){audit.OperationProbe.log.add("neighbour");}}
            """);files.add(neighbour);Path neighbourConfig=work.resolve("neighbour.mixins.json");Files.writeString(neighbourConfig,"{\"required\":true,\"compatibilityLevel\":\"JAVA_25\",\"package\":\"observer\",\"mixins\":[\"Neighbour\"]}");
        Path alike=WeaveHarness.fixture(work,"look-alike",files,Map.of("operation.mixins.json",config,"neighbour.mixins.json",neighbourConfig),List.of("-g"));alike=NativeWeaveReferences.with(work,alike,Map.of("unknown/Dispatch",source()));
        var alikeRun=WeaveHarness.run(work,"look-alike",alike,List.of(configs.getFirst(),new WeaveHarness.Config("neighbour.mixins.json","neighbour-operation",Ecosystem.NEOFORGE)),List.of(),EnvType.SERVER,"audit.OperationProbe","run",Map.of());
        assertTrue(alikeRun.printed("[Operation] 1 [pre:layer, draw:layer:99, post:layer, pre:top, before:top:1, draw:replacement:11, after:top, post:top]"),alikeRun.describe());
        assertTrue(alikeRun.printed("[Operation] direct [neighbour, pre:top, draw:top:7, post:top]"),alikeRun.describe());
        assertFalse(alikeRun.printed("skipped at"),alikeRun.describe());assertTrue(alikeRun.findings().stream().noneMatch(f->f.id().startsWith("mixin-seam:")),alikeRun.findings().toString());
    }
    /**
     * A callback after the moved draw that reads the source's locals through MixinExtras' {@code @Local} receives, at
     * run time, the very canvas the carrier draws with and the host's own argument. With the locals switch off it is
     * not carried and never runs.
     */
    @Test void aCallbackCapturingTheDrawsLocalsSeesTheCanvasTheCarrierDrawsWith()throws Exception {
        Map<String,String> sources=new LinkedHashMap<>();
        sources.put("audit.PaintProbe","""
            package audit;public class PaintProbe{public static java.util.List<String>log=new java.util.ArrayList<>();public void run(){unseen.Board board=new unseen.Board();for(int value:new int[]{1,-1,2}){log.clear();board.draw(value);System.out.println("[Paint] "+value+" "+log);}}}
            """);
        sources.put("unseen.Canvas","package unseen;public class Canvas{private static int next;public final int id=++next;}");
        sources.put("unseen.Brush","package unseen;public class Brush{public final String name;public Brush(String name){this.name=name;}public void present(Canvas canvas,int value){audit.PaintProbe.log.add(\"draw:\"+name+\":\"+value+\":#\"+canvas.id);}}");
        sources.put("unseen.Relay","""
            package unseen;public abstract class Relay{private static final Brush layer=new Brush("layer");protected abstract Brush brush();protected void relay(Canvas canvas,int value){once(layer,canvas,99);once(brush(),canvas,value);}private static void once(Brush brush,Canvas canvas,int value){audit.PaintProbe.log.add("pre:"+brush.name);if(value==-1){audit.PaintProbe.log.add("cancel:"+brush.name);return;}brush.present(canvas,value);audit.PaintProbe.log.add("post:"+brush.name);}}
            """);
        sources.put("unseen.Board","package unseen;public class Board extends Relay{private final Brush brush=new Brush(\"top\");public Brush brush(){return brush;}public void draw(int value){Canvas canvas=new Canvas();relay(canvas,value);}}");
        sources.put("painters.Overpaint","""
            package painters;import unseen.*;import com.llamalad7.mixinextras.sugar.Local;import org.spongepowered.asm.mixin.*;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
            @Mixin(Board.class)public class Overpaint{
            @Inject(method="draw",at=@At(value="INVOKE",target="Lunseen/Brush;present(Lunseen/Canvas;I)V",shift=At.Shift.AFTER))
            private void overpaint(int value,CallbackInfo ci,@Local Canvas canvas,@Local(argsOnly=true) int same){audit.PaintProbe.log.add("over:#"+canvas.id+":"+value+":"+same);}}
            """);
        sources.put("painters.Underpaint","""
            package painters;import unseen.*;import com.llamalad7.mixinextras.injector.wrapoperation.*;import com.llamalad7.mixinextras.sugar.Local;import org.spongepowered.asm.mixin.*;import org.spongepowered.asm.mixin.injection.*;
            @Mixin(Board.class)public class Underpaint{
            @WrapOperation(method="draw",at=@At(value="INVOKE",target="Lunseen/Brush;present(Lunseen/Canvas;I)V"))
            private void underpaint(Brush brush,Canvas canvas,int value,Operation<Void> original,@Local Canvas local){audit.PaintProbe.log.add("under:#"+local.id+":#"+canvas.id);original.call(brush,canvas,value);}}
            """);
        List<Path> files=new ArrayList<>();for(var entry:sources.entrySet()){Path path=work.resolve("src").resolve(entry.getKey().replace('.','/')+".java");Files.createDirectories(path.getParent());Files.writeString(path,entry.getValue());files.add(path);}
        Path config=work.resolve("paint.mixins.json");Files.writeString(config,"{\"required\":true,\"compatibilityLevel\":\"JAVA_25\",\"package\":\"painters\",\"mixins\":[\"Overpaint\"],\"injectors\":{\"defaultRequire\":1}}");
        Path wrapConfig=work.resolve("underpaint.mixins.json");Files.writeString(wrapConfig,"{\"required\":true,\"compatibilityLevel\":\"JAVA_25\",\"package\":\"painters\",\"mixins\":[\"Underpaint\"],\"injectors\":{\"defaultRequire\":1}}");
        Path current=WeaveHarness.fixture(work,"paint",files,Map.of("paint.mixins.json",config,"underpaint.mixins.json",wrapConfig),List.of("-g"));Path fixture=NativeWeaveReferences.with(work,current,Map.of("unseen/Board",paintSource()));
        var wrapped=WeaveHarness.run(work,"paint-wrap",fixture,List.of(new WeaveHarness.Config("underpaint.mixins.json","unseen-underpaint",Ecosystem.FABRIC)),List.of(),EnvType.SERVER,"audit.PaintProbe","run",Map.of());
        assertTrue(wrapped.printed("[Paint] 1 [pre:layer, draw:layer:99:#1, post:layer, pre:top, under:#1:#1, draw:top:1:#1, post:top]"),wrapped.describe());
        assertFalse(wrapped.output().contains("injection warning"),wrapped.describe());
        var configs=List.of(new WeaveHarness.Config("paint.mixins.json","unseen-paint",Ecosystem.FABRIC));
        var on=WeaveHarness.run(work,"paint-on",fixture,configs,List.of(),EnvType.SERVER,"audit.PaintProbe","run",Map.of());
        assertTrue(on.printed("[Paint] 1 [pre:layer, draw:layer:99:#1, post:layer, pre:top, draw:top:1:#1, over:#1:1:1, post:top]"),on.describe());
        assertTrue(on.printed("[Paint] -1 [pre:layer, draw:layer:99:#2, post:layer, pre:top, cancel:top]"),on.describe());
        assertTrue(on.printed("[Paint] 2 [pre:layer, draw:layer:99:#3, post:layer, pre:top, draw:top:2:#3, over:#3:2:2, post:top]"),on.describe());
        assertFalse(on.output().contains("injection warning"),on.describe());
        WeaveHarness.assertWovenAndVerified(on,"unseen/Board",fixture);
        var off=WeaveHarness.run(work,"paint-off",fixture,configs,List.of(),EnvType.SERVER,"audit.PaintProbe","run",Map.of(MixinOperationSeamTransport.LOCALS_PROPERTY,"off"));
        assertTrue(off.printed("[Paint] 1 [pre:layer, draw:layer:99:#1, post:layer, pre:top, draw:top:1:#1, post:top]"),off.describe());assertFalse(off.printed("over:"),off.describe());
    }
    /** The source Board: {@code Canvas canvas=new Canvas(); brush.present(canvas,value);} with its debug scope, as javac -g writes it. */
    private static byte[] paintSource(){ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);writer.visit(V21,ACC_PUBLIC,"unseen/Board",null,"java/lang/Object",null);writer.visitField(ACC_PRIVATE|ACC_FINAL,"brush","Lunseen/Brush;",null,null).visitEnd();
        MethodVisitor method=writer.visitMethod(ACC_PUBLIC,"draw","(I)V",null,null);method.visitCode();Label start=new Label(),scope=new Label(),end=new Label();method.visitLabel(start);
        method.visitTypeInsn(NEW,"unseen/Canvas");method.visitInsn(DUP);method.visitMethodInsn(INVOKESPECIAL,"unseen/Canvas","<init>","()V",false);method.visitVarInsn(ASTORE,2);method.visitLabel(scope);
        method.visitVarInsn(ALOAD,0);method.visitFieldInsn(GETFIELD,"unseen/Board","brush","Lunseen/Brush;");method.visitVarInsn(ALOAD,2);method.visitVarInsn(ILOAD,1);method.visitMethodInsn(INVOKEVIRTUAL,"unseen/Brush","present","(Lunseen/Canvas;I)V",false);method.visitInsn(RETURN);method.visitLabel(end);
        method.visitLocalVariable("this","Lunseen/Board;",null,start,end,0);method.visitLocalVariable("value","I",null,start,end,1);method.visitLocalVariable("canvas","Lunseen/Canvas;",null,scope,end,2);
        method.visitMaxs(0,0);method.visitEnd();writer.visitEnd();return writer.toByteArray();}
    private static byte[] source(){ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);writer.visit(V21,ACC_PUBLIC,"unknown/Dispatch",null,"java/lang/Object",null);writer.visitField(ACC_PRIVATE,"widget","Lunknown/Widget;",null,null).visitEnd();MethodVisitor method=writer.visitMethod(ACC_PUBLIC,"draw","(I)V",null,null);method.visitCode();method.visitTypeInsn(NEW,"unknown/Context");method.visitInsn(DUP);method.visitMethodInsn(INVOKESPECIAL,"unknown/Context","<init>","()V",false);method.visitVarInsn(ASTORE,2);method.visitVarInsn(ALOAD,0);method.visitFieldInsn(GETFIELD,"unknown/Dispatch","widget","Lunknown/Widget;");method.visitVarInsn(ALOAD,2);method.visitVarInsn(ILOAD,1);method.visitMethodInsn(INVOKEVIRTUAL,"unknown/Widget","present","(Lunknown/Context;I)V",false);method.visitInsn(RETURN);method.visitMaxs(0,0);method.visitEnd();writer.visitEnd();return writer.toByteArray();}
}
