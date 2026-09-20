/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package forbric.live.neoforge;

import java.util.concurrent.atomic.AtomicInteger;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Forbric tri-in-one B-5 canary (NeoForge side): a genuine NeoForge {@code @Mod} that counts NeoForge's OWN
 * {@code ServerTickEvent.Post} on the game bus. Staged alongside the traditional-Forge canary (forbriclive) on the
 * MERGED base, it proves BOTH ecosystems' server-tick listeners fire — NeoForge natively (its lifecycle owns
 * {@code MinecraftServer.tickServer}'s hook) and Forge via the Neo->Forge event bridge — and, since each counts one
 * increment per game tick, that the bridge does NOT double-fire (both reach "20 ticks" at the same game tick, not
 * 40). Compiled at assemble time against the NeoForge runtime; never redistributed.
 */
@Mod("forbricneolive")
public class ForbricNeoLiveMod {
	private static final AtomicInteger TICKS = new AtomicInteger();

	public ForbricNeoLiveMod(IEventBus modBus) {
		System.out.println("[ForbricNeoLive] @Mod(\"forbricneolive\") constructed by the real NeoForge ModLoader");
		reportForeignMods();
		registerStartupConfig();
		registerSetupLifecycle(modBus);
		NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.server.ServerStartedEvent.class, event -> {
			// D1 twin: did this canary's neoforge:add_features biome modifier reach the live biome?
			try {
				var biomes = event.getServer().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);
				var plains = biomes.getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS).value();
				var steps = plains.getGenerationSettings().features();
				int ores = net.minecraft.world.level.levelgen.GenerationStep.Decoration.UNDERGROUND_ORES.ordinal();
				var features = steps.size() > ores ? steps.get(ores) : net.minecraft.core.HolderSet.<net.minecraft.world.level.levelgen.placement.PlacedFeature>empty();
				boolean present = false;
				for (var holder : features) {
					if (holder.unwrapKey().map(k -> k.identifier().toString()).orElse("").equals("forbricneolive:probe")) present = true;
				}
				System.out.println("[ForbricNeoLive/WORLDGEN] probe ran: plains has " + features.size() + " feature(s) in underground_ores");
				System.out.println("[ForbricNeoLive/WORLDGEN] plains underground_ores has forbricneolive:probe = " + present);
			} catch (Throwable failure) {
				System.out.println("[ForbricNeoLive/WORLDGEN] probe FAILED: " + failure);
			}
			// The Neo->Forge block-break bridge, driven from the one side that can name the NeoForge event.
			//
			// ServerPlayerGameMode posts exactly this event and branches on isCanceled() afterwards, so posting it
			// here reproduces the seam a MinecraftForge protection mod depends on. The Forge canary refuses it
			// (its listener writes DENY for a player-less probe), and the refusal must come back as isCanceled()
			// on THIS event — a forward that observes but does not carry the veto gives that mod a listener which
			// runs, decides and is ignored, which looks like it works.
			try {
				var level = event.getServer().overworld();
				var pos = new net.minecraft.core.BlockPos(0, 64, 0);
				// A real player, because MinecraftForge's BreakEvent constructor asks the player which tool it is
				// holding and NPEs on null before any listener runs. NeoForge's own fake player is what a mod
				// breaking a block on nobody's behalf uses, so it is also the honest stand-in here.
				var breaker = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
				var probe = new net.neoforged.neoforge.event.level.block.BreakBlockEvent(
						level, pos, level.getBlockState(pos), breaker);
				net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(probe);
				System.out.println("[ForbricNeoLive/BLOCKBREAK] posted BreakBlockEvent at " + pos
						+ " refused=" + probe.isCanceled());

				// The two click events, which carry a useBlock/useItem decision as well as a cancel. The Forge
				// canary refuses a different one on each, so a bridge that translated only one of the pair — or
				// translated it in the wrong direction — cannot pass both halves.
				var hit = new net.minecraft.world.phys.BlockHitResult(
						net.minecraft.world.phys.Vec3.atCenterOf(pos), net.minecraft.core.Direction.UP, pos, false);
				var right = new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(
						breaker, net.minecraft.world.InteractionHand.MAIN_HAND, pos, hit);
				net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(right);
				System.out.println("[ForbricNeoLive/INTERACT] right-click useBlock=" + right.getUseBlock().name()
						+ " useItem=" + right.getUseItem().name());

				var left = new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock(
						breaker, pos, net.minecraft.core.Direction.UP,
						net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.Action.START);
				net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(left);
				System.out.println("[ForbricNeoLive/INTERACT] left-click useBlock=" + left.getUseBlock().name()
						+ " useItem=" + left.getUseItem().name());
			} catch (Throwable failure) {
				System.out.println("[ForbricNeoLive/BLOCKBREAK] probe FAILED: " + failure);
			}
			try {
				var structures = event.getServer().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
				var mineshaft = structures.getOrThrow(net.minecraft.world.level.levelgen.structure.BuiltinStructures.MINESHAFT).value();
				var override = mineshaft.getModifiedStructureSettings().spawnOverrides().get(net.minecraft.world.entity.MobCategory.CREATURE);
				boolean present = false;
				if (override != null) {
					for (var weighted : override.spawns().unwrap()) {
						if (weighted.value().type() == net.minecraft.world.entity.EntityTypes.LLAMA) present = true;
					}
				}
				System.out.println("[ForbricNeoLive/WORLDGEN] structure probe ran: mineshaft creature override present = " + (override != null));
				System.out.println("[ForbricNeoLive/WORLDGEN] mineshaft creature override has minecraft:llama = " + present);
			} catch (Throwable failure) {
				System.out.println("[ForbricNeoLive/WORLDGEN] structure probe FAILED: " + failure);
			}
		});
		NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
			int n = TICKS.incrementAndGet();
			if (n == 20) {
				System.out.println("[ForbricNeoLive] 20 server ticks observed (NeoForge native) - the merged game loop "
						+ "posts NeoForge's ServerTickEvent to NeoForge mods");
				reportActiveContainerFallback();
			}
		});
	}

	/**
	 * Registers a STARTUP config and reads it back immediately.
	 *
	 * <p>The kernel used to ask the config tracker to load STARTUP configs during its early pass, on top of the
	 * tracker already opening each one the moment it is registered — so every STARTUP config opened twice, fired
	 * its Loading event twice and stacked a second file watcher. The second ask was removed, and this is what
	 * proves nothing was lost: reading a value out of a spec that was never opened throws, so an answer here means
	 * the config really did load, by the path that was always doing the work.
	 */
	private static void registerStartupConfig() {
		try {
			net.neoforged.neoforge.common.ModConfigSpec.Builder builder =
					new net.neoforged.neoforge.common.ModConfigSpec.Builder();
			net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<String> value =
					builder.define("startupProbe", "startup-default");
			net.neoforged.neoforge.common.ModConfigSpec spec = builder.build();

			net.neoforged.fml.ModLoadingContext.get().getActiveContainer()
					.registerConfig(net.neoforged.fml.config.ModConfig.Type.STARTUP, spec);

			System.out.println("[ForbricNeoLive] STARTUP config loaded, startupProbe=" + value.get());
		} catch (Throwable t) {
			System.out.println("[ForbricNeoLive] STARTUP config FAILED: " + t);
		}
	}

	/**
	 * {@code ModLoadingContext.getActiveContainer()} from a GAME-bus listener, where no container is active.
	 *
	 * <p>That fallback path ends in {@code getModContainerById("minecraft").orElseThrow()}, and the kernel never
	 * published a "minecraft" container — so a mod registering an extension point outside a kernel-wrapped window
	 * got NeoForge's own "Where is minecraft???!" thrown at it. Asked from the mod constructor this proves
	 * nothing: the kernel sets the active container there. It has to be asked from here.
	 */
	private static void reportActiveContainerFallback() {
		try {
			net.neoforged.fml.ModContainer active = net.neoforged.fml.ModLoadingContext.get().getActiveContainer();
			System.out.println("[ForbricNeoLive] getActiveContainer() with none active answered " + active.getModId());
		} catch (Throwable t) {
			System.out.println("[ForbricNeoLive] getActiveContainer() with none active FAILED: " + t);
		}
	}

	/**
	 * The same setup lifecycle the traditional-Forge canary subscribes to, on this family's bus shape.
	 *
	 * <p>Its job in the gate is to be the control the Forge half never had: the kernel used to post these phases
	 * to NeoForge mods ONLY, so asserting the Forge lines alone cannot tell "both families now get it" apart from
	 * "the delivery moved from one family to the other". Both canaries print the same two lines per phase, and the
	 * gate asserts both sets from one boot.
	 */
	private void registerSetupLifecycle(IEventBus modBus) {
		phase(modBus, "common setup", net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent.class);
		phase(modBus, "dedicated server setup",
				net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent.class);
		phase(modBus, "IMC enqueue", net.neoforged.fml.event.lifecycle.InterModEnqueueEvent.class);
		phase(modBus, "IMC process", net.neoforged.fml.event.lifecycle.InterModProcessEvent.class);
		phase(modBus, "load complete", net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent.class);
		try {
			phase(modBus, "client setup", net.neoforged.fml.event.lifecycle.FMLClientSetupEvent.class);
		} catch (Throwable serverOnly) {
			System.out.println("[ForbricNeoLive/SETUP] client setup not observable here: " + serverOnly);
		}
	}

	/** Subscribes one phase and prints the delivered line, plus a deferred line only the work queue can print. */
	private static <T extends net.neoforged.fml.event.lifecycle.ParallelDispatchEvent> void phase(
			IEventBus modBus, String label, Class<T> type) {
		modBus.addListener(type, event -> {
			System.out.println("[ForbricNeoLive/SETUP] " + label + " DELIVERED to a NeoForge mod");
			event.enqueueWork(() -> System.out.println(
					"[ForbricNeoLive/SETUP] " + label + " DEFERRED work ran"));
		});
	}

	/**
	 * Whether the Fabric mod running in this same instance is visible through the two presence checks a NeoForge
	 * mod makes: ModList.isLoaded, and the LoadingModList lookup multi-platform mods use for a version probe
	 * (Physics Mod reads exactly the second one to decide whether Sodium is present). Answered per-ecosystem,
	 * both said no while the mod was right there, and the compatibility branch went the wrong way in silence.
	 */
	private static void reportForeignMods() {
		boolean modList = false;
		boolean modFile = false;
		try {
			modList = net.neoforged.fml.ModList.get().isLoaded("forbricfabriclive");
		} catch (Throwable notYet) {
			System.out.println("[ForbricNeoLive] ModList not available yet: " + notYet);
		}
		try {
			modFile = net.neoforged.fml.loading.FMLLoader.getCurrent().getLoadingModList()
					.getModFileById("forbricfabriclive") != null;
		} catch (Throwable absent) {
			System.out.println("[ForbricNeoLive] LoadingModList lookup unavailable: " + absent);
		}
		System.out.println("[ForbricNeoLive] foreign forbricfabriclive isLoaded=" + modList + " modFile=" + modFile);
		reportOwnModFile();
		reportOwnCodeSource();
	}

	/**
	 * {@code getProtectionDomain().getCodeSource()} — how a mod finds the jar it was loaded from when it ships
	 * data beside its own classes. JourneyMap and spark both read it, and Sodium's startup checks do too. The
	 * kernel defined every class with no protection domain at all, so it answered null and the mod NPE'd on its
	 * own line.
	 */
	private static void reportOwnCodeSource() {
		try {
			java.security.CodeSource source = ForbricNeoLiveMod.class.getProtectionDomain().getCodeSource();
			if (source == null || source.getLocation() == null) {
				System.out.println("[ForbricNeoLive] own code source is NULL");
				return;
			}
			java.io.File jar = new java.io.File(source.getLocation().toURI());
			System.out.println("[ForbricNeoLive] own code source resolves to a real file: " + jar.getName()
					+ " exists=" + jar.isFile());
		} catch (Throwable t) {
			System.out.println("[ForbricNeoLive] own code source FAILED: " + t);
		}
	}

	/**
	 * The lookup a mod makes about ITSELF: ModList.get().getModFileById(MODID).getFile(). It reads a map the
	 * kernel used to fill for the NeoForge baseline alone, so every kernel-loaded mod got null back and the very
	 * next dereference NPE'd — with nothing in the log to say why.
	 */
	private static void reportOwnModFile() {
		try {
			net.neoforged.neoforgespi.language.IModFileInfo info =
					net.neoforged.fml.ModList.get().getModFileById("forbricneolive");
			if (info == null) {
				System.out.println("[ForbricNeoLive] getModFileById(self) returned NULL");
				return;
			}
			System.out.println("[ForbricNeoLive] getModFileById(self) answered, file=" + info.getFile().getFileName()
					+ " id=" + info.getFile().getId() + " type=" + info.getFile().getType());
			java.util.List<? extends net.neoforged.neoforgespi.language.IModInfo> mine = info.getMods();
			if (mine.isEmpty()) {
				System.out.println("[ForbricNeoLive] own metadata: the file reports NO mods");
				return;
			}
			System.out.println("[ForbricNeoLive] own metadata: name=" + mine.get(0).getDisplayName()
					+ " version=" + mine.get(0).getVersion());
		} catch (Throwable t) {
			System.out.println("[ForbricNeoLive] getModFileById(self) FAILED: " + t);
		}
	}
}
