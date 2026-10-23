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

/**
 * Executes NeoForge's actual {@code CommonHooks.onLivingBreathe} — its breathing decision, its breathe event and its air update —
 * as the kernel transforms it, with the compiled {@code KernelAirRefill}, against stand-ins for the game classes it asks (the
 * drowning damage and vehicle tail are cut off). Air 100 of 300; the hook is handed vanilla's consume 1 and refill 4.
 *
 * <p>Vanilla refills a body that breathes where its eyes are by 4 (water breathing, conduit power, a drowned, an invulnerable
 * player, lava, a fluid that does not drown it), and holds it only in water for a body Breath of the Nautilus alone protects;
 * NeoForge's own hook holds every one of them (the switched-off control). A NeoForge listener still decides from that default:
 * it reads the refill vanilla gives, and what it sets — amounts, whether the body breathes — is what happens, as on NeoForge.
 */
@ExecutesInjector(VanillaAirRefillInjector.class)
@ResourceLock("system-properties")
class VanillaAirRefillInjectorExecutionTest {
	private static final String HOOK = "net/neoforged/neoforge/common/CommonHooks", LIVING = "net.minecraft.world.entity.LivingEntity";
	private static final Map<String, String> SOURCES = Map.ofEntries(
		Map.entry("net.minecraft.world.entity.Entity", "package net.minecraft.world.entity; public class Entity {}"),
		Map.entry(LIVING, """
		 package net.minecraft.world.entity;
		 import net.minecraft.world.level.Level;import net.neoforged.neoforge.fluids.FluidType;
		 public class LivingEntity extends Entity {
		  public int air=100,max=300;public boolean waterBreathing,refillEffects=true,breathesUnderwater;
		  public final EntityFluidInteraction interaction=new EntityFluidInteraction();
		  public EntityFluidInteraction getFluidInteraction(){return interaction;}
		  public boolean canDrownInFluidType(FluidType type){return type.water?!breathesUnderwater:type.canDrownIn(this);}
		  public Level level(){return new Level();}public double getX(){return 0;}public double getEyeY(){return 0;}public double getZ(){return 0;}
		  public int getAirSupply(){return air;}public int getMaxAirSupply(){return max;}public void setAirSupply(int air){this.air=air;}
		 }
		 """),
		Map.entry("net.minecraft.world.entity.EntityFluidInteraction", """
		 package net.minecraft.world.entity;import net.neoforged.neoforge.fluids.*;import net.minecraft.tags.*;
		 public class EntityFluidInteraction {public FluidType type;public int waterQueries;
		  public <E extends Entity>boolean isEyeInFluidMatching(E entity,InFluidPredicate<E>predicate){return predicate.test(entity,type,1);}
		  public boolean isEyeInFluid(TagKey tag){waterQueries++;return tag==FluidTags.WATER&&type.water;}
		 }
		 """),
		Map.entry("net.minecraft.tags.TagKey", "package net.minecraft.tags;public final class TagKey{}"),
		Map.entry("net.minecraft.tags.FluidTags", "package net.minecraft.tags;public final class FluidTags{public static final TagKey WATER=new TagKey(),LAVA=new TagKey();}"),
		Map.entry("net.neoforged.neoforge.fluids.InFluidPredicate", "package net.neoforged.neoforge.fluids;import net.minecraft.world.entity.Entity;public interface InFluidPredicate<E extends Entity>{boolean test(E entity,FluidType type,double height);}"),
		Map.entry("net.neoforged.neoforge.fluids.FluidType", """
		 package net.neoforged.neoforge.fluids;import net.minecraft.world.entity.LivingEntity;
		 public class FluidType {public final boolean drown,air,water;public FluidType(boolean drown,boolean air,boolean water){this.drown=drown;this.air=air;this.water=water;}
		  public boolean canDrownIn(LivingEntity entity){return drown;}public final boolean isAir(){return air;}}
		 """),
		Map.entry("net.minecraft.world.level.Level", "package net.minecraft.world.level;import net.minecraft.core.BlockPos;import net.minecraft.world.level.block.state.BlockState;public class Level{public BlockState getBlockState(BlockPos pos){return new BlockState();}}"),
		Map.entry("net.minecraft.server.level.ServerLevel", "package net.minecraft.server.level;public class ServerLevel extends net.minecraft.world.level.Level{}"),
		Map.entry("net.minecraft.core.BlockPos", "package net.minecraft.core;public class BlockPos{public static BlockPos containing(double x,double y,double z){return new BlockPos();}}"),
		Map.entry("net.minecraft.world.level.block.Block", "package net.minecraft.world.level.block;public class Block{}"),
		Map.entry("net.minecraft.world.level.block.Blocks", "package net.minecraft.world.level.block;public class Blocks{public static final Block BUBBLE_COLUMN=new Block();}"),
		Map.entry("net.minecraft.world.level.block.state.BlockState", "package net.minecraft.world.level.block.state;public class BlockState{public boolean is(Object block){return false;}}"),
		Map.entry("net.minecraft.world.effect.MobEffectUtil", """
		 package net.minecraft.world.effect;import net.minecraft.world.entity.LivingEntity;
		 public class MobEffectUtil{public static int refillQueries;
		  public static boolean hasWaterBreathing(LivingEntity entity){return entity.waterBreathing;}
		  public static boolean shouldEffectsRefillAirsupply(LivingEntity entity){refillQueries++;return entity.refillEffects;}}
		 """),
		Map.entry("net.minecraft.world.entity.player.Abilities", "package net.minecraft.world.entity.player;public class Abilities{public boolean invulnerable;}"),
		Map.entry("net.minecraft.world.entity.player.Player", "package net.minecraft.world.entity.player;public class Player extends net.minecraft.world.entity.LivingEntity{public final Abilities abilities=new Abilities();public Abilities getAbilities(){return abilities;}}"),
		Map.entry("net.neoforged.bus.api.Event", "package net.neoforged.bus.api;public class Event{}"),
		Map.entry("net.neoforged.bus.api.IEventBus", "package net.neoforged.bus.api;public interface IEventBus{Event post(Event event);}"),
		Map.entry("net.neoforged.neoforge.event.entity.living.LivingBreatheEvent", """
		 package net.neoforged.neoforge.event.entity.living;import net.neoforged.bus.api.Event;import net.minecraft.world.entity.LivingEntity;
		 public class LivingBreatheEvent extends Event {public boolean can;public int consume,refill;
		  public LivingBreatheEvent(LivingEntity entity,boolean can,int consume,int refill){this.can=can;this.consume=consume;this.refill=refill;}
		  public boolean canBreathe(){return can;}public int getRefillAirAmount(){return refill;}public int getConsumeAirAmount(){return consume;}}
		 """),
		Map.entry("net.neoforged.neoforge.common.NeoForge", """
		 package net.neoforged.neoforge.common;import net.neoforged.bus.api.*;import net.neoforged.neoforge.event.entity.living.LivingBreatheEvent;
		 public class NeoForge {public static int can=-1,refill=-1,consume=-1,posts;public static String seen;
		  public static final IEventBus EVENT_BUS=event->{posts++;LivingBreatheEvent e=(LivingBreatheEvent)event;seen=e.can+"/"+e.consume+"/"+e.refill;
		   if(can>=0)e.can=can==1;if(refill>=0)e.refill=refill;if(consume>=0)e.consume=consume;return event;};}
		 """));

	@AfterEach void reset() { System.clearProperty(VanillaAirRefillInjector.PROPERTY); }

	/** Each body that breathes where its eyes are refills by 4 as in vanilla; Breath of the Nautilus alone holds it in water only. */
	@Test void aBodyThatBreathesInItsFluidRefillsAsInVanilla(@TempDir Path work) throws Throwable {
		ClassLoader loader = loader(work, false);
		assertCase(loader, Body.of(Fluid.WATER).waterBreathing(), 104, 0, "water breathing in water");
		assertCase(loader, Body.of(Fluid.WATER).waterBreathing(), 104, 0, "conduit power in water (it refills like water breathing)");
		assertCase(loader, Body.of(Fluid.WATER).breathesUnderwater(), 104, 0, "a drowned in water");
		assertCase(loader, Body.of(Fluid.WATER).invulnerablePlayer(), 104, 0, "an invulnerable player in water");
		assertCase(loader, Body.of(Fluid.LAVA), 104, 0, "lava (a fluid nothing drowns in)");
		assertCase(loader, Body.of(Fluid.BREATHABLE), 104, 0, "a mod's fluid it may breathe in");
		assertCase(loader, Body.of(Fluid.DROWNING).waterBreathing(), 104, 0, "water breathing in a mod's drowning fluid");
		assertCase(loader, Body.of(Fluid.WATER).nautilusOnly(), 100, 1, "Breath of the Nautilus alone holds the air in water, as vanilla");
		assertCase(loader, Body.of(Fluid.LAVA).nautilusOnly(), 104, 1, "Breath of the Nautilus alone in lava: vanilla's other branch refills");
		assertCase(loader, Body.of(Fluid.WATER).waterBreathing().air(300), 300, 0, "at the maximum the air stays there");
		assertCase(loader, Body.of(Fluid.WATER).waterBreathing().air(298), 300, 0, "near the maximum it stops there");
	}

	/** What the hook decides by itself is untouched: a body that drowns consumes, a body in air refills. */
	@Test void drowningAndOpenAirAreTheHooksOwn(@TempDir Path work) throws Throwable {
		ClassLoader loader = loader(work, false);
		assertCase(loader, Body.of(Fluid.WATER), 99, 0, "no protection in water drowns");
		assertCase(loader, Body.of(Fluid.DROWNING), 99, 0, "no protection in a mod's drowning fluid drowns");
		assertCase(loader, Body.of(Fluid.AIR), 104, 0, "open air refills");
		assertCase(loader, Body.of(Fluid.AIR).nautilusOnly(), 104, 0, "open air refills whatever the effects");
	}

	/** The NeoForge listener reads vanilla's refill as the default and still decides, exactly as on NeoForge. */
	@Test void theBreatheEventStartsFromVanillasRefillAndStillDecides(@TempDir Path work) throws Throwable {
		ClassLoader loader = loader(work, false);
		Class<?> bus = loader.loadClass("net.neoforged.neoforge.common.NeoForge");
		assertCase(loader, Body.of(Fluid.WATER).waterBreathing(), 104, 0, "default");
		assertEquals("true/1/4", bus.getField("seen").get(null), "the listener is handed vanilla's refill");
		bus.getField("refill").setInt(null, 2);
		assertCase(loader, Body.of(Fluid.WATER).waterBreathing(), 102, 0, "a listener's refill amount is what refills");
		bus.getField("refill").setInt(null, 0);
		assertCase(loader, Body.of(Fluid.WATER).breathesUnderwater(), 100, 0, "a listener that zeroes the refill holds the air");
		bus.getField("refill").setInt(null, -1);
		bus.getField("can").setInt(null, 0);
		bus.getField("consume").setInt(null, 3);
		assertCase(loader, Body.of(Fluid.WATER).waterBreathing(), 97, 0, "a listener that stops the breathing consumes its amount");
		bus.getField("consume").setInt(null, -1);
		bus.getField("can").setInt(null, 1);
		bus.getField("refill").setInt(null, 3);
		assertCase(loader, Body.of(Fluid.WATER), 103, 0, "a listener that lets a drowning body breathe refills its amount");
		assertEquals("false/1/4", bus.getField("seen").get(null), "a drowning body's event is the hook's own");
	}

	/** Switched off, the hook is NeoForge's: the same bytes, and every body that breathes in its fluid holds its air. */
	@Test void switchedOffTheHookHoldsTheAirAsNeoForgeDoes(@TempDir Path work) throws Throwable {
		ClassLoader loader = loader(work, true);
		assertCase(loader, Body.of(Fluid.WATER).waterBreathing(), 100, 0, "NeoForge holds water breathing in water");
		assertCase(loader, Body.of(Fluid.WATER).breathesUnderwater(), 100, 0, "NeoForge holds a drowned");
		assertCase(loader, Body.of(Fluid.LAVA), 100, 0, "NeoForge holds lava");
		assertCase(loader, Body.of(Fluid.AIR), 104, 0, "open air still refills");
	}

	/** A hook of another shape — the refill stored twice, or zeroed away from the breathing decision — is left exactly as it is. */
	@Test void aHookOfAnotherShapeIsLeftAsItIs() throws Exception {
		Path jar = TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(jar), "actual NeoForge CommonHooks required");
		byte[] original;
		try (ZipFile z = new ZipFile(jar.toFile())) { original = z.getInputStream(z.getEntry(HOOK + ".class")).readAllBytes(); }
		for (boolean twice : List.of(true, false)) {
			ClassNode node = new ClassNode();
			new ClassReader(original).accept(node, 0);
			MethodNode hook = node.methods.stream().filter(m -> m.name.equals("onLivingBreathe")).findFirst().orElseThrow();
			VarInsnNode seam = null;
			for (AbstractInsnNode i : hook.instructions) if (i instanceof VarInsnNode store && store.getOpcode() == Opcodes.ISTORE && store.var == 3) seam = store;
			assertNotNull(seam, "the actual hook zeroes its refill");
			if (twice) {
				InsnList again = new InsnList();
				again.add(new VarInsnNode(Opcodes.ILOAD, 3));
				again.add(new VarInsnNode(Opcodes.ISTORE, 3));
				hook.instructions.insert(again);
			} else hook.instructions.insertBefore(seam.getPrevious(), new InsnNode(Opcodes.NOP));
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			node.accept(writer);
			byte[] changed = writer.toByteArray();
			assertSame(changed, InjectorExecution.transform(new VanillaAirRefillInjector(), HOOK.replace('/', '.'), changed, EnvType.SERVER),
					twice ? "a refill stored twice is not the hook's one protected zero" : "a zero away from the breathing decision is not the seam");
		}
	}

	/** The fluid the eyes are in, as the hook's questions see it. */
	enum Fluid {
		WATER(true, false, true), LAVA(false, false, false), BREATHABLE(false, false, false), DROWNING(true, false, false), AIR(false, true, false);
		final boolean drown, air, water;
		Fluid(boolean drown, boolean air, boolean water) { this.drown = drown; this.air = air; this.water = water; }
	}

	/** A body: its fluid, whether an effect keeps it from drowning, whether its effects let vanilla refill, a drowned's lungs, a player. */
	record Body(Fluid fluid, boolean effect, boolean refills, boolean breather, boolean player, int start) {
		static Body of(Fluid fluid) { return new Body(fluid, false, true, false, false, 100); }
		Body waterBreathing() { return new Body(fluid, true, true, breather, player, start); }
		Body nautilusOnly() { return new Body(fluid, true, false, breather, player, start); }
		Body breathesUnderwater() { return new Body(fluid, effect, refills, true, player, start); }
		Body invulnerablePlayer() { return new Body(fluid, effect, refills, breather, true, start); }
		Body air(int value) { return new Body(fluid, effect, refills, breather, player, value); }
	}

	private static void assertCase(ClassLoader loader, Body body, int expected, int waterQueries, String what) throws Throwable {
		Object fluid = InjectorExecution.construct(loader.loadClass("net.neoforged.neoforge.fluids.FluidType"), body.fluid().drown, body.fluid().air, body.fluid().water);
		Object entity = InjectorExecution.construct(loader.loadClass(body.player() ? "net.minecraft.world.entity.player.Player" : LIVING));
		Class<?> living = loader.loadClass(LIVING);
		living.getField("waterBreathing").setBoolean(entity, body.effect());
		living.getField("refillEffects").setBoolean(entity, body.refills());
		living.getField("breathesUnderwater").setBoolean(entity, body.breather());
		living.getField("air").setInt(entity, body.start());
		if (body.player()) {
			Object abilities = InjectorExecution.invoke(entity, "getAbilities");
			abilities.getClass().getField("invulnerable").setBoolean(abilities, true);
		}
		Object interaction = InjectorExecution.invoke(entity, "getFluidInteraction");
		interaction.getClass().getField("type").set(interaction, fluid);
		Class<?> bus = loader.loadClass("net.neoforged.neoforge.common.NeoForge");
		int before = bus.getField("posts").getInt(null);
		int refill = Math.min(body.start() + 4, 300) - body.start();
		InjectorExecution.invokeStatic(loader.loadClass(HOOK.replace('/', '.')), "onLivingBreathe", entity, null, 1, refill);
		assertEquals(expected, InjectorExecution.invoke(entity, "getAirSupply"), what);
		assertEquals(before + 1, bus.getField("posts").getInt(null), "one breathe event: " + what);
		assertEquals(waterQueries, interaction.getClass().getField("waterQueries").getInt(interaction),
				"the water question is asked only where the effects alone do not decide: " + what);
	}

	private static ClassLoader loader(Path work, boolean disabled) throws Exception {
		Map<String, byte[]> classes = new HashMap<>(InjectorExecution.compile(work, SOURCES));
		Path jar = TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(jar), "actual NeoForge CommonHooks required");
		byte[] original;
		try (ZipFile z = new ZipFile(jar.toFile())) { original = z.getInputStream(z.getEntry(HOOK + ".class")).readAllBytes(); }
		if (disabled) System.setProperty(VanillaAirRefillInjector.PROPERTY, "off");
		byte[] transformed = InjectorExecution.transform(new VanillaAirRefillInjector(), HOOK.replace('/', '.'), original, EnvType.SERVER);
		System.clearProperty(VanillaAirRefillInjector.PROPERTY);
		if (disabled) assertSame(original, transformed);
		else assertNotSame(original, transformed);
		classes.put(HOOK, prefix(transformed));
		Path runtime = Path.of(System.getProperty("forbric.fluidBreathingRuntime", Path.of(System.getProperty("user.dir"), "build/classes/java/runtime").toString()));
		Path file = runtime.resolve(VanillaAirRefillInjector.RUNTIME + ".class");
		TestFixtures.require(Fixture.GAME_SIDE, Files.isRegularFile(file), "compiled KernelAirRefill required");
		classes.put(VanillaAirRefillInjector.RUNTIME, Files.readAllBytes(file));
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(classes.get(HOOK), loader));
		assertEquals("", InjectorExecution.verify(classes.get(VanillaAirRefillInjector.RUNTIME), loader));
		return loader;
	}

	/** The hook unchanged through its air update; the drowning damage and vehicle tail after it are cut off. */
	private static byte[] prefix(byte[] bytes) {
		ClassNode source = new ClassNode();
		new ClassReader(bytes).accept(source, ClassReader.EXPAND_FRAMES);
		ClassNode out = new ClassNode();
		out.version = source.version; out.access = Opcodes.ACC_PUBLIC; out.name = source.name; out.superName = "java/lang/Object";
		MethodNode hook = source.methods.stream().filter(m -> m.name.equals("onLivingBreathe")).findFirst().orElseThrow();
		List<MethodInsnNode> air = new java.util.ArrayList<>();
		for (var i : hook.instructions) if (i instanceof MethodInsnNode call && call.name.equals("getAirSupply")) air.add(call);
		assertTrue(air.size() >= 3, "actual native refill/consume/join shape");
		AbstractInsnNode cut = air.get(2).getPrevious();
		while (cut.getOpcode() < 0) cut = cut.getPrevious();
		assertEquals(Opcodes.ALOAD, cut.getOpcode());
		for (var i = cut; i != null;) { var next = i.getNext(); hook.instructions.remove(i); i = next; }
		hook.instructions.add(new InsnNode(Opcodes.RETURN));
		hook.localVariables = null; hook.visibleLocalVariableAnnotations = null; hook.invisibleLocalVariableAnnotations = null;
		out.methods.add(hook);
		java.util.Set<String> used = new java.util.HashSet<>();
		for (var i : hook.instructions) if (i instanceof InvokeDynamicInsnNode factory) for (Object argument : factory.bsmArgs)
			if (argument instanceof Handle handle && handle.getOwner().equals(source.name)) used.add(handle.getName());
		for (MethodNode method : source.methods) if (method != hook && used.contains(method.name)) out.methods.add(method);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		out.accept(writer);
		return writer.toByteArray();
	}
}
