package forbric.breathdrown.forge;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingBreatheEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDrownEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The same mod's annotation-style listeners, registered by MinecraftForge's automatic subscriber: a cancellable event's
 * listener that returns a boolean (true cancels), and a plain one. Reports under its own kinds so the probe can tell
 * them from {@link ForgeCanary}'s.
 */
@Mod.EventBusSubscriber(modid = ForgeCanary.ID)
public final class ForgeAnnotated {
	private ForgeAnnotated() {}

	@SubscribeEvent
	public static boolean onDrown(LivingDrownEvent e) {
		LivingEntity entity = e.getEntity();
		boolean cancel = Core.tagged(entity, "f-ann-cancel");
		Core.report(ForgeCanary.FAMILY, "drown-ann", entity, "forge @drown drowning=" + e.isDrowning() + " damage=" + e.getDamageAmount() + " air=" + entity.getAirSupply() + " -> cancel=" + cancel);
		return cancel;
	}

	@SubscribeEvent
	public static boolean onDamage(LivingDamageEvent e) {
		LivingEntity entity = e.getEntity();
		boolean cancel = Core.tagged(entity, "f-ann-damage-cancel");
		Core.report(ForgeCanary.FAMILY, "damage-ann", entity, "forge @damage amount=" + e.getAmount() + " source=" + e.getSource().getMsgId() + " -> cancel=" + cancel);
		return cancel;
	}

	@SubscribeEvent
	public static void onBreathe(LivingBreatheEvent e) {
		LivingEntity entity = e.getEntity();
		String seen = "forge @breathe can=" + e.canBreathe() + " canRefill=" + e.canRefillAir() + " refill=" + e.getRefillAirAmount();
		if (Core.tagged(entity, "f-ann-refill2")) e.setRefillAirAmount(2);
		Core.report(ForgeCanary.FAMILY, "breathe-ann", entity, seen + " -> refill=" + e.getRefillAirAmount());
	}
}
