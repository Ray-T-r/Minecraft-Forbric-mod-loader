package net.minecraft.world.item;

import java.util.Set;

/** Fixture stand-in: vanilla shears are a ShearsItem; a stick is a plain item. */
public final class Items {
	public static final Item SHEARS = new ShearsItem("minecraft:shears", Set.of("c:tools/shear"));
	public static final Item STICK = new Item("minecraft:stick", Set.of());

	private Items() {
	}
}
