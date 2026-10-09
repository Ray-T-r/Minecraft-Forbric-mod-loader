package net.minecraft.world.entity.player;

import net.minecraft.world.item.Item;

/** A stand-in player: a name, whether it may build, and the stat vanilla awards after an item interaction. */
public class Player {
	private final String name;
	private final Abilities abilities = new Abilities();

	public Player(String name, boolean mayBuild) {
		this.name = name;
		this.abilities.mayBuild = mayBuild;
	}

	public String name() {
		return name;
	}

	public Abilities getAbilities() {
		return abilities;
	}

	public void used(Item item) {
	}
}
