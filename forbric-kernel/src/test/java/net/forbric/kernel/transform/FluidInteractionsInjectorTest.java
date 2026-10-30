/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.runtime.StagedGameClassLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * Lava and water on the merged game: on the real merged LiquidBlock and both carriers' registries, and in a real JVM,
 * where the merged {@code onPlace}/{@code neighborChanged}, NeoForge's and MinecraftForge's
 * {@code FluidInteractionRegistry} and the merged {@code FluidState} are linked against each other and handed fixture
 * fluids in a fixture level. Each registry's initializer (which needs a bootstrapped game) is cut to its map and a
 * fixture's copy of vanilla's water rule, registered through the registry's real {@code addInteraction} as its own
 * initializer registers vanilla's; a mod's rule is registered the same way afterwards. The control is the game as it
 * was: the merged LiquidBlock with MinecraftForge's {@code canInteract} neutered.
 *
 * <p>The families' own LiquidBlocks are the reference: MinecraftForge's {@code onPlace} and {@code neighborChanged}
 * ask its registry; NeoForge's {@code neighborChanged} asks its registry and its {@code onPlace} runs vanilla's rules
 * alone. Both registries walk the neighbours in vanilla's order and, at each, every rule before the next neighbour.
 */
@ResourceLock("system-properties")
class FluidInteractionsInjectorTest {
	private static final Path STAGED = TestFixtures.stagedRoot();
	private static final Path MERGED = STAGED.resolve(TestFixtures.BASE_JAR);
	private static final Path NEO_GAME = STAGED.resolve(TestFixtures.GENUINE_BASE_JAR);
	private static final Path FORGE_GAME = STAGED.resolve("forge-patched/patched-mc-forge-26.2.jar");
	private static final Path NEO_CARRIER = STAGED.resolve(TestFixtures.NEO_CARRIER_JAR);
	private static final Path FORGE_CARRIER = STAGED.resolve("forge-runtime/forge-runtime.jar");
	private static final String LIQUID = "net/minecraft/world/level/block/LiquidBlock";
	private static final String NEO = FluidInteractionsInjector.NEO_INTERNAL;
	private static final String FORGE = FluidInteractionsInjector.FORGE_INTERNAL;
	private static final String FLUID_STATE = "net/minecraft/world/level/material/FluidState";
	private static final String LEVEL = "net/minecraft/world/level/Level";
	private static final String POS = "net/minecraft/core/BlockPos";
	private static final String INTERACT = FluidInteractionsInjector.INTERACT_DESC;
	private static final String RUNTIME = FluidInteractionsInjector.RUNTIME;

	@AfterEach void reset() { System.clearProperty(FluidInteractionsInjector.PROPERTY); }

	// ------------------------------------------------------------------------------------------------ bytecode shape

	@Test void theMergedLiquidBlockIsLeftAsEachFamilyWroteItsHalf() throws Exception {
		byte[] merged = NativeCoremodParityTest.read(MERGED, LIQUID);
		assertEquals(List.of(FORGE), canInteractOwners(method(node(merged), "onPlace")), "premise: the merged onPlace is MinecraftForge's");
		assertEquals(List.of(NEO), canInteractOwners(method(node(merged), "neighborChanged")), "premise: the merged neighborChanged is NeoForge's");
		assertEquals(List.of(FORGE), canInteractOwners(method(node(NativeCoremodParityTest.read(FORGE_GAME, LIQUID)), "onPlace")),
				"premise: MinecraftForge's own onPlace asks its registry");
		ClassNode neoGame = node(NativeCoremodParityTest.read(NEO_GAME, LIQUID));
		assertEquals(List.of(), canInteractOwners(method(neoGame, "onPlace")), "premise: NeoForge's own onPlace asks no registry");
		assertTrue(Arrays.stream(method(neoGame, "onPlace").instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c
				&& c.name.equals("shouldSpreadLiquid")), "premise: NeoForge's onPlace runs vanilla's shouldSpreadLiquid");
		for (byte[] liquid : List.of(merged, NativeCoremodParityTest.read(NEO_GAME, LIQUID), NativeCoremodParityTest.read(FORGE_GAME, LIQUID))) {
			assertSame(liquid, new FluidInteractionsInjector().transform(dotted(LIQUID), liquid, null));
		}
	}

	@Test void neoForgesWalkAsksMinecraftForgeModsAtEachNeighbourBeforeMovingOn() throws Exception {
		byte[] carrier = NativeCoremodParityTest.read(NEO_CARRIER, NEO);
		MethodNode before = method(node(carrier), "canInteract", INTERACT);
		byte[] out = new FluidInteractionsInjector().transform(FluidInteractionsInjector.NEO, carrier, null);
		MethodNode canInteract = method(node(out), "canInteract", INTERACT);
		InsnList code = canInteract.instructions;
		List<MethodInsnNode> asks = calls(canInteract, RUNTIME);
		assertEquals(1, asks.size());
		MethodInsnNode ask = asks.getFirst();
		assertEquals(List.of(FluidInteractionsInjector.FORGE_LEG, FluidInteractionsInjector.FORGE_LEG_DESC), List.of(ask.name, ask.desc));
		VarInsnNode neighbour = (VarInsnNode) next(calls(canInteract, POS).stream().filter(c -> c.name.equals("relative")).findFirst().orElseThrow());
		List<AbstractInsnNode> args = List.of(previous(previous(previous(ask))), previous(previous(ask)), previous(ask));
		assertEquals(List.of(0, 1, neighbour.var), args.stream().map(i -> ((VarInsnNode) i).var).toList(), "(level, pos, the neighbour)");
		// Order: the inner walk's exit jumps to the question; the question's "no" goes on to the next neighbour.
		List<MethodInsnNode> hasNext = calls(canInteract, "java/util/Iterator").stream().filter(c -> c.name.equals("hasNext")).toList();
		JumpInsnNode exit = (JumpInsnNode) next(hasNext.get(1));
		assertEquals(Opcodes.IFEQ, exit.getOpcode());
		assertTrue(code.indexOf(neighbour) < code.indexOf(hasNext.get(1)), "the neighbour is known before NeoForge's rules are walked");
		assertSame(previous(previous(previous(ask))), next(exit.label), "NeoForge's rules ran out at this neighbour: ask about it");
		JumpInsnNode no = (JumpInsnNode) next(ask);
		assertEquals(Opcodes.IFEQ, no.getOpcode());
		assertEquals(List.of(Opcodes.ICONST_1, Opcodes.IRETURN), List.of(next(no).getOpcode(), next(next(no)).getOpcode()), "a match is handled");
		JumpInsnNode moveOn = (JumpInsnNode) next(no.label);
		assertEquals(Opcodes.GOTO, moveOn.getOpcode());
		assertSame(previous(hasNext.get(0)), next(moveOn.label), "no: on to the next neighbour, as before");
		assertTrue(code.indexOf(ask) < code.indexOf(moveOn), "asked before the walk moves on");
		assertEquals(real(before).size() + 7, real(canInteract).size(), "nothing else in NeoForge's walk changed");
		assertEquals(1, calls(canInteract, NEO + "$FluidInteraction").size(), "its own first match still returns at once");
		List<AbstractInsnNode> tail = real(canInteract).subList(real(canInteract).size() - 2, real(canInteract).size());
		assertEquals(List.of(Opcodes.ICONST_0, Opcodes.IRETURN), tail.stream().map(AbstractInsnNode::getOpcode).toList(), "no neighbour matched: false");
		new Analyzer<>(new BasicVerifier()).analyze(NEO, canInteract);
		assertSame(out, new FluidInteractionsInjector().transform(FluidInteractionsInjector.NEO, out, null), "a second pass changes nothing");
	}

	@Test void minecraftForgesInitializerHandsOverItsMapAndAddInteractionReportsIn() throws Exception {
		byte[] carrier = NativeCoremodParityTest.read(FORGE_CARRIER, FORGE);
		byte[] out = new FluidInteractionsInjector().transform(FluidInteractionsInjector.FORGE, carrier, null);
		ClassNode repaired = node(out);
		MethodNode add = repaired.methods.stream().filter(m -> m.name.equals("addInteraction")).findFirst().orElseThrow();
		assertTrue(real(add).getFirst() instanceof MethodInsnNode first && first.owner.equals(RUNTIME)
				&& first.name.equals(FluidInteractionsInjector.IN_USE) && first.desc.equals("()V"), "reports in before it adds");
		List<AbstractInsnNode> clinit = real(method(repaired, "<clinit>"));
		List<AbstractInsnNode> end = clinit.subList(clinit.size() - 3, clinit.size());
		assertTrue(end.get(0) instanceof FieldInsnNode map && map.getOpcode() == Opcodes.GETSTATIC && map.name.equals("INTERACTIONS"));
		assertTrue(end.get(1) instanceof MethodInsnNode hand && hand.owner.equals(RUNTIME) && hand.name.equals(FluidInteractionsInjector.REGISTRY)
				&& hand.desc.equals("(Ljava/util/Map;)V"), "hands over its map once vanilla's rules are in");
		assertEquals(Opcodes.RETURN, end.get(2).getOpcode());
		assertEquals(2, clinit.stream().filter(i -> i instanceof MethodInsnNode c && c.name.equals("addInteraction")).count(),
				"premise: its initializer adds vanilla's two rules, before the hand-over");
		assertEquals(opcodes(method(node(carrier), "canInteract", INTERACT)), opcodes(method(repaired, "canInteract", INTERACT)),
				"MinecraftForge's own walk is not neutered any more, and not edited");
		for (MethodNode m : repaired.methods) if (m.instructions.size() > 0) new Analyzer<>(new BasicVerifier()).analyze(FORGE, m);
		assertSame(out, new FluidInteractionsInjector().transform(FluidInteractionsInjector.FORGE, out, null));
	}

	@Test void reshapedRegistriesAreLeftAsMerged() throws Exception {
		Map<String, Consumer<MethodNode>> neo = Map.of(
				"the walk's exit tests the other way", m -> nthHasNextExit(m).setOpcode(Opcodes.IFNE),
				"something runs between the rules running out and the next neighbour",
				m -> m.instructions.insertBefore(next(nthHasNextExit(m).label), new InsnNode(Opcodes.NOP)),
				"the neighbour is computed twice", m -> {
					MethodInsnNode relative = calls(m, POS).stream().filter(c -> c.name.equals("relative")).findFirst().orElseThrow();
					m.instructions.insertBefore(relative, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, POS, "relative", relative.desc, false));
				},
				"a match does not return at once", m -> {
					MethodInsnNode interact = calls(m, NEO + "$FluidInteraction").getFirst();
					m.instructions.insert(interact, new InsnNode(Opcodes.NOP));
				});
		for (var reshape : neo.entrySet()) {
			byte[] bytes = edited(NativeCoremodParityTest.read(NEO_CARRIER, NEO), "canInteract", INTERACT, reshape.getValue(), ClassReader.EXPAND_FRAMES);
			assertSame(bytes, new FluidInteractionsInjector().transform(FluidInteractionsInjector.NEO, bytes, null), reshape.getKey());
		}
		Map<String, Consumer<MethodNode>> forge = Map.of(
				"its initializer returns twice", m -> m.instructions.insert(new InsnNode(Opcodes.RETURN)),
				"its initializer stores no map", m -> {
					for (AbstractInsnNode i : m.instructions) if (i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTSTATIC) f.name = "OTHER";
				});
		for (var reshape : forge.entrySet()) {
			byte[] bytes = edited(NativeCoremodParityTest.read(FORGE_CARRIER, FORGE), "<clinit>", "()V", reshape.getValue(), 0);
			assertSame(bytes, new FluidInteractionsInjector().transform(FluidInteractionsInjector.FORGE, bytes, null), reshape.getKey());
		}
	}

	@Test void theSwitchLeavesBothRegistriesAlone() throws Exception {
		System.setProperty(FluidInteractionsInjector.PROPERTY, "off");
		for (String[] target : List.of(new String[] {NEO, NEO_CARRIER.toString()}, new String[] {FORGE, FORGE_CARRIER.toString()})) {
			byte[] bytes = NativeCoremodParityTest.read(Path.of(target[1]), target[0]);
			assertSame(bytes, new FluidInteractionsInjector().transform(dotted(target[0]), bytes, null), target[0]);
		}
	}

	// ------------------------------------------------------------------------------------------------ in a real JVM

	@Test void asMergedPlacingLavaNextToWaterNeverReachesAnInteraction() throws Exception {
		try (World merged = new World(false)) {
			merged.at("east", "water");
			merged.onPlace();
			assertEquals(List.of("tick"), merged.events, "control: onPlace asked MinecraftForge's neutered registry, found nothing and let the lava flow");
			merged.events.clear();
			merged.neighborChanged();
			assertEquals(List.of("neo:vanilla"), merged.events, "only water arriving next to lava reacted");
		}
	}

	@Test void placingLavaNextToWaterRunsMinecraftForgesVanillaRuleOnceAsOnMinecraftForge() throws Exception {
		try (World repaired = new World(true)) {
			repaired.at("east", "water");
			repaired.onPlace();
			assertEquals(List.of("forge:vanilla"), repaired.events, "MinecraftForge's own placement: its copy of vanilla's rule, and no fluid tick");
			assertEquals(0, repaired.asked("neo:vanilla"), "NeoForge's registry is not asked on placement, as on NeoForge");
			int byPlacement = repaired.asked("forge:vanilla");
			repaired.events.clear();
			repaired.neighborChanged();
			assertEquals(List.of("neo:vanilla"), repaired.events, "NeoForge's own neighbour change: its copy");
			assertEquals(byPlacement, repaired.asked("forge:vanilla"), "MinecraftForge's copy is never asked by the neighbour change");
		}
	}

	@Test void aNeighbourChangeAloneNeverLoadsMinecraftForgesRegistry() throws Exception {
		try (World repaired = new World(true)) {
			repaired.at("east", "stone");
			repaired.neighborChanged();
			repaired.at("east", "water");
			repaired.neighborChanged();
			assertEquals(List.of("tick", "neo:vanilla"), repaired.events);
			assertFalse(repaired.loaded(FORGE), "no MinecraftForge mod used its registry, so a neighbour change never even loaded it");
		}
	}

	@Test void untilAMinecraftForgeModAddsARuleANeighbourChangeNeverAsksIt() throws Exception {
		try (World repaired = new World(true)) {
			repaired.at("east", "stone");
			repaired.onPlace();   // placement initializes MinecraftForge's registry, as on MinecraftForge
			assertTrue(repaired.loaded(FORGE), "premise: placement asked MinecraftForge's registry");
			repaired.forgeTypeAsked(0);
			repaired.neighborChanged();
			repaired.neighborChanged();
			assertEquals(List.of("tick", "tick", "tick"), repaired.events);
			assertEquals(0, repaired.forgeTypeAsked(0), "its copies of vanilla's rules alone: the neighbour change asked it nothing");
			repaired.forgeRule("honey", repaired.neighbourIs("honey"));
			repaired.neighborChanged();
			assertTrue(repaired.forgeTypeAsked(0) > 0, "control: once a MinecraftForge mod added a rule, it is asked");
		}
	}

	@Test void aMinecraftForgeModsInteractionRunsFromBothEntryPoints() throws Exception {
		try (World repaired = new World(true)) {
			repaired.forgeRule("honey", repaired.neighbourIs("honey"));
			repaired.at("east", "honey");
			repaired.onPlace();
			assertEquals(List.of("forge:honey"), repaired.events, "placement: MinecraftForge's own walk");
			repaired.events.clear();
			repaired.neighborChanged();
			assertEquals(List.of("forge:honey"), repaired.events, "a neighbour change: after NeoForge's rules missed that neighbour");
		}
		try (World merged = new World(false)) {
			merged.forgeRule("honey", merged.neighbourIs("honey"));
			merged.at("east", "honey");
			merged.onPlace();
			merged.neighborChanged();
			assertEquals(List.of("tick", "tick"), merged.events, "control: as merged it ran from neither");
		}
	}

	@Test void aNeoForgeModsInteractionRunsOnANeighbourChangeOnlyAsOnNeoForge() throws Exception {
		try (World repaired = new World(true)) {
			repaired.neoRule("honey", repaired.neighbourIs("honey"));
			repaired.at("east", "honey");
			repaired.onPlace();
			assertEquals(List.of("tick"), repaired.events, "NeoForge's own onPlace runs vanilla's rules alone, never a mod's");
			repaired.events.clear();
			repaired.neighborChanged();
			assertEquals(List.of("neo:honey"), repaired.events);
		}
	}

	@Test void aModsRuleAtAnEarlierNeighbourBeatsVanillasAtALaterOneAsInItsOwnFamily() throws Exception {
		// Above is the first neighbour both registries walk, east the fifth.
		try (World repaired = new World(true)) {
			repaired.forgeRule("honey", repaired.neighbourIs("honey"));
			repaired.at("above", "honey");
			repaired.at("east", "water");
			repaired.onPlace();
			repaired.neighborChanged();
			assertEquals(List.of("forge:honey", "forge:honey"), repaired.events, "as MinecraftForge's walk: the mod's rule above, before vanilla's east");
		}
		try (World repaired = new World(true)) {
			repaired.neoRule("honey", repaired.neighbourIs("honey"));
			repaired.at("above", "honey");
			repaired.at("east", "water");
			repaired.neighborChanged();
			assertEquals(List.of("neo:honey"), repaired.events, "NeoForge's own order is unchanged");
		}
		try (World merged = new World(false)) {
			merged.forgeRule("honey", merged.neighbourIs("honey"));
			merged.at("above", "honey");
			merged.at("east", "water");
			merged.neighborChanged();
			assertEquals(List.of("neo:vanilla"), merged.events, "control: as merged, vanilla's rule east won");
		}
	}

	@Test void oneLiquidNeverReactsTwice() throws Exception {
		try (World repaired = new World(true)) {
			repaired.neoRule("honey", repaired.neighbourIs("honey"));
			repaired.forgeRule("honey", repaired.neighbourIs("honey"));
			repaired.at("east", "honey");
			repaired.onPlace();
			repaired.neighborChanged();
			assertEquals(List.of("forge:honey", "neo:honey"), repaired.events, "each entry point runs its own family's first match, once");
			repaired.events.clear();
			repaired.at("east", "water");
			repaired.onPlace();
			repaired.neighborChanged();
			assertEquals(List.of("forge:vanilla", "neo:vanilla"), repaired.events);
		}
	}

	@Test void minecraftForgesCopiesOfVanillasRulesAreNeverAskedAfterNeoForgesOwn() throws Exception {
		try (World repaired = new World(true)) {
			repaired.forgeRule("honey", repaired.neighbourIs("honey"));
			repaired.at("east", "stone");
			repaired.neighborChanged();
			assertEquals(List.of("tick"), repaired.events);
			assertEquals(5, repaired.asked("forge:honey"), "the mod's rule, at each of the five neighbours");
			assertEquals(0, repaired.asked("forge:vanilla"), "MinecraftForge's copy of vanilla's rule, at none: NeoForge's just was");
			assertEquals(5, repaired.asked("neo:vanilla"));
		}
	}

	@Test void aMinecraftForgeRuleThatFailsToLinkIsLeftOutOfNeighbourChangesOnce() throws Exception {
		try (World repaired = new World(true)) {
			int[] asked = {0};
			repaired.forgeRule("honey", (level, current, relative, state) -> {
				asked[0]++;
				throw new NoClassDefFoundError("net/minecraftforge/SomethingTheMergeLacks");
			});
			repaired.at("east", "honey");
			repaired.neighborChanged();
			repaired.neighborChanged();
			assertEquals(List.of("tick", "tick"), repaired.events, "the lava flows as NeoForge decided, both times");
			assertEquals(1, asked[0], "asked once, then left out");
			repaired.at("east", "water");
			repaired.events.clear();
			repaired.neighborChanged();
			assertEquals(List.of("neo:vanilla"), repaired.events, "vanilla's and NeoForge mods' interactions are untouched");
		}
	}

	/** {@code (level, current, relative, state)}, as both families' HasFluidInteraction take it. */
	@FunctionalInterface
	private interface Rule {
		boolean test(Object level, Object current, Object relative, Object state);
	}

	/**
	 * The merged game around one lava source at (0, 64, 0) and the liquids next to it: LiquidBlock as merged, both
	 * registries (MinecraftForge's neutered as KernelBoot did, or both repaired), the merged FluidState and Level, and the
	 * compiled game side (KernelFluidInteractions). Every fluid is a fixture answering both families' getFluidType().
	 * Each registry's initializer adds its family's copy of vanilla's water rule ("neo:vanilla", "forge:vanilla").
	 */
	private static final class World implements AutoCloseable {
		final List<Object> events = new ArrayList<>();
		private final Map<String, Integer> asked = new HashMap<>();
		private final Loader loader;
		private final Object level, liquid, lavaState, pos;
		private final Class<?> posClass, fluidClass;
		private final Map<String, Object> neoTypes = new HashMap<>(), forgeTypes = new HashMap<>(), states = new HashMap<>();
		private final Map<Object, Object> fluids = new HashMap<>();

		World(boolean repaired) throws Exception {
			Map<String, byte[]> defined = new HashMap<>();
			FluidInteractionsInjector injector = new FluidInteractionsInjector();
			byte[] neo = initializerOnly(NativeCoremodParityTest.read(NEO_CARRIER, NEO), NEO, "neo");
			byte[] forge = initializerOnly(NativeCoremodParityTest.read(FORGE_CARRIER, FORGE), FORGE, "forge");
			if (repaired) {
				neo = injector.transform(FluidInteractionsInjector.NEO, neo, null);
				forge = injector.transform(FluidInteractionsInjector.FORGE, forge, null);
			} else {
				// What KernelBoot registered before the repair, and registers again with the repair off.
				forge = new MethodBodyNeuter().add(new MethodBodyNeuter.Target(FluidInteractionsInjector.FORGE, "canInteract", INTERACT, "control"))
						.transform(FluidInteractionsInjector.FORGE, forge, null);
			}
			defined.put(dotted(LIQUID), flowDirectionsOnly(NativeCoremodParityTest.read(MERGED, LIQUID)));
			defined.put(dotted(NEO), neo);
			defined.put(dotted(FORGE), forge);
			for (String name : List.of("net/minecraft/world/level/block/Block", "net/minecraft/world/level/block/state/BlockBehaviour",
					"net/minecraft/world/level/block/state/BlockState", "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase",
					"net/minecraft/world/level/block/state/StateHolder", FLUID_STATE, "net/minecraft/world/level/material/Fluid",
					"net/minecraft/world/level/material/FlowingFluid", LEVEL)) {
				defined.put(dotted(name), withoutInitializer(NativeCoremodParityTest.read(MERGED, name)));
			}
			defined.put("net.neoforged.neoforge.attachment.AttachmentHolder",
					withoutInitializer(NativeCoremodParityTest.read(NEO_CARRIER, "net/neoforged/neoforge/attachment/AttachmentHolder")));
			defined.put("net.neoforged.neoforge.fluids.FluidType",
					withoutInitializer(NativeCoremodParityTest.read(NEO_CARRIER, "net/neoforged/neoforge/fluids/FluidType")));
			defined.put("net.minecraftforge.fluids.FluidType",
					withoutInitializer(NativeCoremodParityTest.read(FORGE_CARRIER, "net/minecraftforge/fluids/FluidType")));
			defined.put("fixture.TestLevel", testLevel());
			defined.put("fixture.TestFluid", testFluid());
			defined.put("fixture.NoTags", noTags());
			defined.put("fixture.Initializers", initializers());
			loader = new Loader(defined);

			Class<?> testLevel = loader.loadClass("fixture.TestLevel");
			testLevel.getField("FLUIDS").set(null, fluids);
			testLevel.getField("EVENTS").set(null, events);
			level = unsafe().allocateInstance(testLevel);
			fluidClass = loader.loadClass("fixture.TestFluid");
			fluidClass.getField("NO_TAGS").set(null, unsafe().allocateInstance(loader.loadClass("fixture.NoTags")));
			for (String name : List.of("lava", "water", "honey", "stone")) fluid(name, name.equals("lava") || name.equals("water"));
			testLevel.getField("EMPTY").set(null, states.get("stone"));
			Class<?> initializers = loader.loadClass("fixture.Initializers");
			initializers.getField("NEO").set(null, (Runnable) () -> rule(NEO, neoTypes.get("lava"), "neo:vanilla", neighbourIs("water")));
			initializers.getField("FORGE").set(null, (Runnable) () -> rule(FORGE, forgeTypes.get("lava"), "forge:vanilla", neighbourIs("water")));
			posClass = loader.loadClass(dotted(POS));
			pos = posClass.getConstructor(int.class, int.class, int.class).newInstance(0, 64, 0);
			lavaState = states.get("lava");
			fluids.put(pos, lavaState);
			Class<?> liquidClass = loader.loadClass(dotted(LIQUID));
			liquid = unsafe().allocateInstance(liquidClass);
			field(liquidClass, "fluid").set(liquid, field(lavaState.getClass(), "owner").get(lavaState));
		}

		/** A fixture fluid {@code name}, with a NeoForge and a MinecraftForge type of its own, and its one state. */
		private void fluid(String name, boolean source) throws Exception {
			Object neoType = unsafe().allocateInstance(loader.loadClass("net.neoforged.neoforge.fluids.FluidType"));
			Object forgeType = unsafe().allocateInstance(loader.loadClass("net.minecraftforge.fluids.FluidType"));
			Object fluid = unsafe().allocateInstance(fluidClass);
			fluidClass.getField("name").set(fluid, name);
			fluidClass.getField("neoType").set(fluid, neoType);
			fluidClass.getField("forgeType").set(fluid, forgeType);
			fluidClass.getField("source").setBoolean(fluid, source);
			Object state = unsafe().allocateInstance(loader.loadClass(dotted(FLUID_STATE)));
			field(state.getClass(), "owner").set(state, fluid);
			neoTypes.put(name, neoType);
			forgeTypes.put(name, forgeType);
			states.put(name, state);
		}

		/** {@code liquid} at the lava's {@code side}: "above", "east", … — a BlockPos method name. */
		void at(String side, String liquid) throws Exception {
			fluids.put(posClass.getMethod(side).invoke(pos), states.get(liquid));
		}

		/** Whether the liquid at {@code relative} is {@code name}, read the way the registries read it: its fluid state. */
		Rule neighbourIs(String name) {
			return (level, current, relative, state) -> fluids.get(relative) == states.get(name);
		}

		/** A NeoForge mod's interaction for lava next to {@code name}, through NeoForge's real addInteraction. */
		void neoRule(String name, Rule rule) throws Exception {
			register(NEO, neoTypes.get("lava"), "neo:" + name, rule);
		}

		/** A MinecraftForge mod's interaction for lava next to {@code name}, through MinecraftForge's real addInteraction. */
		void forgeRule(String name, Rule rule) throws Exception {
			register(FORGE, forgeTypes.get("lava"), "forge:" + name, rule);
		}

		/** How many times the rule recording {@code event} was asked. */
		int asked(String event) {
			return asked.getOrDefault(event, 0);
		}

		/** How many times a fixture fluid answered MinecraftForge's getFluidType() since the last call; resets to {@code to}. */
		int forgeTypeAsked(int to) throws Exception {
			int was = fluidClass.getField("FORGE_TYPE_ASKED").getInt(null);
			fluidClass.getField("FORGE_TYPE_ASKED").setInt(null, to);
			return was;
		}

		private void rule(String registry, Object lavaType, String event, Rule rule) {
			try {
				register(registry, lavaType, event, rule);
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		}

		private void register(String registry, Object lavaType, String event, Rule rule) throws Exception {
			Class<?> owner = loader.loadClass(dotted(registry));
			Class<?> predicate = loader.loadClass(dotted(registry) + "$HasFluidInteraction");
			Class<?> interaction = loader.loadClass(dotted(registry) + "$FluidInteraction");
			Class<?> information = loader.loadClass(dotted(registry) + "$InteractionInformation");
			Object test = Proxy.newProxyInstance(loader, new Class<?>[] {predicate}, (proxy, method, args) -> switch (method.getName()) {
				case "test" -> {
					asked.merge(event, 1, Integer::sum);
					yield rule.test(args[0], args[1], args[2], args[3]);
				}
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == args[0];
				default -> event;
			});
			Object react = Proxy.newProxyInstance(loader, new Class<?>[] {interaction}, (proxy, method, args) -> {
				if (method.getName().equals("interact")) events.add(event);
				return method.getName().equals("hashCode") ? System.identityHashCode(proxy) : method.getName().equals("equals") ? proxy == args[0] : null;
			});
			Object info = information.getConstructor(predicate, interaction).newInstance(test, react);
			try {
				owner.getMethod("addInteraction", lavaType.getClass(), information).invoke(null, lavaType, info);
			} catch (InvocationTargetException thrown) {
				throw new AssertionError(thrown.getCause());
			}
		}

		void onPlace() throws Exception {
			invoke("onPlace", lavaBlockState(), level, pos, lavaBlockState(), false);
		}

		void neighborChanged() throws Exception {
			invoke("neighborChanged", lavaBlockState(), level, pos, null, null, false);
		}

		private void invoke(String name, Object... args) throws Exception {
			Method method = Arrays.stream(liquid.getClass().getDeclaredMethods()).filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
			method.setAccessible(true);
			try {
				method.invoke(liquid, args);
			} catch (InvocationTargetException thrown) {
				if (thrown.getCause() instanceof Exception e) throw e;
				throw new AssertionError(thrown.getCause());
			}
		}

		/** The lava source's block state: all onPlace reads of it is its fluid state. */
		private Object lavaBlockState() throws Exception {
			Object state = unsafe().allocateInstance(loader.loadClass("net.minecraft.world.level.block.state.BlockState"));
			field(state.getClass(), "fluidState").set(state, lavaState);
			return state;
		}

		boolean loaded(String internalName) {
			return loader.loaded(dotted(internalName));
		}

		@Override public void close() throws Exception { loader.close(); }
	}

	/** The compiled game side, the staged game and its libraries, with some classes defined from given bytes. */
	private static final class Loader extends URLClassLoader {
		private final Map<String, byte[]> defined;

		Loader(Map<String, byte[]> defined) throws Exception {
			super(urls(), ClassLoader.getPlatformClassLoader());
			this.defined = defined;
		}

		private static URL[] urls() throws Exception {
			TestFixtures.require(Fixture.JAVA_25, Runtime.version().feature() >= 25, "the merged game is class-file 69, which only Java 25 links");
			for (Path jar : List.of(MERGED, NEO_CARRIER, FORGE_CARRIER)) TestFixtures.requireFile(Fixture.STAGED, jar, jar + " absent");
			List<URL> urls = new ArrayList<>(StagedGameClassLoader.urls());
			// The game side logs through ForbricLog, a boot-side class.
			urls.add(Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main").toUri().toURL());
			return urls.toArray(URL[]::new);
		}

		@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = defined.get(name);
			return bytes != null ? defineClass(name, bytes, 0, bytes.length) : super.findClass(name);
		}

		boolean loaded(String name) {
			return findLoadedClass(name) != null;
		}
	}

	// ------------------------------------------------------------------------------------------------ fixtures

	/** LiquidBlock with its initializer cut to the one static both registries read: POSSIBLE_FLOW_DIRECTIONS. */
	private static byte[] flowDirectionsOnly(byte[] bytes) {
		ClassNode node = node(bytes);
		MethodNode clinit = node.methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst().orElseThrow();
		clinit.instructions.clear();
		clinit.tryCatchBlocks.clear();
		clinit.localVariables = null;
		for (String direction : List.of("DOWN", "SOUTH", "NORTH", "EAST", "WEST")) {
			clinit.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, "net/minecraft/core/Direction", direction, "Lnet/minecraft/core/Direction;"));
		}
		String object = "Ljava/lang/Object;";
		clinit.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/google/common/collect/ImmutableList", "of",
				"(" + object.repeat(5) + ")Lcom/google/common/collect/ImmutableList;", false));
		clinit.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, LIQUID, "POSSIBLE_FLOW_DIRECTIONS", "Lcom/google/common/collect/ImmutableList;"));
		clinit.instructions.add(new InsnNode(Opcodes.RETURN));
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * A registry whose initializer creates its (empty) map and runs {@code fixture.Initializers.<family>()}, which adds the
	 * fixture's copy of vanilla's rule — in place of NeoForgeMod/ForgeMod's types, which need a bootstrapped game.
	 */
	private static byte[] initializerOnly(byte[] bytes, String owner, String family) {
		ClassNode node = node(bytes);
		MethodNode clinit = node.methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst().orElseThrow();
		clinit.instructions.clear();
		clinit.tryCatchBlocks.clear();
		clinit.localVariables = null;
		clinit.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashMap"));
		clinit.instructions.add(new InsnNode(Opcodes.DUP));
		clinit.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false));
		clinit.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, "INTERACTIONS", "Ljava/util/Map;"));
		clinit.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "fixture/Initializers", family, "()V", false));
		clinit.instructions.add(new InsnNode(Opcodes.RETURN));
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** {@code neo()} and {@code forge()} run the Runnable in the static field of that family's name. */
	private static byte[] initializers() {
		String name = "fixture/Initializers";
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		for (String family : List.of("NEO", "FORGE")) {
			cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, family, "Ljava/lang/Runnable;", null, null).visitEnd();
			MethodVisitor run = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, family.toLowerCase(), "()V", null, null);
			run.visitCode();
			run.visitFieldInsn(Opcodes.GETSTATIC, name, family, "Ljava/lang/Runnable;");
			run.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true);
			run.visitInsn(Opcodes.RETURN);
			run.visitMaxs(0, 0);
			run.visitEnd();
		}
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A Level whose fluid states come from FLUIDS (EMPTY elsewhere) and whose fluid ticks are recorded in EVENTS. */
	private static byte[] testLevel() {
		String name = "fixture/TestLevel";
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, LEVEL, null);
		for (String field : List.of("FLUIDS:Ljava/util/Map;", "EVENTS:Ljava/util/List;", "EMPTY:Ljava/lang/Object;")) {
			cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field.split(":")[0], field.split(":")[1], null, null).visitEnd();
		}
		MethodVisitor get = cw.visitMethod(Opcodes.ACC_PUBLIC, "getFluidState", "(L" + POS + ";)L" + FLUID_STATE + ";", null, null);
		get.visitCode();
		get.visitFieldInsn(Opcodes.GETSTATIC, name, "FLUIDS", "Ljava/util/Map;");
		get.visitVarInsn(Opcodes.ALOAD, 1);
		get.visitFieldInsn(Opcodes.GETSTATIC, name, "EMPTY", "Ljava/lang/Object;");
		get.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "getOrDefault", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
		get.visitTypeInsn(Opcodes.CHECKCAST, FLUID_STATE);
		get.visitInsn(Opcodes.ARETURN);
		get.visitMaxs(0, 0);
		get.visitEnd();
		MethodVisitor tick = cw.visitMethod(Opcodes.ACC_PUBLIC, "scheduleTick", "(L" + POS + ";Lnet/minecraft/world/level/material/Fluid;I)V", null, null);
		tick.visitCode();
		tick.visitFieldInsn(Opcodes.GETSTATIC, name, "EVENTS", "Ljava/util/List;");
		tick.visitLdcInsn("tick");
		tick.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
		tick.visitInsn(Opcodes.POP);
		tick.visitInsn(Opcodes.RETURN);
		tick.visitMaxs(0, 0);
		tick.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * A fluid answering both families' getFluidType() from its fields — counting MinecraftForge's answers in
	 * FORGE_TYPE_ASKED — with a tick delay and no tags.
	 */
	private static byte[] testFluid() {
		String name = "fixture/TestFluid";
		String neoType = "net/neoforged/neoforge/fluids/FluidType", forgeType = "net/minecraftforge/fluids/FluidType";
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "net/minecraft/world/level/material/FlowingFluid", null);
		for (String field : List.of("name:Ljava/lang/String;", "neoType:Ljava/lang/Object;", "forgeType:Ljava/lang/Object;", "source:Z")) {
			cw.visitField(Opcodes.ACC_PUBLIC, field.split(":")[0], field.split(":")[1], null, null).visitEnd();
		}
		cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "NO_TAGS", "Ljava/lang/Object;", null, null).visitEnd();
		cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "FORGE_TYPE_ASKED", "I", null, null).visitEnd();
		for (String[] type : List.of(new String[] {"neoType", neoType}, new String[] {"forgeType", forgeType})) {
			MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "getFluidType", "()L" + type[1] + ";", null, null);
			m.visitCode();
			if (type[1].equals(forgeType)) {
				m.visitFieldInsn(Opcodes.GETSTATIC, name, "FORGE_TYPE_ASKED", "I");
				m.visitInsn(Opcodes.ICONST_1);
				m.visitInsn(Opcodes.IADD);
				m.visitFieldInsn(Opcodes.PUTSTATIC, name, "FORGE_TYPE_ASKED", "I");
			}
			m.visitVarInsn(Opcodes.ALOAD, 0);
			m.visitFieldInsn(Opcodes.GETFIELD, name, type[0], "Ljava/lang/Object;");
			m.visitTypeInsn(Opcodes.CHECKCAST, type[1]);
			m.visitInsn(Opcodes.ARETURN);
			m.visitMaxs(0, 0);
			m.visitEnd();
		}
		MethodVisitor source = cw.visitMethod(Opcodes.ACC_PUBLIC, "isSource", "(L" + FLUID_STATE + ";)Z", null, null);
		source.visitCode();
		source.visitVarInsn(Opcodes.ALOAD, 0);
		source.visitFieldInsn(Opcodes.GETFIELD, name, "source", "Z");
		source.visitInsn(Opcodes.IRETURN);
		source.visitMaxs(0, 0);
		source.visitEnd();
		MethodVisitor delay = cw.visitMethod(Opcodes.ACC_PUBLIC, "getTickDelay", "(Lnet/minecraft/world/level/LevelReader;)I", null, null);
		delay.visitCode();
		delay.visitIntInsn(Opcodes.BIPUSH, 30);
		delay.visitInsn(Opcodes.IRETURN);
		delay.visitMaxs(0, 0);
		delay.visitEnd();
		MethodVisitor holder = cw.visitMethod(Opcodes.ACC_PUBLIC, "builtInRegistryHolder", "()Lnet/minecraft/core/Holder$Reference;", null, null);
		holder.visitCode();
		holder.visitFieldInsn(Opcodes.GETSTATIC, name, "NO_TAGS", "Ljava/lang/Object;");
		holder.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/core/Holder$Reference");
		holder.visitInsn(Opcodes.ARETURN);
		holder.visitMaxs(0, 0);
		holder.visitEnd();
		MethodVisitor string = cw.visitMethod(Opcodes.ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
		string.visitCode();
		string.visitVarInsn(Opcodes.ALOAD, 0);
		string.visitFieldInsn(Opcodes.GETFIELD, name, "name", "Ljava/lang/String;");
		string.visitInsn(Opcodes.ARETURN);
		string.visitMaxs(0, 0);
		string.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A registry holder in no tag: the bubble-column check onPlace makes of the placed liquid's fluid. */
	private static byte[] noTags() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "fixture/NoTags", null, "net/minecraft/core/Holder$Reference", null);
		MethodVisitor is = cw.visitMethod(Opcodes.ACC_PUBLIC, "is", "(Lnet/minecraft/tags/TagKey;)Z", null, null);
		is.visitCode();
		is.visitInsn(Opcodes.ICONST_0);
		is.visitInsn(Opcodes.IRETURN);
		is.visitMaxs(0, 0);
		is.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	// ------------------------------------------------------------------------------------------------ helpers

	/** {@code bytes} with one method edited, read with {@code flags} and written back unchanged otherwise. */
	private static byte[] edited(byte[] bytes, String name, String desc, Consumer<MethodNode> edit, int flags) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, flags);
		edit.accept(method(node, name, desc));
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** The IFEQ after the inner walk's hasNext: where NeoForge's rules at one neighbour run out. */
	private static JumpInsnNode nthHasNextExit(MethodNode method) {
		List<MethodInsnNode> hasNext = calls(method, "java/util/Iterator").stream().filter(c -> c.name.equals("hasNext")).toList();
		return (JumpInsnNode) next(hasNext.get(1));
	}

	private static List<MethodInsnNode> calls(MethodNode method, String owner) {
		List<MethodInsnNode> found = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode call && call.owner.equals(owner)) found.add(call);
		return found;
	}

	private static List<String> canInteractOwners(MethodNode method) {
		List<String> owners = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.name.equals("canInteract") && call.desc.equals(INTERACT)) owners.add(call.owner);
		}
		return owners;
	}

	private static List<Integer> opcodes(MethodNode method) {
		return real(method).stream().map(AbstractInsnNode::getOpcode).toList();
	}

	private static List<AbstractInsnNode> real(MethodNode method) {
		return Arrays.stream(method.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
	}

	private static AbstractInsnNode next(AbstractInsnNode insn) {
		AbstractInsnNode next = insn.getNext();
		while (next != null && next.getOpcode() < 0) next = next.getNext();
		return next;
	}

	private static AbstractInsnNode previous(AbstractInsnNode insn) {
		AbstractInsnNode previous = insn.getPrevious();
		while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
		return previous;
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name) {
		return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(node.name + "." + name));
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst()
				.orElseThrow(() -> new AssertionError(node.name + "." + name + desc));
	}

	private static byte[] withoutInitializer(byte[] bytes) {
		ClassNode node = node(bytes);
		node.methods.removeIf(m -> m.name.equals("<clinit>"));
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static String dotted(String internalName) {
		return internalName.replace('/', '.');
	}

	private static Field field(Class<?> owner, String name) throws Exception {
		for (Class<?> at = owner; at != null; at = at.getSuperclass()) {
			try {
				Field f = at.getDeclaredField(name);
				f.setAccessible(true);
				return f;
			} catch (NoSuchFieldException notHere) {
				// declared further up
			}
		}
		throw new NoSuchFieldException(owner.getName() + "." + name);
	}

	private static sun.misc.Unsafe unsafe() throws Exception {
		Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		f.setAccessible(true);
		return (sun.misc.Unsafe) f.get(null);
	}
}
