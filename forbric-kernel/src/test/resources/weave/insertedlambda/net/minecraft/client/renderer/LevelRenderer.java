package net.minecraft.client.renderer;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4fc;

/**
 * Fixture stand-in for NeoForge's renderer: its lambda$addMainPass$0 takes a pose between the state and the sections,
 * which vanilla's (the test's native reference) does not.
 */
public class LevelRenderer {
	public final List<String> trace = new ArrayList<>();

	public void addMainPass(LevelRenderState state, ChunkSectionsToRender sections) {
		lambda$addMainPass$0(state, new Matrix4fc("pose"), sections, 0.25F);
	}

	private void lambda$addMainPass$0(LevelRenderState state, Matrix4fc pose, ChunkSectionsToRender sections, float alpha) {
		sections.renderGroup(trace, "opaque");
		sections.renderGroup(trace, "translucent");
	}
}
