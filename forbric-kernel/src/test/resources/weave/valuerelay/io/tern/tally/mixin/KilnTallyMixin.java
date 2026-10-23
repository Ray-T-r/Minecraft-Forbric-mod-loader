package io.tern.tally.mixin;

import net.minecraft.fixture.kiln.Hearth;
import net.minecraft.fixture.kiln.Kiln;
import net.minecraft.fixture.kiln.Spot;
import net.minecraft.fixture.kiln.Tool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Replaces the refined value at its store: ten times it. */
@Mixin(Kiln.class)
abstract class KilnTallyMixin {
	@ModifyVariable(method = "vent", at = @At("STORE"), ordinal = 0)
	private int tally$tenfold(int value, Hearth level, Spot pos, Tool tool) {
		Kiln.LOG.add("tally " + value + " " + tool.name());
		return value * 10;
	}
}
