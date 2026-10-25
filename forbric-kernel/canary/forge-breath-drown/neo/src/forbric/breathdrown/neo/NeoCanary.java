package forbric.breathdrown.neo;

import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingBreatheEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDrownEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * gate-m57's NeoForge mod, using NeoForge's API only: a drowning liquid of its own and a bucket for it, listeners for
 * breathing, drowning and the damage chain at normal priority and a breathe and a drown listener at {@code LOWEST}, all
 * registered in the constructor, and a breathe and a drown listener at {@code LOW}, registered from common setup (after
 * the constructor, as a mod may). Each reports what it saw to the probe and acts on the body's {@code n-*} tags; the
 * {@code LOW} ones on {@code n-low-*} alone, the {@code LOWEST} ones on {@code n-lowest-*} alone.
 */
@Mod(NeoCanary.ID)
public final class NeoCanary {
	static final String ID = "forbricbreathneo";
	static final String FAMILY = "neo";
	static final DeferredRegister<FluidType> TYPES = DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, ID);
	static final DeferredRegister<Fluid> FLUIDS = DeferredRegister.create(Registries.FLUID, ID);
	static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, ID);
	static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, ID);

	@SuppressWarnings("unchecked")
	private static void murk() {
		Supplier<FluidType> type = TYPES.register("murk", () -> new FluidType(FluidType.Properties.create().canDrown(true).canSwim(true).descriptionId("block." + ID + ".murk")));
		Supplier<Fluid>[] source = new Supplier[1], flowing = new Supplier[1];
		Supplier<LiquidBlock>[] block = new Supplier[1];
		Supplier<Item>[] bucket = new Supplier[1];
		BaseFlowingFluid.Properties props = new BaseFlowingFluid.Properties(type, () -> source[0].get(), () -> flowing[0].get())
				.block(() -> block[0].get()).bucket(() -> bucket[0].get());
		source[0] = FLUIDS.register("murk", () -> new BaseFlowingFluid.Source(props));
		flowing[0] = FLUIDS.register("flowing_murk", () -> new BaseFlowingFluid.Flowing(props));
		block[0] = BLOCKS.register("murk", () -> new LiquidBlock((BaseFlowingFluid) source[0].get(), BlockBehaviour.Properties.of().noCollision().strength(100f).noLootTable()
				.setId(ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(ID, "murk")))));
		bucket[0] = ITEMS.register("murk_bucket", () -> new BucketItem(source[0].get(), new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1)
				.setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(ID, "murk_bucket")))));
	}

	static {
		murk();
	}

	public NeoCanary(IEventBus bus) {
		TYPES.register(bus);
		FLUIDS.register(bus);
		BLOCKS.register(bus);
		ITEMS.register(bus);
		NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> Core.tick(e.getServer()));
		NeoForge.EVENT_BUS.addListener(LivingBreatheEvent.class, NeoCanary::breathe);
		NeoForge.EVENT_BUS.addListener(LivingDrownEvent.class, NeoCanary::drown);
		NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class, NeoCanary::incoming);
		NeoForge.EVENT_BUS.addListener(LivingDamageEvent.Pre.class, NeoCanary::damage);
		NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, LivingBreatheEvent.class, NeoCanary::breatheLowest);
		NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, LivingDrownEvent.class, NeoCanary::drownLowest);
		bus.addListener(FMLCommonSetupEvent.class, e -> {
			NeoForge.EVENT_BUS.addListener(EventPriority.LOW, false, LivingBreatheEvent.class, NeoCanary::breatheLow);
			NeoForge.EVENT_BUS.addListener(EventPriority.LOW, false, LivingDrownEvent.class, NeoCanary::drownLow);
		});
	}

	static void breathe(LivingBreatheEvent e) {
		LivingEntity entity = e.getEntity();
		String seen = "neo can=" + e.canBreathe() + " consume=" + e.getConsumeAirAmount() + " refill=" + e.getRefillAirAmount();
		if (Core.tagged(entity, "n-refill0")) e.setRefillAirAmount(0);
		if (Core.tagged(entity, "n-cant")) { e.setCanBreathe(false); e.setConsumeAirAmount(3); }
		Core.report(FAMILY, "breathe", entity, seen + " -> can=" + e.canBreathe() + " consume=" + e.getConsumeAirAmount() + " refill=" + e.getRefillAirAmount());
	}

	static void drown(LivingDrownEvent e) {
		LivingEntity entity = e.getEntity();
		String seen = "neo drown drowning=" + e.isDrowning() + " damage=" + e.getDamageAmount() + " bubbles=" + e.getBubbleCount() + " air=" + entity.getAirSupply();
		if (Core.tagged(entity, "n-cancel")) e.setCanceled(true);
		if (Core.tagged(entity, "n-notdrowning")) e.setDrowning(false);
		if (Core.tagged(entity, "n-drowning")) e.setDrowning(true);
		if (Core.tagged(entity, "n-damage3")) e.setDamageAmount(3f);
		if (Core.tagged(entity, "n-bubbles0")) e.setBubbleCount(0);
		Core.report(FAMILY, "drown", entity, seen + " -> cancel=" + e.isCanceled() + " drowning=" + e.isDrowning() + " damage=" + e.getDamageAmount() + " bubbles=" + e.getBubbleCount());
	}

	static void breatheLow(LivingBreatheEvent e) {
		LivingEntity entity = e.getEntity();
		String seen = "neo-low can=" + e.canBreathe() + " consume=" + e.getConsumeAirAmount() + " refill=" + e.getRefillAirAmount();
		if (Core.tagged(entity, "n-low-refill0")) e.setRefillAirAmount(0);
		Core.report(FAMILY, "breathe-low", entity, seen + " -> can=" + e.canBreathe() + " consume=" + e.getConsumeAirAmount() + " refill=" + e.getRefillAirAmount());
	}

	static void drownLow(LivingDrownEvent e) {
		LivingEntity entity = e.getEntity();
		String seen = "neo-low drown drowning=" + e.isDrowning() + " damage=" + e.getDamageAmount() + " bubbles=" + e.getBubbleCount() + " air=" + entity.getAirSupply();
		if (Core.tagged(entity, "n-low-cancel")) e.setCanceled(true);
		if (Core.tagged(entity, "n-low-damage3")) e.setDamageAmount(3f);
		Core.report(FAMILY, "drown-low", entity, seen + " -> cancel=" + e.isCanceled() + " drowning=" + e.isDrowning() + " damage=" + e.getDamageAmount() + " bubbles=" + e.getBubbleCount());
	}

	static void breatheLowest(LivingBreatheEvent e) {
		LivingEntity entity = e.getEntity();
		String seen = "neo-lowest can=" + e.canBreathe() + " consume=" + e.getConsumeAirAmount() + " refill=" + e.getRefillAirAmount();
		if (Core.tagged(entity, "n-lowest-refill0")) e.setRefillAirAmount(0);
		Core.report(FAMILY, "breathe-lowest", entity, seen + " -> can=" + e.canBreathe() + " consume=" + e.getConsumeAirAmount() + " refill=" + e.getRefillAirAmount());
	}

	static void drownLowest(LivingDrownEvent e) {
		LivingEntity entity = e.getEntity();
		String seen = "neo-lowest drown drowning=" + e.isDrowning() + " damage=" + e.getDamageAmount() + " bubbles=" + e.getBubbleCount() + " air=" + entity.getAirSupply();
		if (Core.tagged(entity, "n-lowest-damage3")) e.setDamageAmount(3f);
		Core.report(FAMILY, "drown-lowest", entity, seen + " -> cancel=" + e.isCanceled() + " drowning=" + e.isDrowning() + " damage=" + e.getDamageAmount() + " bubbles=" + e.getBubbleCount());
	}

	static void incoming(LivingIncomingDamageEvent e) {
		LivingEntity entity = e.getEntity();
		if (Core.tagged(entity, "n-incoming-cancel")) e.setCanceled(true);
		Core.report(FAMILY, "incoming", entity, "neo incoming amount=" + e.getAmount() + " source=" + e.getSource().getMsgId() + " -> cancel=" + e.isCanceled());
	}

	static void damage(LivingDamageEvent.Pre e) {
		Core.report(FAMILY, "damage", e.getEntity(), "neo damage new=" + e.getNewDamage() + " original=" + e.getOriginalDamage() + " source=" + e.getSource().getMsgId());
	}
}
