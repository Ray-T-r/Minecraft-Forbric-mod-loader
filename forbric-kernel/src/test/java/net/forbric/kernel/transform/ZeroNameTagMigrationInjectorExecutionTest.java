package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;
import net.fabricmc.api.EnvType;

@ExecutesInjector(ZeroNameTagMigrationInjector.class)
class ZeroNameTagMigrationInjectorExecutionTest {
    private static final String OWNER = "unknown/content/InvisibleEntity";
    private static ClassNode parse(byte[] bytes) { var node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
    private static Map<String, String> sources(String body) {
        return Map.of(
            "net.minecraft.core.Holder", "package net.minecraft.core; public interface Holder<T>{}",
            ZeroNameTagMigrationInjector.NATIVE.replace('/', '.'), "package net.neoforged.neoforge.common; public class NeoForgeMod { public static net.minecraft.core.Holder<?> NAMETAG_DISTANCE; }",
            ZeroNameTagMigrationInjector.ATTRIBUTES.replace('/', '.'), "package net.minecraft.world.entity.ai.attributes; public class Attributes { public static final net.minecraft.core.Holder<?> NAME_TAG_DISTANCE=new net.minecraft.core.Holder<>(){}; }",
            "net.minecraft.world.entity.ai.attributes.AttributeInstance", "package net.minecraft.world.entity.ai.attributes; public class AttributeInstance { public double value=64; public void setBaseValue(double v){value=v;} }",
            "net.minecraft.world.entity.ai.attributes.AttributeMap", "package net.minecraft.world.entity.ai.attributes; public class AttributeMap { public final AttributeInstance instance=new AttributeInstance(); public AttributeInstance getInstance(net.minecraft.core.Holder<?> key){ if(key!=Attributes.NAME_TAG_DISTANCE)throw new AssertionError();return instance; } }",
            OWNER.replace('/', '.'), "package unknown.content; public class InvisibleEntity { public static void suppress(net.minecraft.world.entity.ai.attributes.AttributeMap map) { " + body + " } }");
    }
    @Test void unknownEntitysZeroSetterExecutesAgainstTheReplacementAttribute(@TempDir Path work) throws Throwable {
        Map<String, byte[]> classes = new HashMap<>(InjectorExecution.compile(work, sources("var instance=map.getInstance(net.neoforged.neoforge.common.NeoForgeMod.NAMETAG_DISTANCE);if(instance!=null)instance.setBaseValue(0);")));
        ClassNode nativeOwner = parse(classes.get(ZeroNameTagMigrationInjector.NATIVE)); nativeOwner.fields.clear();
        var writer = new ClassWriter(0); nativeOwner.accept(writer); classes.put(ZeroNameTagMigrationInjector.NATIVE, writer.toByteArray());
        var injector = new ZeroNameTagMigrationInjector(name -> classes.containsKey(name) ? parse(classes.get(name)) : null);
        byte[] original = classes.get(OWNER); byte[] repaired = InjectorExecution.transform(injector, OWNER.replace('/', '.'), original, EnvType.CLIENT);
        assertNotSame(original, repaired); classes.put(OWNER, repaired);
        ClassLoader loader = InjectorExecution.load(classes); assertEquals("", InjectorExecution.verify(repaired, loader));
        Object map = loader.loadClass("net.minecraft.world.entity.ai.attributes.AttributeMap").getConstructor().newInstance();
        InjectorExecution.invokeStatic(loader.loadClass(OWNER.replace('/', '.')), "suppress", map);
        Object instance = map.getClass().getField("instance").get(map); assertEquals(0d, instance.getClass().getField("value").getDouble(instance));
    }
    @Test void aNonzeroDistanceIsNotSemanticallyEquivalent(@TempDir Path work) throws Exception {
        Map<String, byte[]> classes = InjectorExecution.compile(work, sources("var instance=map.getInstance(net.neoforged.neoforge.common.NeoForgeMod.NAMETAG_DISTANCE);instance.setBaseValue(1);"));
        ClassNode nativeOwner = parse(classes.get(ZeroNameTagMigrationInjector.NATIVE)); nativeOwner.fields.clear();
        var injector = new ZeroNameTagMigrationInjector(name -> name.equals(ZeroNameTagMigrationInjector.NATIVE) ? nativeOwner : classes.containsKey(name) ? parse(classes.get(name)) : null);
        byte[] original = classes.get(OWNER); assertSame(original, injector.transform(OWNER, original, null));
    }
    @Test void everyReadNeedsItsOwnProvedSuppressionUse(@TempDir Path work) throws Exception {
        Map<String, byte[]> classes = InjectorExecution.compile(work, sources("var first=map.getInstance(net.neoforged.neoforge.common.NeoForgeMod.NAMETAG_DISTANCE);first.setBaseValue(0);first.setBaseValue(0);var unused=map.getInstance(net.neoforged.neoforge.common.NeoForgeMod.NAMETAG_DISTANCE);"));
        ClassNode nativeOwner = parse(classes.get(ZeroNameTagMigrationInjector.NATIVE)); nativeOwner.fields.clear();
        var injector = new ZeroNameTagMigrationInjector(name -> name.equals(ZeroNameTagMigrationInjector.NATIVE) ? nativeOwner : classes.containsKey(name) ? parse(classes.get(name)) : null);
        byte[] original = classes.get(OWNER); assertSame(original, injector.transform(OWNER, original, null));
    }
}
