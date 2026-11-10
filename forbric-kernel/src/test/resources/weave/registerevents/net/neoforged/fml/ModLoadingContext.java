package net.neoforged.fml;

/** Stand-in: the active container, as NeoForge's wrap sets it around each container's dispatch. */
public class ModLoadingContext {
	private static final ModLoadingContext INSTANCE = new ModLoadingContext();
	private ModContainer active;

	public static ModLoadingContext get() {
		return INSTANCE;
	}

	public void setActiveContainer(ModContainer container) {
		active = container;
	}

	public ModContainer getActiveContainer() {
		return active;
	}
}
