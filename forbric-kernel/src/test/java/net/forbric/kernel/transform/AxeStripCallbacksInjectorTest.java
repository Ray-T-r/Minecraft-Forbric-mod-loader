package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import net.forbric.kernel.TestFixtures;

@ExecutesInjector(AxeStripCallbacksInjector.class)
@ResourceLock("system-properties")
class AxeStripCallbacksInjectorTest {
    @TempDir Path root;
    @AfterEach void reset() { System.clearProperty(AxeStripCallbacksInjector.PROPERTY); }
    @Test void theHeldAxeRunsItsCallbackOnceAndCanRefuseWhileOtherToolsKeepTheNativePath() throws Exception {
        Map<String,String> sources = Map.of(
            "net/minecraft/world/level/block/state/BlockState.java", "package net.minecraft.world.level.block.state; public class BlockState {}",
            "net/minecraft/world/item/Item.java", "package net.minecraft.world.item; public class Item {}",
            "net/minecraft/world/item/ItemStack.java", "package net.minecraft.world.item; public class ItemStack { private Item item; public ItemStack(Item i){item=i;} public Item getItem(){return item;} }",
            "net/minecraft/world/item/context/UseOnContext.java", "package net.minecraft.world.item.context; public class UseOnContext { private net.minecraft.world.item.ItemStack stack; public UseOnContext(net.minecraft.world.item.ItemStack s){stack=s;} public net.minecraft.world.item.ItemStack getItemInHand(){return stack;} }",
            "net/minecraft/world/item/AxeItem.java", "package net.minecraft.world.item; import java.util.Optional; import net.minecraft.world.level.block.state.BlockState; public class AxeItem extends Item { public static int callbacks,nativeCalls; public static boolean refuse; private Optional<BlockState> getStripped(BlockState s){callbacks++;return refuse?Optional.empty():Optional.of(s);} public static BlockState getAxeStrippingState(BlockState s){nativeCalls++;return s;} }");
        List<String> args = new ArrayList<>(List.of("-d", root.toString()));
        for (var entry : sources.entrySet()) { Path path=root.resolve(entry.getKey()); Files.createDirectories(path.getParent()); Files.writeString(path,entry.getValue()); args.add(path.toString()); }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
        byte[] original=Files.readAllBytes(root.resolve(AxeStripCallbacksInjector.AXE+".class"));
        byte[] repaired=new AxeStripCallbacksInjector().transform(AxeStripCallbacksInjector.AXE.replace('/','.'),original,null);
        try (var loader = new URLClassLoader(new URL[]{root.toUri().toURL()},null) {
            @Override protected Class<?> findClass(String name)throws ClassNotFoundException {
                return name.equals(AxeStripCallbacksInjector.AXE.replace('/','.'))?defineClass(name,repaired,0,repaired.length):super.findClass(name);
            }
        }) {
            Class<?> axe=loader.loadClass("net.minecraft.world.item.AxeItem"), item=loader.loadClass("net.minecraft.world.item.Item"), stack=loader.loadClass("net.minecraft.world.item.ItemStack"), context=loader.loadClass("net.minecraft.world.item.context.UseOnContext"), state=loader.loadClass("net.minecraft.world.level.block.state.BlockState");
            Object block=state.getConstructor().newInstance(), held=axe.getConstructor().newInstance();
            Object ctx=context.getConstructor(stack).newInstance(stack.getConstructor(item).newInstance(held));
            var call=axe.getMethod(AxeStripCallbacksInjector.HELPER,state,context);
            assertSame(block,call.invoke(null,block,ctx)); assertEquals(1,axe.getField("callbacks").getInt(null)); assertEquals(0,axe.getField("nativeCalls").getInt(null));
            axe.getField("refuse").setBoolean(null,true); assertNull(call.invoke(null,block,ctx)); assertEquals(2,axe.getField("callbacks").getInt(null));
            Object other=context.getConstructor(stack).newInstance(stack.getConstructor(item).newInstance(item.getConstructor().newInstance()));
            assertSame(block,call.invoke(null,block,other)); assertEquals(2,axe.getField("callbacks").getInt(null)); assertEquals(1,axe.getField("nativeCalls").getInt(null));
        }
    }
    @Test void bothNativeCarriersRouteOnlyTheStrippingDecision()throws Exception {
        var repair=new AxeStripCallbacksInjector();
        Path staged=Path.of(System.getProperty("forbric.stagedRoot","../forbric-loader/run"));
        // Sorted: the first carrier read decides a skip's reason, and the skip baselines compare reasons exactly.
        for(var entry:new java.util.TreeMap<>(Map.of("net.neoforged.neoforge.common.extensions.IBlockExtension",staged.resolve(TestFixtures.NEO_CARRIER_JAR),"net.minecraftforge.common.extensions.IForgeBlock",staged.resolve(TestFixtures.FORGE_INTEROP_JAR))).entrySet()) {
            byte[] original=NativeCoremodParityTest.read(entry.getValue(),entry.getKey().replace('.','/')); byte[] bytes=repair.transform(entry.getKey(),original,null);
            assertNotSame(original,bytes); ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);
            MethodNode tool=node.methods.stream().filter(m->m.name.equals("getToolModifiedState")).findFirst().orElseThrow();new Analyzer<>(new BasicVerifier()).analyze(node.name,tool);
            assertEquals(1,Arrays.stream(tool.instructions.toArray()).filter(i->i instanceof MethodInsnNode c&&c.name.equals(AxeStripCallbacksInjector.HELPER)).count());
            assertSame(bytes,repair.transform(entry.getKey(),bytes,null));System.setProperty(AxeStripCallbacksInjector.PROPERTY,"off");assertSame(original,repair.transform(entry.getKey(),original,null));System.clearProperty(AxeStripCallbacksInjector.PROPERTY);
        }
    }
}
