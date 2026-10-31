package net.minecraftforge.event.entity.living;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.eventbus.api.bus.CancellableEventBus;

/** Compile-only: MinecraftForge's drowning event, as the fixtures read and change it. Never shipped. */
public final class LivingDrownEvent {
	public static final CancellableEventBus<LivingDrownEvent> BUS = null;

	private LivingDrownEvent() {
	}

	public LivingEntity getEntity() { throw new UnsupportedOperationException(); }
	public boolean isDrowning() { throw new UnsupportedOperationException(); }
	public void setDrowning(boolean drowning) { throw new UnsupportedOperationException(); }
	public float getDamageAmount() { throw new UnsupportedOperationException(); }
	public void setDamageAmount(float amount) { throw new UnsupportedOperationException(); }
	public int getBubbleCount() { throw new UnsupportedOperationException(); }
	public void setBubbleCount(int count) { throw new UnsupportedOperationException(); }
}
