package forbric.live;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** Only loaded by name on the client; the dedicated-server entry point never resolves these event types. */
public final class ForbricLiveClient {
	private static boolean creativeContentsRequested;

	private ForbricLiveClient() {}

	public static void init(FMLJavaModLoadingContext ctx) {
		RegisterKeyMappingsEvent.BUS.addListener(event ->
				System.out.println("[ForbricLive/CLIENT] RegisterKeyMappingsEvent RECEIVED"));
		EntityRenderersEvent.RegisterRenderers.BUS.addListener(event ->
				System.out.println("[ForbricLive/CLIENT] EntityRenderersEvent.RegisterRenderers RECEIVED"));
		BuildCreativeModeTabContentsEvent.BUS.addListener(event ->
				System.out.println("[ForbricLive/CLIENT] BuildCreativeModeTabContentsEvent RECEIVED: "
						+ event.getTabKey().identifier()));
		// Opening a survival world does not open the creative inventory. Exercise its real builder once the
		// world's registries exist; do not post the event ourselves, which would hide the missing game hook.
		TickEvent.ClientTickEvent.Post.BUS.addListener(event -> {
			Minecraft mc = Minecraft.getInstance();
			if (creativeContentsRequested || mc.level == null || mc.player == null) return;
			creativeContentsRequested = true;
			CreativeModeTabs.tryRebuildTabContents(mc.level.enabledFeatures(), true, mc.level.registryAccess());
			System.out.println("[ForbricLive/CLIENT] creative contents builder exercised in a live world");
		});
		System.out.println("[ForbricLive/CLIENT] subscribed to three Forge registration events");
		registerConfigScreen(ctx);
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
