package net.minecraft.world.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.CommonHooks;

/**
 * A stand-in for the merged base's LivingEntity: its baseTick hands the whole air-supply calculation to NeoForge's
 * CommonHooks.onLivingBreathe, where vanilla's baseTick asks isEyeInFluid(WATER) and MobEffectUtil.hasWaterBreathing
 * itself.
 */
public class LivingEntity {
	private final Level level;
	private final String fluid;
	private int airSupply = 10;

	public LivingEntity(Level level, String fluid) {
		this.level = level;
		this.fluid = fluid;
	}

	public void baseTick() {
		if (this.level instanceof ServerLevel serverLevel) {
			CommonHooks.onLivingBreathe(this, serverLevel, 1, 4);
		}
	}

	public boolean isEyeInFluid(TagKey<?> tag) {
		return tag.name().equals(fluid);
	}

	public boolean isEyeInAnyFluid() {
		return !fluid.equals("air");
	}

	public boolean canBreatheUnderwater() {
		return false;
	}

	public boolean isInLava() {
		return fluid.equals("lava");
	}

	public int getAirSupply() {
		return airSupply;
	}

	public void setAirSupply(int airSupply) {
		this.airSupply = airSupply;
	}
}
