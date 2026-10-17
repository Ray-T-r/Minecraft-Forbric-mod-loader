package net.minecraft.client.renderer.chunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.VertexSorting;

import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Hand-written stand-in, not game code: the class a Fabric mod was compiled against (vanilla's), whose four-argument
 * {@code compile} carries the body. The weave runs read it as the native reference only.
 */
public class SectionCompiler {
	public static final class Results {
		public final List<String> quads = new ArrayList<>();
	}

	private final ModelBlockRenderer renderer = new ModelBlockRenderer();

	public Results compile(SectionPos section, RenderSectionRegion region, VertexSorting sorting, SectionBufferBuilderPack buffers) {
		Results results = new Results();
		Map<String, List<String>> startedLayers = new LinkedHashMap<>();
		BlockQuadOutput solid = quad -> startedLayers.computeIfAbsent("solid", layer -> new ArrayList<>()).add(quad);
		for (BlockPos pos : BlockPos.betweenClosed(region.first(), region.last())) {
			BlockState state = region.getBlockState(pos);
			renderer.tesselateBlock(solid, 0f, 0f, 0f, region, pos, state, state.model(), 42L);
		}
		for (List<String> layer : startedLayers.values()) results.quads.addAll(layer);
		return results;
	}
}
