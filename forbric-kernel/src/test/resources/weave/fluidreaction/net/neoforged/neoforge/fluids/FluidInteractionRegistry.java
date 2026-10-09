package net.neoforged.neoforge.fluids;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Fixture stand-in with the registry's own canInteract: per flow direction the first interaction whose predicate holds
 * for that neighbour runs, and the cell is handled. Vanilla's lava-meets-water rule is registered for both lavas.
 */
public final class FluidInteractionRegistry {
	public interface FluidInteraction {
		void interact(Level level, BlockPos currentPos, BlockPos relativePos, FluidState currentState);
	}

	public interface HasFluidInteraction {
		boolean test(Level level, BlockPos currentPos, BlockPos relativePos, FluidState currentState);
	}

	public record InteractionInformation(HasFluidInteraction predicate, FluidInteraction interaction) {
	}

	private static final Map<Fluid, List<InteractionInformation>> INTERACTIONS = new HashMap<>();

	static {
		for (Fluid lava : List.of(Fluids.LAVA, Fluids.FLOWING_LAVA)) {
			INTERACTIONS.computeIfAbsent(lava, f -> new ArrayList<>()).add(new InteractionInformation(
					(level, pos, relative, state) -> level.getFluidState(relative).is(FluidTags.WATER),
					(level, pos, relative, state) -> {
						level.setBlockAndUpdate(pos, (state.isSource() ? Blocks.OBSIDIAN : Blocks.COBBLESTONE).defaultBlockState());
						level.levelEvent(1501, pos, 0);
					}));
		}
	}

	private FluidInteractionRegistry() {
	}

	public static boolean canInteract(Level level, BlockPos pos) {
		FluidState state = level.getFluidState(pos);
		for (Direction direction : LiquidBlock.POSSIBLE_FLOW_DIRECTIONS) {
			BlockPos relativePos = pos.relative(direction.getOpposite());
			List<InteractionInformation> interactions = INTERACTIONS.getOrDefault(state.getType(), Collections.emptyList());
			for (InteractionInformation interaction : interactions) {
				if (interaction.predicate().test(level, pos, relativePos, state)) {
					interaction.interaction().interact(level, pos, relativePos, state);
					return true;
				}
			}
		}
		return false;
	}
}
