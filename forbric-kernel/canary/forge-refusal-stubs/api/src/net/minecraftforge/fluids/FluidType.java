package net.minecraftforge.fluids;

/** Compile-only: MinecraftForge's fluid type and the builder calls the fixtures make. Never shipped. */
public class FluidType {
	public FluidType(Properties properties) {
		throw new UnsupportedOperationException();
	}

	public static final class Properties {
		private Properties() {
		}

		public static Properties create() { throw new UnsupportedOperationException(); }
		public Properties descriptionId(String descriptionId) { throw new UnsupportedOperationException(); }
		public Properties canSwim(boolean canSwim) { throw new UnsupportedOperationException(); }
		public Properties canDrown(boolean canDrown) { throw new UnsupportedOperationException(); }
	}
}
