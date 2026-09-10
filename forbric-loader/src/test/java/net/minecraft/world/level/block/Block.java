package net.minecraft.world.level.block;

import java.util.List;

import net.minecraft.world.level.block.state.StateDefinition;

public class Block {
	private final StateDefinition stateDefinition;

	public Block(List<Object> possibleStates) {
		this.stateDefinition = new StateDefinition(possibleStates);
	}

	public StateDefinition getStateDefinition() {
		return stateDefinition;
	}
}
