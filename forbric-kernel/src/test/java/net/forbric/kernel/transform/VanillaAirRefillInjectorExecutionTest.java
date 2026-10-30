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
import net.forbric.kernel.mixin.CrossHostIslandFixture;
import net.forbric.kernel.mixin.CrossHostPredicateIslandInjector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * Executes NeoForge's actual {@code CommonHooks.onLivingBreathe} — its breathing decision, its breathe event and its air update —
 * as the kernel transforms it, with the compiled {@code KernelAirRefill}, against stand-ins for the game classes it asks (the
 * drowning damage and vehicle tail are cut off). Air 100 of 300 unless a case says otherwise; the hook is handed vanilla's
 * consume 1 and refill {@code min(air + 4, 300) - air}.
 *
 * <p>Vanilla refills a body that breathes where its eyes are by 4 (water breathing, conduit power, a drowned, an invulnerable
 * player, lava, a fluid that does not drown it), and holds it only in its water branch — the eye in a fluid the body can drown
 * in, by the fluid type's own answer — for a body Breath of the Nautilus alone protects; it leaves air at or above the maximum
 * where it is. NeoForge's own hook holds every one of them and pulls air above the maximum back to it (the switched-off
 * control). A NeoForge listener still decides from that default: it reads the refill vanilla gives, and what it sets — amounts,
 * whether the body breathes — is what happens, as on NeoForge. The cross-host predicate island's prefix, put into the same
 * hook, is executed too: its decision replaces the hook's, and its protected decision hands the event vanilla's water-branch
 * refill. As in the chain, the kernel's delivery bookkeeping (NativeDualEventInjector) is in the hook before either.
 */
@ExecutesInjector({VanillaAirRefillInjector.class, CrossHostPredicateIslandInjector.class})
@ResourceLock("system-properties")
class VanillaAirRefillInjectorExecutionTest {
	private static final String HOOK = "net/neoforged/neoforge/common/CommonHooks", LIVING = "net.minecraft.world.entity.LivingEntity";
	private static final String ISLANDS = "net.forbric.kernel.runtime.KernelFluidPredicateIslands";
	private static final Map<String, String> SOURCES = Map.ofEntries(
		Map.entry("net.minecraft.world.entity.Entity", "package net.minecraft.world.entity; public class Entity {}"),
		Map.entry(LIVING, """
		 package net.minecraft.world.entity;
		 import net.minecraft.world.level.Level;import net.neoforged.neoforge.fluids.FluidType;
		 public class LivingEntity extends Entity {
		  public int air=100,max=300;public boolean waterBreathing,conduit,nautilus,breathesUnderwater;
		  public final EntityFluidInteraction interaction=new EntityFluidInteraction();
		  public EntityFluidInteraction getFluidInteraction(){return interaction;}
		  public boolean canDrownInFluidType(FluidType type){return type.water?!breathesUnderwater:type.drowns(this);}
		  public Level level(){return new Level();}public double getX(){return 0;}public double getEyeY(){return 0;}public double getZ(){return 0;}
		  public int getAirSupply(){return air;}public int getMaxAirSupply(){return max;}public void setAirSupply(int air){this.air=air;}
		 }
		 """),
		Map.entry("net.minecraft.world.entity.EntityFluidInteraction", """
		 package net.minecraft.world.entity;import net.neoforged.neoforge.fluids.*;import net.minecraft.tags.*;
		 public class EntityFluidInteraction {public FluidType type;public static int tagQueries;
		  public <E extends Entity>boolean isEyeInFluidMatching(E entity,InFluidPredicate<E>predicate){return predicate.test(entity,type,1);}
		  public boolean isEyeInFluid(TagKey tag){tagQueries++;return tag==FluidTags.WATER&&type.water;}
		 }
		 """),
		Map.entry("net.minecraft.tags.TagKey", "package net.minecraft.tags;public final class TagKey{}"),
		Map.entry("net.minecraft.tags.FluidTags", "package net.minecraft.tags;public final class FluidTags{public static final TagKey WATER=new TagKey(),LAVA=new TagKey();}"),
		Map.entry("net.neoforged.neoforge.fluids.InFluidPredicate", "package net.neoforged.neoforge.fluids;import net.minecraft.world.entity.Entity;public interface InFluidPredicate<E extends Entity>{boolean test(E entity,FluidType type,double height);}"),
		// canDrownIn is the type's own answer for a body, counted: only the kernel's refill asks it (the stand-in body's
		// canDrownInFluidType reads the same answer uncounted, as NeoForge's does for every type but water's).
		Map.entry("net.neoforged.neoforge.fluids.FluidType", """
		 package net.neoforged.neoforge.fluids;import net.minecraft.world.entity.LivingEntity;
		 public class FluidType {public static int drownQueries;public final boolean drown,air,water,sparesBreathers;
		  public FluidType(boolean drown,boolean air,boolean water,boolean sparesBreathers){this.drown=drown;this.air=air;this.water=water;this.sparesBreathers=sparesBreathers;}
		  public boolean drowns(LivingEntity entity){return drown&&!(sparesBreathers&&entity.breathesUnderwater);}
		  public boolean canDrownIn(LivingEntity entity){drownQueries++;return drowns(entity);}public final boolean isAir(){return air;}}
		 """),
		Map.entry("net.minecraft.world.level.Level", "package net.minecraft.world.level;import net.minecraft.core.BlockPos;import net.minecraft.world.level.block.state.BlockState;public class Level{public BlockState getBlockState(BlockPos pos){return new BlockState();}}"),
		Map.entry("net.minecraft.server.level.ServerLevel", "package net.minecraft.server.level;public class ServerLevel extends net.minecraft.world.level.Level{}"),
		Map.entry("net.minecraft.core.BlockPos", "package net.minecraft.core;public class BlockPos{public static BlockPos containing(double x,double y,double z){return new BlockPos();}}"),
		Map.entry("net.minecraft.world.level.block.Block", "package net.minecraft.world.level.block;public class Block{}"),
		Map.entry("net.minecraft.world.level.block.Blocks", "package net.minecraft.world.level.block;public class Blocks{public static final Block BUBBLE_COLUMN=new Block();}"),
		Map.entry("net.minecraft.world.level.block.state.BlockState", "package net.minecraft.world.level.block.state;public class BlockState{public boolean is(Object block){return false;}}"),
		// As the merged MobEffectUtil decides it: the three effects that keep a body from drowning, and the refill only Breath
		// of the Nautilus alone withholds.
		Map.entry("net.minecraft.world.effect.MobEffectUtil", """
		 package net.minecraft.world.effect;import net.minecraft.world.entity.LivingEntity;
		 public class MobEffectUtil{
		  public static boolean hasWaterBreathing(LivingEntity entity){return entity.waterBreathing||entity.conduit||entity.nautilus;}
		  public static boolean shouldEffectsRefillAirsupply(LivingEntity entity){return !entity.nautilus||entity.waterBreathing||entity.conduit;}}
		 """),
		Map.entry("net.minecraft.world.entity.player.Abilities", "package net.minecraft.world.entity.player;public class Abilities{public boolean invulnerable;}"),
		Map.entry("net.minecraft.world.entity.player.Player", "package net.minecraft.world.entity.player;public class Player extends net.minecraft.world.entity.LivingEntity{public final Abilities abilities=new Abilities();public Abilities getAbilities(){return abilities;}}"),
		Map.entry("net.neoforged.bus.api.Event", "package net.neoforged.bus.api;public class Event{}"),
		Map.entry("net.neoforged.bus.api.IEventBus", "package net.neoforged.bus.api;public interface IEventBus{Event post(Event event);}"),
		// As NeoForge's own event holds its amounts: never below zero, in the constructor and in the setters.
		Map.entry("net.neoforged.neoforge.event.entity.living.LivingBreatheEvent", """
		 package net.neoforged.neoforge.event.entity.living;import net.neoforged.bus.api.Event;import net.minecraft.world.entity.LivingEntity;
		 public class LivingBreatheEvent extends Event {private boolean can;private int consume,refill;
		  public LivingBreatheEvent(LivingEntity entity,boolean can,int consume,int refill){this.can=can;this.consume=Math.max(consume,0);this.refill=Math.max(refill,0);}
		  public boolean canBreathe(){return can;}public void setCanBreathe(boolean can){this.can=can;}
		  public int getRefillAirAmount(){return refill;}public void setRefillAirAmount(int refill){this.refill=Math.max(refill,0);}
		  public int getConsumeAirAmount(){return consume;}public void setConsumeAirAmount(int consume){this.consume=Math.max(consume,0);}}
		 """),
		Map.entry("net.neoforged.neoforge.common.NeoForge", """
		 package net.neoforged.neoforge.common;import net.neoforged.bus.api.*;import net.neoforged.neoforge.event.entity.living.LivingBreatheEvent;
		 public class NeoForge {public static int can=-1,refill=Integer.MIN_VALUE,consume=-1,posts;public static String seen;
		  public static final IEventBus EVENT_BUS=event->{posts++;LivingBreatheEvent e=(LivingBreatheEvent)event;seen=e.canBreathe()+"/"+e.getConsumeAirAmount()+"/"+e.getRefillAirAmount();
		   if(can>=0)e.setCanBreathe(can==1);if(refill!=Integer.MIN_VALUE)e.setRefillAirAmount(refill);if(consume>=0)e.setConsumeAirAmount(consume);return event;};}
		 """),
		// The island's runtime gate, answering the decision a test sets (-1: none, the hook decides; else the native predicate's).
		Map.entry(ISLANDS, """
		 package net.forbric.kernel.runtime;
		 public final class KernelFluidPredicateIslands {public static int decision=-1,calls;
		  public static int current(Object actor,Object world,Object interaction,String key){calls++;return decision;}}
		 """));

	@AfterEach void reset() {
		System.clearProperty(VanillaAirRefillInjector.PROPERTY);
		CrossHostIslandFixture.reset();
	}

	/** Each body that breathes where its eyes are refills by 4 as in vanilla; Breath of the Nautilus alone holds it where the body could drown. */
	@Test void aBodyThatBreathesInItsFluidRefillsAsInVanilla(@TempDir Path work) throws Throwable {
		ClassLoader loader = loader(work, Mode.REFILL);
		assertCase(loader, Body.of(Fluid.WATER).waterBreathing(), 104, 0, "water breathing in water");
		assertCase(loader, Body.of(Fluid.WATER).conduit(), 104, 0, "conduit power in water");
		assertCase(loader, Body.of(Fluid.WATER).conduit().nautilus(), 104, 0, "conduit power beside Breath of the Nautilus lets it refill");
		assertCase(loader, Body.of(Fluid.WATER).breathesUnderwater(), 104, 0, "a drowned in water");
		assertCase(loader, Body.of(Fluid.WATER).invulnerablePlayer(), 104, 0, "an invulnerable player in water");
		assertCase(loader, Body.of(Fluid.LAVA), 104, 0, "lava (a fluid nothing drowns in)");
		assertCase(loader, Body.of(Fluid.BREATHABLE), 104, 0, "a mod's fluid it may breathe in");
		assertCase(loader, Body.of(Fluid.DROWNING).waterBreathing(), 104, 0, "water breathing in a mod's drowning fluid");
		assertCase(loader, Body.of(Fluid.WATER).waterBreathing().air(300), 300, 0, "at the maximum the air stays there");
		assertCase(loader, Body.of(Fluid.WATER).waterBreathing().air(298), 300, 0, "near the maximum it stops there");
	}

	/**
	 * Breath of the Nautilus alone: vanilla's water branch holds the air — the eye in a fluid the body can drown in, as the fluid
	 * type answers for this body (fabric-api makes a Fabric drowning fluid answer isEyeInFluid(WATER) that way) — and every other
	 * fluid refills it. Asked of the eye's fluid types, never through the water tag.
	 */
	@Test void breathOfTheNautilusAloneHoldsTheAirWhereTheBodyCouldDrown(@TempDir Path work) throws Throwable {
		ClassLoader loader = loader(work, Mode.REFILL);
		assertCase(loader, Body.of(Fluid.WATER).nautilus(), 100, 1, "in water, as vanilla");
		assertCase(loader, Body.of(Fluid.DROWNING).nautilus(), 100, 1, "in a mod's drowning fluid outside the water tag, as natively on all three");
		assertCase(loader, Body.of(Fluid.WATER).nautilus().breathesUnderwater(), 100, 1, "a drowned in water: water's type drowns whoever is in it");
		assertCase(loader, Body.of(Fluid.LAVA).nautilus(), 104, 1, "in lava: vanilla's other branch refills");
		assertCase(loader, Body.of(Fluid.BREATHABLE).nautilus(), 104, 1, "in a fluid that drowns nobody: refills");
		assertCase(loader, Body.of(Fluid.SPARES_BREATHERS).nautilus(), 100, 1, "a fluid that drowns this body holds it");
		assertCase(loader, Body.of(Fluid.SPARES_BREATHERS).nautilus().breathesUnderwater(), 104, 1, "the same fluid, for a body it does not drown, refills");
	}

	/** What the hook decides by itself is untouched: a body that drowns consumes, a body in air refills. */
	@Test void drowningAndOpenAirAreTheHooksOwn(@TempDir Path work) throws Throwable {
		ClassLoader loader = loader(work, Mode.REFILL);
		assertCase(loader, Body.of(Fluid.WATER), 99, 0, "no protection in water drowns");
		assertCase(loader, Body.of(Fluid.DROWNING), 99, 0, "no protection in a mod's drowning fluid drowns");
		assertCase(loader, Body.of(Fluid.AIR), 104, 0, "open air refills");
		assertCase(loader, Body.of(Fluid.AIR).nautilus(), 104, 0, "open air refills whatever the effects");
	}

	/**
	 * Air above the maximum stays there, as vanilla leaves it (it changes the air only below the maximum), where NeoForge's update
	 * pulls it back to the maximum every tick the body breathes. What a listener is handed is unchanged (NeoForge's event holds
	 * the negative refill there as zero), and a refill a listener sets is applied as NeoForge applies it, clamp included — but a
	 * listener that sets the very amount the event started from cannot be told apart from one that left it.
	 */
	@Test void airAboveTheMaximumStaysAsInVanilla(@TempDir Path work) throws Throwable {
		ClassLoader loader = loader(work, Mode.REFILL);
		Class<?> bus = loader.loadClass("net.neoforged.neoforge.common.NeoForge");
		assertCase(loader, Body.of(Fluid.WATER).waterBreathing().air(400), 400, 0, "water breathing in water at 400 of 300");
		assertCase(loader, Body.of(Fluid.AIR).air(400), 400, 0, "open air at 400 of 300");
		assertEquals("true/1/0", bus.getField("seen").get(null), "the listener is handed what NeoForge hands it in open air");
		assertCase(loader, Body.of(Fluid.WATER).nautilus().air(400), 400, 0, "Breath of the Nautilus alone in water at 400");
		assertCase(loader, Body.of(Fluid.LAVA).air(400), 400, 0, "lava at 400");
		assertCase(loader, Body.of(Fluid.WATER).air(400), 399, 0, "a body that drowns still loses its air, as vanilla");
		bus.getField("refill").setInt(null, 2);
		assertCase(loader, Body.of(Fluid.WATER).waterBreathing().air(400), 300, 0, "a listener's refill is NeoForge's to apply, clamp included");
		bus.getField("refill").setInt(null, 1);
		assertCase(loader, Body.of(Fluid.AIR).air(400), 300, 0, "in open air too");
		bus.getField("refill").setInt(null, 0);
		assertCase(loader, Body.of(Fluid.AIR).air(400), 400, 0, "a listener that sets the amount the event started from is not told apart");
	}

	/** The NeoForge listener reads vanilla's refill as the default and still decides, exactly as on NeoForge. */
	@Test void theBreatheEventStartsFromVanillasRefillAndStillDecides(@TempDir Path work) throws Throwable {
		ClassLoader loader = loader(work, Mode.REFILL);
		Class<?> bus = loader.loadClass("net.neoforged.neoforge.common.NeoForge");
		assertCase(loader, Body.of(Fluid.WATER).waterBreathing(), 104, 0, "default");
		assertEquals("true/1/4", bus.getField("seen").get(null), "the listener is handed vanilla's refill");
		bus.getField("refill").setInt(null, 2);
		assertCase(loader, Body.of(Fluid.WATER).waterBreathing(), 102, 0, "a listener's refill amount is what refills");
		bus.getField("refill").setInt(null, 0);
		assertCase(loader, Body.of(Fluid.WATER).breathesUnderwater(), 100, 0, "a listener that zeroes the refill holds the air");
		bus.getField("refill").setInt(null, Integer.MIN_VALUE);
		bus.getField("can").setInt(null, 0);
		bus.getField("consume").setInt(null, 3);
		assertCase(loader, Body.of(Fluid.WATER).waterBreathing(), 97, 0, "a listener that stops the breathing consumes its amount");
		bus.getField("consume").setInt(null, -1);
		bus.getField("can").setInt(null, 1);
		bus.getField("refill").setInt(null, 3);
		assertCase(loader, Body.of(Fluid.WATER), 103, 0, "a listener that lets a drowning body breathe refills its amount");
		assertEquals("false/1/4", bus.getField("seen").get(null), "a drowning body's event is the hook's own");
	}

	/** Switched off, the hook is NeoForge's: the same bytes, every body that breathes in its fluid holds its air, air above the maximum is pulled down. */
	@Test void switchedOffTheHookHoldsTheAirAsNeoForgeDoes(@TempDir Path work) throws Throwable {
		ClassLoader loader = loader(work, Mode.OFF);
		Class<?> bus = loader.loadClass("net.neoforged.neoforge.common.NeoForge");
		assertCase(loader, Body.of(Fluid.WATER).waterBreathing(), 100, 0, "NeoForge holds water breathing in water");
		assertCase(loader, Body.of(Fluid.WATER).breathesUnderwater(), 100, 0, "NeoForge holds a drowned");
		assertCase(loader, Body.of(Fluid.LAVA), 100, 0, "NeoForge holds lava");
		assertCase(loader, Body.of(Fluid.LAVA).nautilus(), 100, 0, "NeoForge holds Breath of the Nautilus alone anywhere");
		assertCase(loader, Body.of(Fluid.AIR), 104, 0, "open air still refills");
		assertCase(loader, Body.of(Fluid.WATER).waterBreathing().air(400), 300, 0, "NeoForge pulls air above the maximum down");
		assertCase(loader, Body.of(Fluid.AIR).air(400), 300, 0, "in open air too");
		assertEquals("true/1/0", bus.getField("seen").get(null), "and hands its listener the same refill as the kernel's hook does");
	}

	/**
	 * The cross-host predicate island's prefix, executed in the decorated hook: the island's decision (KernelFluidPredicateIslands,
	 * here a stand-in answering what a test sets) replaces the hook's own once, and its protected decision — the native predicate
	 * took the water branch and the body does not drown — hands the event vanilla's water-branch refill without asking any fluid.
	 * No decision leaves the hook to decide; on NeoForge's undecorated hook the island hands the carrier's zero instead.
	 */
	@Test void theIslandsProtectedDecisionHandsTheEventVanillasWaterBranchRefill(@TempDir Path work) throws Throwable {
		ClassLoader loader = loader(work, Mode.ISLAND);
		Class<?> islands = loader.loadClass(ISLANDS);
		islands.getField("decision").setInt(null, 1);
		assertIsland(loader, Body.of(Fluid.DROWNING).nautilus(), 100, 0, "protected, Breath of the Nautilus alone: the water branch holds it");
		assertIsland(loader, Body.of(Fluid.DROWNING).waterBreathing(), 104, 0, "protected, water breathing: the water branch refills");
		assertIsland(loader, Body.of(Fluid.DROWNING).conduit(), 104, 0, "protected, conduit power: the water branch refills");
		assertIsland(loader, Body.of(Fluid.DROWNING).breathesUnderwater(), 104, 0, "protected, a drowned (the native predicate's own protection): refills");
		assertIsland(loader, Body.of(Fluid.DROWNING).nautilus().air(400), 400, 0, "protected, above the maximum: stays");
		islands.getField("decision").setInt(null, 3);
		assertIsland(loader, Body.of(Fluid.DROWNING).nautilus(), 104, 0, "the native predicate found no drowning fluid: air refills");
		assertIsland(loader, Body.of(Fluid.DROWNING).air(400), 400, 0, "and above the maximum stays");
		islands.getField("decision").setInt(null, 0);
		assertIsland(loader, Body.of(Fluid.DROWNING).nautilus(), 99, 0, "the native predicate drowns the body: it consumes");
		islands.getField("decision").setInt(null, -1);
		assertIsland(loader, Body.of(Fluid.DROWNING).nautilus(), 100, 1, "no decision: the hook's own protected branch, which asks the fluid");
		assertIsland(loader, Body.of(Fluid.BREATHABLE).nautilus(), 104, 1, "no decision, a fluid that drowns nobody: the hook's branch refills");

		ClassLoader carrier = loader(work.resolve("carrier"), Mode.ISLAND_UNDECORATED);
		Class<?> undecorated = carrier.loadClass(ISLANDS);
		undecorated.getField("decision").setInt(null, 1);
		assertIsland(carrier, Body.of(Fluid.DROWNING).waterBreathing(), 100, 0, "NeoForge's own hook: the island hands the carrier's zero");
	}

	/** A hook of another shape — the refill stored twice, zeroed away from the breathing decision, or clamped twice — is left exactly as it is. */
	@Test void aHookOfAnotherShapeIsLeftAsItIs() throws Exception {
		byte[] original = hook();
		for (String change : List.of("refill stored twice", "zero away from the decision", "clamped twice", "event stored twice")) {
			ClassNode node = new ClassNode();
			new ClassReader(original).accept(node, 0);
			MethodNode hook = node.methods.stream().filter(m -> m.name.equals("onLivingBreathe")).findFirst().orElseThrow();
			VarInsnNode seam = null, kept = null;
			for (AbstractInsnNode i : hook.instructions) {
				if (i instanceof VarInsnNode store && store.getOpcode() == Opcodes.ISTORE && store.var == 3) seam = store;
				AbstractInsnNode before = i.getPrevious();
				while (before != null && before.getOpcode() < 0) before = before.getPrevious();
				if (i instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE && kept == null && before instanceof MethodInsnNode init
						&& init.name.equals("<init>")) kept = store;
			}
			assertNotNull(seam, "the actual hook zeroes its refill");
			assertNotNull(kept, "the actual hook keeps its event");
			InsnList again = new InsnList();
			switch (change) {
				case "refill stored twice" -> { again.add(new VarInsnNode(Opcodes.ILOAD, 3)); again.add(new VarInsnNode(Opcodes.ISTORE, 3)); }
				case "zero away from the decision" -> hook.instructions.insertBefore(seam.getPrevious(), new InsnNode(Opcodes.NOP));
				case "clamped twice" -> { again.add(new InsnNode(Opcodes.ICONST_0)); again.add(new InsnNode(Opcodes.ICONST_0));
					again.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "min", "(II)I", false)); again.add(new InsnNode(Opcodes.POP)); }
				default -> { }
			}
			if (change.equals("event stored twice")) {
				// The event replaced right after it is kept: the update would read another value.
				InsnList replaced = new InsnList();
				replaced.add(new VarInsnNode(Opcodes.ALOAD, kept.var));
				replaced.add(new VarInsnNode(Opcodes.ASTORE, kept.var));
				hook.instructions.insert(kept, replaced);
			} else if (again.size() > 0) hook.instructions.insert(again);
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			node.accept(writer);
			byte[] changed = writer.toByteArray();
			assertSame(changed, InjectorExecution.transform(new VanillaAirRefillInjector(), HOOK.replace('/', '.'), changed, EnvType.SERVER), change);
		}
	}

	/** The fluid the eyes are in, as the hook's questions see it. */
	enum Fluid {
		WATER(true, false, true, false), LAVA(false, false, false, false), BREATHABLE(false, false, false, false), DROWNING(true, false, false, false),
		/** A fluid type whose own answer depends on the body: it drowns a body that cannot breathe under water, and spares one that can. */
		SPARES_BREATHERS(true, false, false, true), AIR(false, true, false, false);
		final boolean drown, air, water, sparesBreathers;
		Fluid(boolean drown, boolean air, boolean water, boolean sparesBreathers) { this.drown = drown; this.air = air; this.water = water; this.sparesBreathers = sparesBreathers; }
	}

	/** A body: its fluid, its effects (water breathing, conduit power, Breath of the Nautilus), a drowned's lungs, a player, its air. */
	record Body(Fluid fluid, boolean withWaterBreathing, boolean withConduit, boolean withNautilus, boolean breather, boolean player, int start) {
		static Body of(Fluid fluid) { return new Body(fluid, false, false, false, false, false, 100); }
		Body waterBreathing() { return new Body(fluid, true, withConduit, withNautilus, breather, player, start); }
		Body conduit() { return new Body(fluid, withWaterBreathing, true, withNautilus, breather, player, start); }
		Body nautilus() { return new Body(fluid, withWaterBreathing, withConduit, true, breather, player, start); }
		Body breathesUnderwater() { return new Body(fluid, withWaterBreathing, withConduit, withNautilus, true, player, start); }
		Body invulnerablePlayer() { return new Body(fluid, withWaterBreathing, withConduit, withNautilus, breather, true, start); }
		Body air(int value) { return new Body(fluid, withWaterBreathing, withConduit, withNautilus, breather, player, value); }
	}

	/** Runs the hook once for {@code body}: the air it leaves, one event, and how often the kernel's refill asked a fluid type whether it drowns the body. */
	private static void assertCase(ClassLoader loader, Body body, int expected, int drownQueries, String what) throws Throwable {
		Class<?> type = loader.loadClass("net.neoforged.neoforge.fluids.FluidType");
		Object fluid = InjectorExecution.construct(type, body.fluid().drown, body.fluid().air, body.fluid().water, body.fluid().sparesBreathers);
		Object entity = InjectorExecution.construct(loader.loadClass(body.player() ? "net.minecraft.world.entity.player.Player" : LIVING));
		Class<?> living = loader.loadClass(LIVING);
		living.getField("waterBreathing").setBoolean(entity, body.withWaterBreathing());
		living.getField("conduit").setBoolean(entity, body.withConduit());
		living.getField("nautilus").setBoolean(entity, body.withNautilus());
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
		type.getField("drownQueries").setInt(null, 0);
		int refill = Math.min(body.start() + 4, 300) - body.start();
		InjectorExecution.invokeStatic(loader.loadClass(HOOK.replace('/', '.')), "onLivingBreathe", entity, null, 1, refill);
		assertEquals(expected, InjectorExecution.invoke(entity, "getAirSupply"), what);
		assertEquals(before + 1, bus.getField("posts").getInt(null), "one breathe event: " + what);
		assertEquals(drownQueries, type.getField("drownQueries").getInt(null),
				"a fluid type is asked whether it drowns the body only where the effects alone do not decide: " + what);
		assertEquals(0, interaction.getClass().getField("tagQueries").getInt(null), "nothing asks the water tag: " + what);
	}

	/** As {@link #assertCase}, and the island's gate is asked exactly once per run. */
	private static void assertIsland(ClassLoader loader, Body body, int expected, int drownQueries, String what) throws Throwable {
		Class<?> islands = loader.loadClass(ISLANDS);
		int before = islands.getField("calls").getInt(null);
		assertCase(loader, body, expected, drownQueries, what);
		assertEquals(before + 1, islands.getField("calls").getInt(null), "the island decides once per run: " + what);
	}

	private enum Mode { REFILL, OFF, ISLAND, ISLAND_UNDECORATED }

	private static byte[] hook() throws Exception {
		return staged(HOOK);
	}

	/** The game's class resources by path, as the chain hands them to the delivery bookkeeping. */
	private static byte[] stagedResource(String path) {
		for (String jar : List.of(TestFixtures.BASE_JAR, TestFixtures.NEO_CARRIER_JAR, TestFixtures.FORGE_INTEROP_JAR)) {
			Path file = TestFixtures.stagedRoot().resolve(jar);
			if (!Files.isRegularFile(file)) continue;
			try (ZipFile z = new ZipFile(file.toFile())) {
				var entry = z.getEntry(path);
				if (entry != null) return z.getInputStream(entry).readAllBytes();
			} catch (java.io.IOException unreadable) {
				throw new java.io.UncheckedIOException(unreadable);
			}
		}
		return null;
	}

	private static byte[] staged(String entry) throws Exception {
		Path jar = TestFixtures.stagedRoot().resolve(TestFixtures.NEO_CARRIER_JAR);
		TestFixtures.requireFile(Fixture.STAGED, jar, "actual NeoForge CommonHooks required");
		try (ZipFile z = new ZipFile(jar.toFile())) { return z.getInputStream(z.getEntry(entry + ".class")).readAllBytes(); }
	}

	/** The stand-in event holds its amounts as the staged one does: never below zero, in the constructor and the refill setter. */
	@Test void theStandInEventClampsLikeNeoForgesOwn() throws Exception {
		ClassNode event = new ClassNode();
		new ClassReader(staged("net/neoforged/neoforge/event/entity/living/LivingBreatheEvent")).accept(event, 0);
		Map<String, Integer> clamps = new HashMap<>();
		for (MethodNode method : event.methods) for (AbstractInsnNode insn : method.instructions)
			if (insn instanceof MethodInsnNode call && call.owner.equals("java/lang/Math") && call.name.equals("max") && call.desc.equals("(II)I"))
				clamps.merge(method.name + method.desc, 1, Integer::sum);
		assertEquals(2, clamps.get("<init>(Lnet/minecraft/world/entity/LivingEntity;ZII)V"), "consume and refill clamped when constructed");
		assertEquals(1, clamps.get("setRefillAirAmount(I)V"), "a listener's refill clamped");
		assertEquals(1, clamps.get("setConsumeAirAmount(I)V"), "a listener's consume clamped");
	}

	private static ClassLoader loader(Path work, Mode mode) throws Exception {
		Files.createDirectories(work);
		Map<String, byte[]> classes = new HashMap<>(InjectorExecution.compile(work, SOURCES));
		// The kernel's delivery bookkeeping goes into the hook first, as in the chain: it reports the event the hook constructs
		// between the construction and the local the event is kept in.
		byte[] raw = hook();
		byte[] original = InjectorExecution.transform(new NativeDualEventInjector(VanillaAirRefillInjectorExecutionTest::stagedResource,
				net.forbric.kernel.boot.DeadEventAudit.forwardedForgeEvents()), HOOK.replace('/', '.'), raw, EnvType.SERVER);
		assertNotSame(raw, original, "the delivery bookkeeping instruments the hook");
		if (mode == Mode.OFF || mode == Mode.ISLAND_UNDECORATED) System.setProperty(VanillaAirRefillInjector.PROPERTY, "off");
		byte[] transformed = InjectorExecution.transform(new VanillaAirRefillInjector(), HOOK.replace('/', '.'), original, EnvType.SERVER);
		System.clearProperty(VanillaAirRefillInjector.PROPERTY);
		if (mode == Mode.OFF || mode == Mode.ISLAND_UNDECORATED) assertSame(original, transformed);
		else assertNotSame(original, transformed);
		if (mode == Mode.ISLAND || mode == Mode.ISLAND_UNDECORATED) {
			// Planned from the actual fabric-api drowning modifier on this very hook, then put into it, as the chain does.
			CrossHostIslandFixture.reset();
			CrossHostIslandFixture.planOn(transformed);
			byte[] islanded = InjectorExecution.transform(new CrossHostPredicateIslandInjector(), HOOK.replace('/', '.'), transformed, EnvType.SERVER);
			assertNotSame(transformed, islanded, "the island is put into the hook");
			transformed = islanded;
		}
		assertTrue(mentionsBookkeeping(transformed), "the hook still carries the delivery bookkeeping");
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

	private static boolean mentionsBookkeeping(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		MethodNode hook = node.methods.stream().filter(m -> m.name.equals("onLivingBreathe")).findFirst().orElseThrow();
		return DeliveryBookkeeping.mentions(hook);
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
