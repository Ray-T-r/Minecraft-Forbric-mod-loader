package forbric.live;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.resources.model.geometry.UnbakedGeometry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.client.model.geometry.GeometryLoaderManager;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** Only loaded by name on the client; the dedicated-server entry point never resolves these event types. */
public final class ForbricLiveClient {
	private static final ModelLayerLocation PROBE_LAYER = new ModelLayerLocation(
			Identifier.fromNamespaceAndPath("forbriclive", "probe"), "main");
	private static final AtomicInteger RELOAD_POSTS = new AtomicInteger();
	private static final AtomicInteger RELOAD_APPLIES = new AtomicInteger();
	private static KeyMapping probeKey;
	private static boolean creativeContentsRequested;
	private static int worldTicks;

	private ForbricLiveClient() {}

	public static void init(FMLJavaModLoadingContext ctx) {
		net.minecraftforge.client.event.RegisterPresetEditorsEvent.getBus(ctx.getModBusGroup()).addListener(event ->
				System.out.println("[ForbricLive/CLIENT] RegisterPresetEditorsEvent RECEIVED"));
		RegisterKeyMappingsEvent.BUS.addListener(event -> {
			// F7 is deliberately different from the F6 supplied by the gate's options.txt.
			probeKey = new KeyMapping("key.forbriclive.probe", org.lwjgl.glfw.GLFW.GLFW_KEY_F7,
					KeyMapping.Category.MISC);
			event.register(probeKey);
			System.out.println("[ForbricLive/CLIENT] RegisterKeyMappingsEvent RECEIVED");
		});
		EntityRenderersEvent.RegisterRenderers.BUS.addListener(event ->
				System.out.println("[ForbricLive/CLIENT] EntityRenderersEvent.RegisterRenderers RECEIVED"));
		BuildCreativeModeTabContentsEvent.BUS.addListener(event ->
				System.out.println("[ForbricLive/CLIENT] BuildCreativeModeTabContentsEvent RECEIVED: "
						+ event.getTabKey().identifier()));
		EntityRenderersEvent.RegisterLayerDefinitions.BUS.addListener(event -> {
			event.registerLayerDefinition(PROBE_LAYER, () -> {
				MeshDefinition mesh = new MeshDefinition();
				mesh.getRoot().addOrReplaceChild("probe_cube", CubeListBuilder.create()
						.texOffs(0, 0).addBox(0, 0, 0, 1, 1, 1), PartPose.ZERO);
				return LayerDefinition.create(mesh, 16, 16);
			});
			System.out.println("[ForbricLive/CLIENT] EntityRenderersEvent.RegisterLayerDefinitions RECEIVED");
		});
		RegisterParticleProvidersEvent.BUS.addListener(event ->
				System.out.println("[ForbricLive/CLIENT] RegisterParticleProvidersEvent RECEIVED"));
		RegisterColorHandlersEvent.Block.BUS.addListener(event -> {
			event.register(List.of(state -> 0x33AA55), Blocks.STONE);
			System.out.println("[ForbricLive/CLIENT] RegisterColorHandlersEvent.Block RECEIVED");
		});
		RegisterClientReloadListenersEvent.BUS.addListener(event -> {
			RELOAD_POSTS.incrementAndGet();
			event.registerReloadListener((state, prepareExecutor, barrier, applyExecutor) ->
					barrier.wait((Void) null).thenRunAsync(RELOAD_APPLIES::incrementAndGet, applyExecutor));
			System.out.println("[ForbricLive/CLIENT] RegisterClientReloadListenersEvent RECEIVED");
		});
		RegisterClientTooltipComponentFactoriesEvent.BUS.addListener(event -> {
			event.register(ProbeTooltip.class, ignored -> new ProbeClientTooltip());
			System.out.println("[ForbricLive/CLIENT] RegisterClientTooltipComponentFactoriesEvent RECEIVED");
		});
		ModelEvent.RegisterGeometryLoaders.BUS.addListener(event -> {
			event.register(Identifier.fromNamespaceAndPath("forbriclive", "probe"),
					(json, context) -> UnbakedGeometry.EMPTY);
			System.out.println("[ForbricLive/CLIENT] ModelEvent.RegisterGeometryLoaders RECEIVED");
		});
		// Exercise the game's builder, never the event bus. The common listener supplies the actual item.
		TickEvent.ClientTickEvent.Post.BUS.addListener(event -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level == null || mc.player == null) return;
			if (!creativeContentsRequested) {
				creativeContentsRequested = true;
				CreativeModeTabs.tryRebuildTabContents(mc.level.enabledFeatures(), true, mc.level.registryAccess());
				System.out.println("[ForbricLive/CLIENT] creative contents builder exercised in a live world");
			}
			if (++worldTicks == 100) observeRegistrationResults(mc);
		});
		System.out.println("[ForbricLive/CLIENT] subscribed to ten Forge registration events");
		registerConfigScreen(ctx);
	}

	/** Read consumer state after the initial resource reload and a hundred real in-world ticks. */
	private static void observeRegistrationResults(Minecraft mc) {
		System.out.println("[ForbricLive/CLIENT] key in Options.keyMappings: "
				+ (probeKey != null && Arrays.asList(mc.options.keyMappings).contains(probeKey)));
		System.out.println("[ForbricLive/CLIENT] key saved binding: "
				+ (probeKey == null ? "<absent>" : probeKey.saveString()));
		try {
			boolean baked = mc.getEntityModels().bakeLayer(PROBE_LAYER).getChild("probe_cube") != null;
			System.out.println("[ForbricLive/CLIENT] layer forbriclive:probe baked: " + baked);
		} catch (Throwable failure) {
			System.out.println("[ForbricLive/CLIENT] layer forbriclive:probe baked: false (" + failure + ")");
		}
		var tints = mc.getBlockColors().getTintSources(Blocks.STONE.defaultBlockState());
		System.out.println("[ForbricLive/CLIENT] stone tint sources: " + tints.size());
		System.out.println("[ForbricLive/CLIENT] stone probe tint present: "
				+ tints.stream().anyMatch(tint -> tint.color(Blocks.STONE.defaultBlockState()) == 0x33AA55));
		System.out.println("[ForbricLive/CLIENT] reload posts=" + RELOAD_POSTS.get());
		System.out.println("[ForbricLive/CLIENT] reload listener applies=" + RELOAD_APPLIES.get());
		try {
			System.out.println("[ForbricLive/CLIENT] tooltip factory consumed: "
					+ (ClientTooltipComponent.create(new ProbeTooltip()) instanceof ProbeClientTooltip));
		} catch (Throwable failure) {
			System.out.println("[ForbricLive/CLIENT] tooltip factory consumed: false (" + failure + ")");
		}
		observeGeometryLoader(Identifier.fromNamespaceAndPath("forbriclive", "probe"));
		observeGeometryLoader(Identifier.fromNamespaceAndPath("forge", "obj"));
		ForbricLiveMod.observeCreativeContents("client tick 100");
		System.out.println("[ForbricLive/CLIENT] registration observations completed at world tick 100");
	}

	private static void observeGeometryLoader(Identifier id) {
		try {
			System.out.println("[ForbricLive/CLIENT] " + id + " geometry loader present: "
					+ (GeometryLoaderManager.get(id) != null));
		} catch (Throwable failure) {
			// The carrier leaves LOADERS null until the game's geometry registration hook has run.
			System.out.println("[ForbricLive/CLIENT] " + id + " geometry loader present: false (" + failure + ")");
		}
	}

	private record ProbeTooltip() implements TooltipComponent {}

	private record ProbeClientTooltip() implements ClientTooltipComponent {
		@Override public int getHeight(Font font) { return 1; }
		@Override public int getWidth(Font font) { return 1; }
	}

	private static void registerConfigScreen(FMLJavaModLoadingContext ctx) {
		try {
			ctx.getContainer().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
					() -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new ForbricLiveConfigScreen(parent)));
			System.out.println("[ForbricLive/CFG] registered a traditional-Forge config screen factory");
		} catch (Throwable t) {
			System.out.println("[ForbricLive/CFG] could not register a config screen factory: " + t);
		}
	}

	public static final class ForbricLiveConfigScreen extends Screen {
		private final Screen parent;

		public ForbricLiveConfigScreen(Screen parent) {
			super(Component.literal("ForbricLive config"));
			this.parent = parent;
			System.out.println("[ForbricLive/CFG] the traditional-Forge config screen was constructed");
		}

		@Override
		protected void init() {
			System.out.println("[ForbricLive/CFG] the traditional-Forge config screen opened");
		}

		@Override
		public void onClose() {
			this.minecraft.gui.setScreen(this.parent);
		}
	}
}
