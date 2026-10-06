package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import net.fabricmc.api.EnvType;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Executes the actual CommonHooks default decision, native event and air update prefix, without unrelated world damage/vehicle setup. */
@ExecutesInjector(FabricFluidBreathingInjector.class)
@ResourceLock("system-properties")
class FabricFluidBreathingInjectorExecutionTest {
	private static final String HOOK="net/neoforged/neoforge/common/CommonHooks",LIVING="net.minecraft.world.entity.LivingEntity";
	private static final Map<String,String>SOURCES=Map.ofEntries(
		Map.entry("net.minecraft.world.entity.Entity","package net.minecraft.world.entity; public class Entity {}"),
		Map.entry(LIVING,"""
		 package net.minecraft.world.entity;
		 import net.minecraft.world.level.Level;import net.neoforged.neoforge.fluids.FluidType;
		 public class LivingEntity extends Entity {
		  public int air=100,max=300,queries;public boolean waterBreathing;public final EntityFluidInteraction interaction=new EntityFluidInteraction();
		  public EntityFluidInteraction getFluidInteraction(){return interaction;}public boolean canDrownInFluidType(FluidType type){queries++;return type.canDrownIn(this);}
		  public Level level(){return new Level();}public double getX(){return 0;}public double getEyeY(){return 0;}public double getZ(){return 0;}
		  public int getAirSupply(){return air;}public int getMaxAirSupply(){return max;}public void setAirSupply(int air){this.air=air;}
		 }
		 """),
		Map.entry("net.minecraft.world.entity.EntityFluidInteraction","""
		 package net.minecraft.world.entity;import net.neoforged.neoforge.fluids.*;
		 public class EntityFluidInteraction {public FluidType type;public int queries;public <E extends Entity>boolean isEyeInFluidMatching(E entity,InFluidPredicate<E>predicate){queries++;return predicate.test(entity,type,1);}}
		 """),
		Map.entry("net.neoforged.neoforge.fluids.InFluidPredicate","package net.neoforged.neoforge.fluids;import net.minecraft.world.entity.Entity;public interface InFluidPredicate<E extends Entity>{boolean test(E entity,FluidType type,double height);}"),
		Map.entry("net.neoforged.neoforge.fluids.FluidType","""
		 package net.neoforged.neoforge.fluids;import net.minecraft.world.entity.LivingEntity;
		 public class FluidType {private final boolean drown;public FluidType(boolean drown){this.drown=drown;}public boolean canDrownIn(LivingEntity entity){return drown;}public final boolean isAir(){return false;}}
		 """),
		Map.entry("net.forbric.kernel.runtime.KernelFabricFluidBehaviors","""
		 package net.forbric.kernel.runtime;import net.neoforged.neoforge.fluids.FluidType;
		 public class KernelFabricFluidBehaviors {
		  public static final class Owned extends FluidType{public Owned(boolean drown){super(drown);}}
		  public static boolean enabled(){return !"off".equals(System.getProperty("forbric.fabricFluidBehavior","on"));}
		  public static boolean ownsNeoType(FluidType type){return type instanceof Owned;}
		 }
		 """),
		Map.entry("fixture.NativeOverride","package fixture;import net.neoforged.neoforge.fluids.FluidType;import net.minecraft.world.entity.LivingEntity;public class NativeOverride extends FluidType{public NativeOverride(){super(true);}public boolean canDrownIn(LivingEntity entity){return false;}}"),
		Map.entry("net.minecraft.world.level.Level","package net.minecraft.world.level;import net.minecraft.core.BlockPos;import net.minecraft.world.level.block.state.BlockState;public class Level{public BlockState getBlockState(BlockPos pos){return new BlockState();}}"),
		Map.entry("net.minecraft.server.level.ServerLevel","package net.minecraft.server.level;public class ServerLevel extends net.minecraft.world.level.Level{}"),
		Map.entry("net.minecraft.core.BlockPos","package net.minecraft.core;public class BlockPos{public static BlockPos containing(double x,double y,double z){return new BlockPos();}}"),
		Map.entry("net.minecraft.world.level.block.Block","package net.minecraft.world.level.block;public class Block{}"),
		Map.entry("net.minecraft.world.level.block.Blocks","package net.minecraft.world.level.block;public class Blocks{public static final Block BUBBLE_COLUMN=new Block();}"),
		Map.entry("net.minecraft.world.level.block.state.BlockState","package net.minecraft.world.level.block.state;public class BlockState{public boolean is(Object block){return false;}}"),
		Map.entry("net.minecraft.world.effect.MobEffectUtil","package net.minecraft.world.effect;import net.minecraft.world.entity.LivingEntity;public class MobEffectUtil{public static boolean hasWaterBreathing(LivingEntity entity){return entity.waterBreathing;}}"),
		Map.entry("net.minecraft.world.entity.player.Abilities","package net.minecraft.world.entity.player;public class Abilities{public boolean invulnerable;}"),
		Map.entry("net.minecraft.world.entity.player.Player","package net.minecraft.world.entity.player;public class Player extends net.minecraft.world.entity.LivingEntity{public Abilities getAbilities(){return new Abilities();}}"),
		Map.entry("net.neoforged.bus.api.Event","package net.neoforged.bus.api;public class Event{}"),
		Map.entry("net.neoforged.bus.api.IEventBus","package net.neoforged.bus.api;public interface IEventBus{Event post(Event event);}"),
		Map.entry("net.neoforged.neoforge.event.entity.living.LivingBreatheEvent","""
		 package net.neoforged.neoforge.event.entity.living;import net.neoforged.bus.api.Event;import net.minecraft.world.entity.LivingEntity;
		 public class LivingBreatheEvent extends Event {public boolean can;public int consume,refill;public LivingBreatheEvent(LivingEntity entity,boolean can,int consume,int refill){this.can=can;this.consume=consume;this.refill=refill;}public boolean canBreathe(){return can;}public int getRefillAirAmount(){return refill;}public int getConsumeAirAmount(){return consume;}}
		 """),
		Map.entry("net.neoforged.neoforge.common.NeoForge","""
		 package net.neoforged.neoforge.common;import net.neoforged.bus.api.*;import net.neoforged.neoforge.event.entity.living.LivingBreatheEvent;
		 public class NeoForge {public static int can=-1,refill=-1,posts;public static final IEventBus EVENT_BUS=event->{posts++;LivingBreatheEvent e=(LivingBreatheEvent)event;if(can>=0)e.can=can==1;if(refill>=0)e.refill=refill;return event;};}
		 """));
	@AfterEach void reset(){System.clearProperty(FabricFluidBreathingInjector.PROPERTY);System.clearProperty(FabricFluidBehaviorInjector.PROPERTY);}

	@Test void theRealDefaultPrefixRefillsAdaptersAndKeepsNativeDefaultsAndOverrides(@TempDir Path work)throws Throwable{
		ClassLoader loader=loader(work,false);assertCase(loader,"net.forbric.kernel.runtime.KernelFabricFluidBehaviors$Owned",false,false,104,1);
		assertCase(loader,"net.forbric.kernel.runtime.KernelFabricFluidBehaviors$Owned",true,false,99,1);
		assertCase(loader,"net.neoforged.neoforge.fluids.FluidType",false,false,100,1);
		assertCase(loader,"fixture.NativeOverride",null,false,100,1);
		assertCase(loader,"net.forbric.kernel.runtime.KernelFabricFluidBehaviors$Owned",false,true,104,1);
		assertCase(loader,"fixture.NativeOverride",null,true,100,0);
	}
	@Test void theNativeBreatheEventStillDecidesAirRateAndCanBreathe(@TempDir Path work)throws Throwable{
		ClassLoader loader=loader(work,false);Class<?>bus=loader.loadClass("net.neoforged.neoforge.common.NeoForge");
		bus.getField("can").setInt(null,0);assertCase(loader,"net.forbric.kernel.runtime.KernelFabricFluidBehaviors$Owned",false,false,99,1);
		bus.getField("can").setInt(null,-1);bus.getField("refill").setInt(null,2);assertCase(loader,"net.forbric.kernel.runtime.KernelFabricFluidBehaviors$Owned",false,false,102,1);
		bus.getField("refill").setInt(null,0);assertCase(loader,"net.forbric.kernel.runtime.KernelFabricFluidBehaviors$Owned",false,false,100,1);
	}
	@Test void disabledControlHoldsAdapterAirAsTheNativeHookDid(@TempDir Path work)throws Throwable{
		ClassLoader loader=loader(work,true);assertCase(loader,"net.forbric.kernel.runtime.KernelFabricFluidBehaviors$Owned",false,false,100,1);
	}
	private static void assertCase(ClassLoader loader,String type,Boolean drown,boolean potion,int expected,int queries)throws Throwable{
		Object fluid=drown==null?InjectorExecution.construct(loader.loadClass(type)):InjectorExecution.construct(loader.loadClass(type),drown);
		Object entity=InjectorExecution.construct(loader.loadClass(LIVING));entity.getClass().getField("waterBreathing").setBoolean(entity,potion);
		Object interaction=InjectorExecution.invoke(entity,"getFluidInteraction");interaction.getClass().getField("type").set(interaction,fluid);
		int before=loader.loadClass("net.neoforged.neoforge.common.NeoForge").getField("posts").getInt(null);
		InjectorExecution.invokeStatic(loader.loadClass(HOOK.replace('/','.')),"onLivingBreathe",entity,null,1,4);
		assertEquals(expected,InjectorExecution.invoke(entity,"getAirSupply"),type+" drown="+drown+" waterBreathing="+potion);
		assertEquals(queries,entity.getClass().getField("queries").getInt(entity),"reuse the native drowning decision instead of asking a behavior twice");
		assertEquals(2,interaction.getClass().getField("queries").getInt(interaction),"native isAir + drowning query, or one adapter-only lazy query after water-breathing skips drowning");
		assertEquals(before+1,loader.loadClass("net.neoforged.neoforge.common.NeoForge").getField("posts").getInt(null),"one native event");
	}
	private static ClassLoader loader(Path work,boolean disabled)throws Exception{
		Map<String,byte[]> classes=new HashMap<>(InjectorExecution.compile(work,SOURCES));
		Path jar=TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar");TestFixtures.require(Fixture.STAGED,Files.isRegularFile(jar),"actual NeoForge CommonHooks required");byte[]original;try(ZipFile z=new ZipFile(jar.toFile())){original=z.getInputStream(z.getEntry(HOOK+".class")).readAllBytes();}
		if(disabled)System.setProperty(FabricFluidBreathingInjector.PROPERTY,"off");byte[]transformed=InjectorExecution.transform(new FabricFluidBreathingInjector(),HOOK.replace('/','.'),original,EnvType.SERVER);System.clearProperty(FabricFluidBreathingInjector.PROPERTY);
		if(disabled)assertSame(original,transformed);else assertNotSame(original,transformed);
		classes.put(HOOK,prefix(transformed));
		Path runtime=Path.of(System.getProperty("forbric.fluidBreathingRuntime",Path.of(System.getProperty("user.dir"),"build/classes/java/runtime").toString()));
		for(String suffix:List.of("","$Observation")) {Path file=runtime.resolve("net/forbric/kernel/runtime/KernelFluidBreathing"+suffix+".class");TestFixtures.require(Fixture.GAME_SIDE,Files.isRegularFile(file),"compiled actual breathing bridge required");classes.put("net/forbric/kernel/runtime/KernelFluidBreathing"+suffix,Files.readAllBytes(file));}
		ClassLoader loader=InjectorExecution.load(classes);assertEquals("",InjectorExecution.verify(classes.get(HOOK),loader));return loader;
	}
	/** The executable prefix is copied unchanged through its joined post-update label; only unrelated tail code is excluded. */
	private static byte[]prefix(byte[]bytes){
		ClassNode source=new ClassNode();new ClassReader(bytes).accept(source,ClassReader.EXPAND_FRAMES);ClassNode out=new ClassNode();out.version=source.version;out.access=Opcodes.ACC_PUBLIC;out.name=source.name;out.superName="java/lang/Object";
		MethodNode hook=source.methods.stream().filter(m->m.name.equals("onLivingBreathe")).findFirst().orElseThrow();List<MethodInsnNode>air=new java.util.ArrayList<>();for(var i:hook.instructions)if(i instanceof MethodInsnNode call&&call.name.equals("getAirSupply"))air.add(call);assertTrue(air.size()>=3,"actual native refill/consume/join shape");
		AbstractInsnNode cut=air.get(2).getPrevious();while(cut.getOpcode()<0)cut=cut.getPrevious();assertEquals(Opcodes.ALOAD,cut.getOpcode());
		for(var i=cut;i!=null;){var next=i.getNext();hook.instructions.remove(i);i=next;}hook.instructions.add(new InsnNode(Opcodes.RETURN));hook.localVariables=null;hook.visibleLocalVariableAnnotations=null;hook.invisibleLocalVariableAnnotations=null;out.methods.add(hook);
		for(MethodNode method:source.methods)if(method!=hook&&method.name.startsWith("lambda$onLivingBreathe$")&&method.desc.endsWith(";D)Z"))out.methods.add(method);
		// The unused vehicle predicate references an unrelated API: retain only lambda handles the prefix actually uses.
		java.util.Set<String>used=new java.util.HashSet<>();for(var i:hook.instructions)if(i instanceof InvokeDynamicInsnNode factory)for(Object argument:factory.bsmArgs)if(argument instanceof Handle handle&&handle.getOwner().equals(source.name))used.add(handle.getName());out.methods.removeIf(m->m!=hook&&!used.contains(m.name));
		ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);out.accept(writer);return writer.toByteArray();
	}
}
