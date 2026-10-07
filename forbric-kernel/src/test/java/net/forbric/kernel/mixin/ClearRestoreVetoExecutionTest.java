package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Executes the generated bridge with an original predicate: native veto wins and the original entry is preserved. */
class ClearRestoreVetoExecutionTest {
    private static final String LIVING="net/minecraft/world/entity/LivingEntity", EFFECT="net/minecraft/world/effect/MobEffectInstance",
            HOLDER="net/minecraft/core/Holder", OP="com/llamalad7/mixinextras/injector/wrapoperation/Operation";
    @Test void nativeAndGuestVetoesKeepTheirOrderAndOriginalArguments() throws Exception {
        Path jar=Path.of("build/compat-inputs/sweep90/mods/balm-fabric-26.2-26.2.0.9.jar");
        TestFixtures.require(Fixture.THIRD_PARTY,Files.isRegularFile(jar),"Balm fixture absent");
        ClassNode mixin;
        try(ZipFile z=new ZipFile(jar.toFile())) { mixin=MixinFit.parse(z.getInputStream(z.getEntry("net/blay09/mods/balm/fabric/internal/mixin/LivingEntityMixin.class")).readAllBytes()); }
        mixin.methods.removeIf(m -> !List.of("<init>","clearAllEffects","lambda$clearAllEffects$0","lambda$clearAllEffects$1").contains(m.name));
        mixin.access=Opcodes.ACC_PUBLIC; mixin.visibleAnnotations=null; mixin.invisibleAnnotations=null;
        mixin.visibleAnnotations=List.of(annotation("Lorg/spongepowered/asm/mixin/Mixin;","value",List.of(Type.getObjectType(LIVING))));
        mixin.fields.add(new FieldNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"reject","Z",null,null));
        mixin.fields.add(new FieldNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"questions","I",null,null));
        mixin.fields.add(new FieldNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"seenEntry","Ljava/util/Map$Entry;",null,null));
        mixin.fields.add(new FieldNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"seenEntity","Ljava/lang/Object;",null,null));
        MethodNode predicate=StagedFabricMixinFixture.method(mixin,"lambda$clearAllEffects$0");
        predicate.instructions.clear(); predicate.localVariables=null; predicate.tryCatchBlocks.clear();
        InsnList c=predicate.instructions;
        c.add(new FieldInsnNode(Opcodes.GETSTATIC,mixin.name,"questions","I")); c.add(new InsnNode(Opcodes.ICONST_1)); c.add(new InsnNode(Opcodes.IADD));
        c.add(new FieldInsnNode(Opcodes.PUTSTATIC,mixin.name,"questions","I"));
        c.add(new VarInsnNode(Opcodes.ALOAD,0)); c.add(new FieldInsnNode(Opcodes.PUTSTATIC,mixin.name,"seenEntity","Ljava/lang/Object;"));
        c.add(new VarInsnNode(Opcodes.ALOAD,1)); c.add(new FieldInsnNode(Opcodes.PUTSTATIC,mixin.name,"seenEntry","Ljava/util/Map$Entry;"));
        c.add(new FieldInsnNode(Opcodes.GETSTATIC,mixin.name,"reject","Z")); c.add(new InsnNode(Opcodes.IRETURN)); predicate.maxStack=2;
        ClassNode living=StagedFabricMixinFixture.living(false);
        assertEquals(1,FabricEntityMixinAnchors.adapt(mixin,n -> living));
        Loader loader=new Loader();
        Class<?> holderClass=loader.define(plain(HOLDER)), entityClass=loader.define(plain(LIVING));
        Class<?> effectClass=loader.define(effect()); Class<?> operationClass=loader.define(operation()); Class<?> provider=loader.define(mixin);
        Object holder=holderClass.getConstructor().newInstance(), entity=entityClass.getConstructor().newInstance();
        Object effect=effectClass.getConstructor(holderClass).newInstance(holder), instance=provider.getConstructor().newInstance();
        boolean[] nativeVeto={false}; int[] nativeCalls={0};
        Object operation=Proxy.newProxyInstance(loader,new Class[]{operationClass},(proxy,method,args) -> {
            assertEquals("call",method.getName()); Object[] arguments=(Object[])args[0];
            assertSame(entity,arguments[0]); assertSame(effect,arguments[1]); nativeCalls[0]++; return nativeVeto[0];
        });
        Method bridge=provider.getDeclaredMethod("forbric$clearVeto$clearAllEffects",entityClass,effectClass,operationClass); bridge.setAccessible(true);
        assertEquals(false,bridge.invoke(instance,entity,effect,operation));
        assertEquals(1,provider.getField("questions").getInt(null));
        Map.Entry<?,?> entry=(Map.Entry<?,?>)provider.getField("seenEntry").get(null);
        assertSame(holder,entry.getKey()); assertSame(effect,entry.getValue()); assertSame(entity,provider.getField("seenEntity").get(null));
        provider.getField("reject").setBoolean(null,true);
        assertEquals(true,bridge.invoke(instance,entity,effect,operation)); assertEquals(2,provider.getField("questions").getInt(null));
        nativeVeto[0]=true;
        assertEquals(true,bridge.invoke(instance,entity,effect,operation)); assertEquals(2,provider.getField("questions").getInt(null),"native veto prevents a second guest question");
        assertEquals(3,nativeCalls[0]);
    }
    private static ClassNode plain(String name) {
        ClassNode n=new ClassNode(); n.version=Opcodes.V21; n.access=Opcodes.ACC_PUBLIC; n.name=name; n.superName="java/lang/Object";
        MethodNode init=new MethodNode(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);
        init.instructions.add(new VarInsnNode(Opcodes.ALOAD,0)); init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false)); init.instructions.add(new InsnNode(Opcodes.RETURN)); n.methods.add(init); return n;
    }
    private static ClassNode effect() {
        ClassNode n=plain(EFFECT); n.methods.clear(); n.fields.add(new FieldNode(Opcodes.ACC_PRIVATE,"holder","L"+HOLDER+";",null,null));
        MethodNode init=new MethodNode(Opcodes.ACC_PUBLIC,"<init>","(L"+HOLDER+";)V",null,null);
        init.instructions.add(new VarInsnNode(Opcodes.ALOAD,0)); init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false));
        init.instructions.add(new VarInsnNode(Opcodes.ALOAD,0)); init.instructions.add(new VarInsnNode(Opcodes.ALOAD,1)); init.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,EFFECT,"holder","L"+HOLDER+";")); init.instructions.add(new InsnNode(Opcodes.RETURN)); n.methods.add(init);
        MethodNode get=new MethodNode(Opcodes.ACC_PUBLIC,"getEffect","()L"+HOLDER+";",null,null);
        get.instructions.add(new VarInsnNode(Opcodes.ALOAD,0)); get.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,EFFECT,"holder","L"+HOLDER+";")); get.instructions.add(new InsnNode(Opcodes.ARETURN)); n.methods.add(get); return n;
    }
    private static ClassNode operation() { ClassNode n=plain(OP); n.access=Opcodes.ACC_PUBLIC|Opcodes.ACC_INTERFACE|Opcodes.ACC_ABSTRACT; n.methods.clear(); n.methods.add(new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_ABSTRACT,"call","([Ljava/lang/Object;)Ljava/lang/Object;",null,null)); return n; }
    private static AnnotationNode annotation(String desc,Object... values){AnnotationNode a=new AnnotationNode(desc);a.values=new ArrayList<>(List.of(values));return a;}
    private static final class Loader extends ClassLoader {
        Loader(){super(ClearRestoreVetoExecutionTest.class.getClassLoader());}
        Class<?> define(ClassNode node){ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);byte[] bytes=writer.toByteArray();return defineClass(node.name.replace('/','.'),bytes,0,bytes.length);}
    }
}
