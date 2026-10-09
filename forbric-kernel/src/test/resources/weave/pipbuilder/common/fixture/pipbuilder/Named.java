package fixture.pipbuilder;

import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;

/** A mod's picture-in-picture renderer, known by its name, for one of the mods' render states. */
public final class Named extends PictureInPictureRenderer<PictureInPictureRenderState> {
	public interface LensState extends PictureInPictureRenderState { }
	public interface FrameState extends PictureInPictureRenderState { }
	public interface PlateState extends PictureInPictureRenderState { }
	public interface DialState extends PictureInPictureRenderState { }

	private final String name;
	private final Class<? extends PictureInPictureRenderState> state;

	public Named(String name, Class<? extends PictureInPictureRenderState> state) {
		this.name = name;
		this.state = state;
	}

	@Override
	@SuppressWarnings("unchecked")
	public Class<PictureInPictureRenderState> getRenderStateClass() {
		return (Class<PictureInPictureRenderState>) (Class<?>) state;
	}

	@Override
	public String toString() {
		return name;
	}
}
