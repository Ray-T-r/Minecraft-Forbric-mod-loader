package net.minecraft.world.level.block.state;

import java.util.List;

public class StateDefinition {
	private final List<Object> possibleStates;

	public StateDefinition(List<Object> possibleStates) {
		this.possibleStates = possibleStates;
	}

	public List<Object> getPossibleStates() {
		return possibleStates;
	}
}
