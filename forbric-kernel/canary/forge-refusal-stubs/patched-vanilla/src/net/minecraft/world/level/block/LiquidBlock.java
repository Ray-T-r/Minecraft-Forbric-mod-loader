package net.minecraft.world.level.block;

import java.util.function.Supplier;

import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.FlowingFluid;

/**
 * Compile-only: MinecraftForge's patch to vanilla's liquid block, the constructor that takes a supplier of the fluid.
 * Ahead of the game jar on the fixtures' compile classpath, so it hides NeoForge's {@code LiquidBlock}, which has no
 * such constructor; a fixture's call compiles to the descriptor MinecraftForge's patched game declares. Never shipped.
 */
public class LiquidBlock extends Block {
	public LiquidBlock(Supplier<? extends FlowingFluid> fluid, BlockBehaviour.Properties properties) {
		super(properties);
	}
}
