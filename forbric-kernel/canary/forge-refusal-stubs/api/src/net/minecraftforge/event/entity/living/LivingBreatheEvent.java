package net.minecraftforge.event.entity.living;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.eventbus.api.bus.EventBus;

/** Compile-only: MinecraftForge's breathing event, as the fixtures read and change it. Never shipped. */
public final class LivingBreatheEvent {
	public static final EventBus<LivingBreatheEvent> BUS = null;

	private LivingBreatheEvent() {
	}

	public LivingEntity getEntity() { throw new UnsupportedOperationException(); }
	public boolean canBreathe() { throw new UnsupportedOperationException(); }
	public void setCanBreathe(boolean canBreathe) { throw new UnsupportedOperationException(); }
	public boolean canRefillAir() { throw new UnsupportedOperationException(); }
	public void setCanRefillAir(boolean canRefillAir) { throw new UnsupportedOperationException(); }
	public int getConsumeAirAmount() { throw new UnsupportedOperationException(); }
	public void setConsumeAirAmount(int amount) { throw new UnsupportedOperationException(); }
	public int getRefillAirAmount() { throw new UnsupportedOperationException(); }
	public void setRefillAirAmount(int amount) { throw new UnsupportedOperationException(); }
}
