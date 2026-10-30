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

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterPresetEditorsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/**
 * The client half of the NeoForge canary: the registration events a client mod subscribes to, and a read-back of
 * what each one changed in the game's own tables after a hundred real in-world ticks.
 *
 * <p>The MinecraftForge canary's client half (forbriclive's {@code ForbricLiveClient}) was the only fixture for
 * this contract on the kernel; every event here is that canary's, on NeoForge's bus and in NeoForge's shape, and the
 * consumer checks are the same consumer checks. Two lines per registration on purpose: "RECEIVED" proves the event
 * was posted to this mod, the observation proves the registration reached the table the game reads. A key mapping
 * registered into an event nobody drains prints the first and not the second.
 *
 * <p>Different block, key and colour from the MinecraftForge canary's, so the two can share a client without one
 * registration overwriting the other's and turning one canary's check red.
 */
public final class ForbricNeoLiveClient {
	/** A picture-in-picture state, of the shape a minimap registers. Nothing builds it; the registration is the point. */
	public record ProbePipState(int x0, int y0, int x1, int y1, float scale,
			net.minecraft.client.gui.navigation.ScreenRectangle scissorArea)
			implements net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState {
		@Override
		public net.minecraft.client.gui.navigation.ScreenRectangle bounds() {
			return net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState
					.getBounds(x0, x1, y0, y1, scissorArea);
		}
	}

	/** The renderer the canary registers for {@link ProbePipState}. */
	public static final class ProbePipRenderer
			extends net.minecraft.client.gui.render.pip.PictureInPictureRenderer<ProbePipState> {
		@Override
		public Class<ProbePipState> getRenderStateClass() {
			return ProbePipState.class;
		}

		@Override
		protected void renderToTexture(ProbePipState state, com.mojang.blaze3d.vertex.PoseStack pose,
				net.minecraft.client.renderer.SubmitNodeCollector collector) {
		}

		@Override
		protected String getTextureLabel() {
			return "forbricneolive probe";
		}
	}

	/** The tint the canary gives granite: what the read-back looks for among granite's tint sources. */
	static final int PROBE_TINT = 0x5533AA;

	private static final ModelLayerLocation PROBE_LAYER = new ModelLayerLocation(
			Identifier.fromNamespaceAndPath("forbricneolive", "probe"), "main");
	private static final AtomicInteger RELOAD_POSTS = new AtomicInteger();
	private static final AtomicInteger RELOAD_APPLIES = new AtomicInteger();
	private static final AtomicInteger LAYER_DRAWS = new AtomicInteger();
	private static KeyMapping probeKey;
	private static int worldTicks;

	private ForbricNeoLiveClient() {}

	public static void init(IEventBus modBus) {
		modBus.addListener(RegisterKeyMappingsEvent.class, event -> {
			// F8 is deliberately different from the F9 supplied by the gate's options.txt.
			probeKey = new KeyMapping("key.forbricneolive.probe", org.lwjgl.glfw.GLFW.GLFW_KEY_F8, KeyMapping.Category.MISC);
			event.register(probeKey);
			System.out.println("[ForbricNeoLive/CLIENT] RegisterKeyMappingsEvent RECEIVED");
		});
		modBus.addListener(EntityRenderersEvent.RegisterRenderers.class, event ->
				System.out.println("[ForbricNeoLive/CLIENT] EntityRenderersEvent.RegisterRenderers RECEIVED"));
		modBus.addListener(BuildCreativeModeTabContentsEvent.class, event -> {
			if (event.getTabKey().equals(net.minecraft.world.item.CreativeModeTabs.BUILDING_BLOCKS)) {
				System.out.println("[ForbricNeoLive/CLIENT] BuildCreativeModeTabContentsEvent RECEIVED: "
						+ event.getTabKey().identifier());
			}
		});
		modBus.addListener(EntityRenderersEvent.RegisterLayerDefinitions.class, event -> {
			event.registerLayerDefinition(PROBE_LAYER, () -> {
				MeshDefinition mesh = new MeshDefinition();
				mesh.getRoot().addOrReplaceChild("probe_cube", CubeListBuilder.create()
						.texOffs(0, 0).addBox(0, 0, 0, 1, 1, 1), PartPose.ZERO);
				return LayerDefinition.create(mesh, 16, 16);
			});
			System.out.println("[ForbricNeoLive/CLIENT] EntityRenderersEvent.RegisterLayerDefinitions RECEIVED");
		});
		modBus.addListener(RegisterParticleProvidersEvent.class, event ->
				System.out.println("[ForbricNeoLive/CLIENT] RegisterParticleProvidersEvent RECEIVED"));
		modBus.addListener(RegisterColorHandlersEvent.BlockTintSources.class, event -> {
			event.register(List.of(state -> PROBE_TINT), Blocks.GRANITE);
			System.out.println("[ForbricNeoLive/CLIENT] RegisterColorHandlersEvent.BlockTintSources RECEIVED");
		});
		modBus.addListener(AddClientReloadListenersEvent.class, event -> {
			RELOAD_POSTS.incrementAndGet();
			event.addListener(Identifier.fromNamespaceAndPath("forbricneolive", "probe"),
					(state, prepareExecutor, barrier, applyExecutor) ->
							barrier.wait((Void) null).thenRunAsync(RELOAD_APPLIES::incrementAndGet, applyExecutor));
			System.out.println("[ForbricNeoLive/CLIENT] AddClientReloadListenersEvent RECEIVED");
		});
		modBus.addListener(RegisterClientTooltipComponentFactoriesEvent.class, event -> {
			event.register(ProbeTooltip.class, ignored -> new ProbeClientTooltip());
			System.out.println("[ForbricNeoLive/CLIENT] RegisterClientTooltipComponentFactoriesEvent RECEIVED");
		});
		modBus.addListener(ModelEvent.RegisterLoaders.class, event -> {
			event.register(Identifier.fromNamespaceAndPath("forbricneolive", "probe"),
					(net.neoforged.neoforge.client.model.UnbakedModelLoader<net.minecraft.client.resources.model.UnbakedModel>)
							(json, context) -> null);
			System.out.println("[ForbricNeoLive/CLIENT] ModelEvent.RegisterLoaders RECEIVED");
		});
		modBus.addListener(RegisterPresetEditorsEvent.class, event ->
				System.out.println("[ForbricNeoLive/CLIENT] RegisterPresetEditorsEvent RECEIVED"));
		modBus.addListener(RegisterPictureInPictureRenderersEvent.class, event -> {
			event.register(ProbePipState.class, ProbePipRenderer::new);
			System.out.println("[ForbricNeoLive/CLIENT] RegisterPictureInPictureRenderersEvent RECEIVED");
		});
		// DRAWS, not the registration: the event arriving proves only that a layer stack exists to register into.
		modBus.addListener(RegisterGuiLayersEvent.class, event -> {
			event.registerAboveAll(Identifier.fromNamespaceAndPath("forbricneolive", "overlay"), (extractor, delta) -> {
				if (LAYER_DRAWS.incrementAndGet() == 20) {
					System.out.println("[ForbricNeoLive/CLIENT] gui layer DREW 20 frames — NeoForge's HUD layers reach the screen");
				}
			});
			System.out.println("[ForbricNeoLive/CLIENT] RegisterGuiLayersEvent RECEIVED");
		});
		NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level == null || mc.player == null) return;
			if (worldTicks == 0) {
				// Exercise the game's own builder, never the event bus: it is what posts the creative event to mods.
				net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(mc.level.enabledFeatures(), true,
						mc.level.registryAccess());
				System.out.println("[ForbricNeoLive/CLIENT] creative contents builder exercised in a live world");
			}
			if (++worldTicks == 100) observeRegistrationResults(mc);
		});
		System.out.println("[ForbricNeoLive/CLIENT] subscribed to twelve NeoForge client registration events");
	}

	/** Read consumer state after the initial resource reload and a hundred real in-world ticks. */
	private static void observeRegistrationResults(Minecraft mc) {
		// The SERVER config as the client holds it in a world: on a dedicated server's client this is the server's
		// value only if NeoForge's configuration-phase sync delivered it.
		System.out.println("[ForbricNeoLive/CFG] greeting seen on the client in a world: " + ForbricNeoLiveMod.greeting());
		System.out.println("[ForbricNeoLive/CLIENT] key in Options.keyMappings: "
				+ (probeKey != null && Arrays.asList(mc.options.keyMappings).contains(probeKey)));
		System.out.println("[ForbricNeoLive/CLIENT] key saved binding: "
				+ (probeKey == null ? "<absent>" : probeKey.saveString()));
		try {
			boolean baked = mc.getEntityModels().bakeLayer(PROBE_LAYER).getChild("probe_cube") != null;
			System.out.println("[ForbricNeoLive/CLIENT] layer forbricneolive:probe baked: " + baked);
		} catch (Throwable failure) {
			System.out.println("[ForbricNeoLive/CLIENT] layer forbricneolive:probe baked: false (" + failure + ")");
		}
		var tints = mc.getBlockColors().getTintSources(Blocks.GRANITE.defaultBlockState());
		System.out.println("[ForbricNeoLive/CLIENT] granite probe tint present: "
				+ tints.stream().anyMatch(tint -> tint.color(Blocks.GRANITE.defaultBlockState()) == PROBE_TINT));
		System.out.println("[ForbricNeoLive/CLIENT] reload posts=" + RELOAD_POSTS.get());
		System.out.println("[ForbricNeoLive/CLIENT] reload listener applies=" + RELOAD_APPLIES.get());
		try {
			System.out.println("[ForbricNeoLive/CLIENT] tooltip factory consumed: "
					+ (ClientTooltipComponent.create(new ProbeTooltip()) instanceof ProbeClientTooltip));
		} catch (Throwable failure) {
			System.out.println("[ForbricNeoLive/CLIENT] tooltip factory consumed: false (" + failure + ")");
		}
		System.out.println("[ForbricNeoLive/CLIENT] gui layer draws so far: " + LAYER_DRAWS.get());
		System.out.println("[ForbricNeoLive/CLIENT] registration observations completed at world tick 100");
	}

	private record ProbeTooltip() implements TooltipComponent {}

	private record ProbeClientTooltip() implements ClientTooltipComponent {
		@Override public int getHeight(Font font) { return 1; }
		@Override public int getWidth(Font font) { return 1; }
	}
}
