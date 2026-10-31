package net.minecraftforge.event.entity.living;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.eventbus.api.bus.CancellableEventBus;

/** Compile-only: MinecraftForge's hurt event, as the fixtures read and change it. Never shipped. */
public final class LivingHurtEvent {
	public static final CancellableEventBus<LivingHurtEvent> BUS = null;

	private LivingHurtEvent() {
	}

	public LivingEntity getEntity() { throw new UnsupportedOperationException(); }
	public DamageSource getSource() { throw new UnsupportedOperationException(); }
	public float getAmount() { throw new UnsupportedOperationException(); }
	public void setAmount(float amount) { throw new UnsupportedOperationException(); }
}
