package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.fabricmc.api.EnvType;

@ExecutesInjector(ConfigApiAbiInjector.class)
class ConfigApiAbiInjectorExecutionTest {
    private static final String TRACKER="net/neoforged/fml/config/ConfigTracker";
    private static final String BY_ID="(Lnet/neoforged/fml/config/ModConfig$Type;Lnet/neoforged/fml/config/IConfigSpec;Ljava/lang/String;)Lnet/neoforged/fml/config/ModConfig;";
    private static final String OWNER="unknown/config/Consumer";
    private static Map<String,String> sources() {
        return Map.of(
            "net.neoforged.fml.config.ModConfig", "package net.neoforged.fml.config; public class ModConfig { public enum Type {COMMON} public final String id;public ModConfig(String id){this.id=id;} }",
            "net.neoforged.fml.config.IConfigSpec", "package net.neoforged.fml.config; public interface IConfigSpec {}",
            TRACKER.replace('/', '.'), "package net.neoforged.fml.config; public class ConfigTracker { public static final ConfigTracker INSTANCE=new ConfigTracker(); public ModConfig registerConfig(ModConfig.Type type,IConfigSpec spec,String id){throw new AssertionError(\"missing native signature\");} }",
            "net.forbric.kernel.runtime.KernelConfigApiBridge", "package net.forbric.kernel.runtime; public class KernelConfigApiBridge { public static net.neoforged.fml.config.ModConfig registerConfig(net.neoforged.fml.config.ConfigTracker tracker,net.neoforged.fml.config.ModConfig.Type type,net.neoforged.fml.config.IConfigSpec spec,String id){if(tracker!=net.neoforged.fml.config.ConfigTracker.INSTANCE)throw new AssertionError();return new net.neoforged.fml.config.ModConfig(id);} }",
            OWNER.replace('/', '.'), "package unknown.config; public class Consumer { public static Object register(){return net.neoforged.fml.config.ConfigTracker.INSTANCE.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON,null,\"unfamiliar_mod\");} }");
    }
    private static ClassNode nativeApi(boolean legacyPresent) {
        ClassNode api=new ClassNode(); api.name=TRACKER;
        api.methods.add(new MethodNode(Opcodes.ACC_PUBLIC,"registerConfig",BY_ID.replace("Ljava/lang/String;","Lnet/neoforged/fml/ModContainer;"),null,null));
        if(legacyPresent)api.methods.add(new MethodNode(Opcodes.ACC_PUBLIC,"registerConfig",BY_ID,null,null));return api;
    }
    @Test void unknownConsumersUseTheirApiDescriptorsWithoutAClassOrSiteCountGate(@TempDir Path work) throws Throwable {
        Map<String,byte[]> classes=new HashMap<>(InjectorExecution.compile(work,sources()));
        var injector=new ConfigApiAbiInjector(name->name.equals(TRACKER)?nativeApi(false):null);
        byte[] original=classes.get(OWNER),output=InjectorExecution.transform(injector,OWNER.replace('/','.'),original,EnvType.CLIENT);
        assertNotSame(original,output);classes.put(OWNER,output);assertSame(output,injector.transform(OWNER,output,null));
        ClassLoader loader=InjectorExecution.load(classes);assertEquals("",InjectorExecution.verify(output,loader));
        Object config=InjectorExecution.invokeStatic(loader.loadClass(OWNER.replace('/','.')),"register");
        assertEquals("unfamiliar_mod",config.getClass().getField("id").get(config));
    }
    @Test void anExistingSignatureAndUnknownCarrierShapeArePreserved(@TempDir Path work) throws Exception {
        byte[] bytes=InjectorExecution.compile(work,sources()).get(OWNER);
        assertSame(bytes,new ConfigApiAbiInjector(name->name.equals(TRACKER)?nativeApi(true):null).transform(OWNER,bytes,null));
        assertSame(bytes,new ConfigApiAbiInjector(name->null).transform(OWNER,bytes,null));
    }
    @Test void anArbitrarySpecImplementationDoesNotGainAnInventedValidator() {
        ClassWriter writer=new ClassWriter(0);writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,"unknown/config/Spec",null,"java/lang/Object",new String[]{"net/neoforged/fml/config/IConfigSpec"});writer.visitEnd();
        byte[] bytes=writer.toByteArray();ClassNode api=new ClassNode();api.methods.add(new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_ABSTRACT,"validateSpec","(Lnet/neoforged/fml/config/ModConfig;)V",null,null));
        assertSame(bytes,new ConfigApiAbiInjector(name->name.equals("net/neoforged/fml/config/IConfigSpec")?api:null).transform("unknown.config.Spec",bytes,null));
    }
    @Test void unknownConsumersDirectAndLambdaScreenConstructionBothExecute(@TempDir Path work) throws Throwable {
        String screen="net/neoforged/neoforge/client/gui/ConfigurationScreen", base="Lnet/minecraft/client/gui/screens/Screen;";
        Map<String,String> sources=Map.of(
            "net.minecraft.client.gui.screens.Screen","package net.minecraft.client.gui.screens;public class Screen {}",
            screen.replace('/','.'),"package net.neoforged.neoforge.client.gui;public class ConfigurationScreen extends net.minecraft.client.gui.screens.Screen {public final String id;public final net.minecraft.client.gui.screens.Screen parent;public ConfigurationScreen(String id,net.minecraft.client.gui.screens.Screen parent){this.id=id;this.parent=parent;}}",
            "net.forbric.kernel.runtime.KernelConfigApiBridge","package net.forbric.kernel.runtime;public class KernelConfigApiBridge {public static net.minecraft.client.gui.screens.Screen configurationScreen(String id,net.minecraft.client.gui.screens.Screen parent){return new net.neoforged.neoforge.client.gui.ConfigurationScreen(id,parent);}}",
            "unknown.config.ScreenConsumer","package unknown.config;public class ScreenConsumer {public static Object direct(String id,net.minecraft.client.gui.screens.Screen parent){return new net.neoforged.neoforge.client.gui.ConfigurationScreen(id,parent);}public static java.util.function.BiFunction<String,net.minecraft.client.gui.screens.Screen,net.minecraft.client.gui.screens.Screen> factory(){return net.neoforged.neoforge.client.gui.ConfigurationScreen::new;}}"
        );
        Map<String,byte[]> classes=new HashMap<>(InjectorExecution.compile(work,sources));
        ClassNode nativeScreen=new ClassNode();nativeScreen.methods.add(new MethodNode(Opcodes.ACC_PUBLIC,"<init>","(Lnet/neoforged/fml/ModContainer;"+base+")V",null,null));
        var injector=new ConfigApiAbiInjector(name->name.equals(screen)?nativeScreen:null);
        String consumer="unknown/config/ScreenConsumer";byte[] original=classes.get(consumer),output=InjectorExecution.transform(injector,consumer.replace('/','.'),original,EnvType.CLIENT);
        assertNotSame(original,output);classes.put(consumer,output);ClassLoader loader=InjectorExecution.load(classes);assertEquals("",InjectorExecution.verify(output,loader));
        Class<?> caller=loader.loadClass(consumer.replace('/','.'));Object parent=loader.loadClass("net.minecraft.client.gui.screens.Screen").getConstructor().newInstance();
        Object direct=InjectorExecution.invokeStatic(caller,"direct","direct_mod",parent);assertEquals("direct_mod",direct.getClass().getField("id").get(direct));assertSame(parent,direct.getClass().getField("parent").get(direct));
        @SuppressWarnings("unchecked") var factory=(java.util.function.BiFunction<String,Object,Object>)InjectorExecution.invokeStatic(caller,"factory");
        Object indirect=factory.apply("lambda_mod",parent);assertEquals("lambda_mod",indirect.getClass().getField("id").get(indirect));assertSame(parent,indirect.getClass().getField("parent").get(indirect));
    }
}
