package net.minecraftforge.event.entity.living;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.eventbus.api.bus.CancellableEventBus;

/** Compile-only: MinecraftForge's attack event, as the fixtures read it. Never shipped. */
public final class LivingAttackEvent {
	public static final CancellableEventBus<LivingAttackEvent> BUS = null;

	private LivingAttackEvent() {
	}

	public LivingEntity getEntity() { throw new UnsupportedOperationException(); }
	public DamageSource getSource() { throw new UnsupportedOperationException(); }
	public float getAmount() { throw new UnsupportedOperationException(); }
}
