package org.example.scuba;

import java.util.ArrayList;
import java.util.List;

import fixture.breathingsingle.Swimmer;
import net.minecraft.world.entity.LivingEntity;

/** The mod's gills: a swimmer breathes under water. What the mod was asked, in order. */
public final class Gills {
	public static final List<String> ASKED = new ArrayList<>();

	private Gills() {
	}

	public static boolean grown(LivingEntity entity) {
		ASKED.add("gills");
		return entity instanceof Swimmer;
	}

	public static boolean goggles(LivingEntity entity) {
		ASKED.add("goggles");
		return true;
	}
}
