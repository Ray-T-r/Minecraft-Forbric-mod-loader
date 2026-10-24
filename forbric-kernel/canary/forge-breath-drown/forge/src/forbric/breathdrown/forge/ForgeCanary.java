package forbric.breathdrown.forge;

import java.util.function.Predicate;
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
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingBreatheEvent;
import net.minecraftforge.event.entity.living.LivingDrownEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.ForgeFlowingFluid;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * gate-m57's MinecraftForge mod, using MinecraftForge's API only: a drowning liquid of its own (MinecraftForge fluid
 * type, a {@code LiquidBlock} built from a supplier, a bucket built from a supplier), and listeners for breathing,
 * drowning and the damage chain, registered with {@code BUS.addListener} here and with {@code @SubscribeEvent} in
 * {@link ForgeAnnotated}. Each reports what it saw to the probe and acts on the body's {@code f-*} tags.
 */
@Mod(ForgeCanary.ID)
public final class ForgeCanary {
	static final String ID = "forbricbreathforge";
	static final String FAMILY = "forge";
	static final DeferredRegister<FluidType> TYPES = DeferredRegister.create(ForgeRegistries.Keys.FLUID_TYPES, ID);
	static final DeferredRegister<Fluid> FLUIDS = DeferredRegister.create(Registries.FLUID, ID);
	static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, ID);
	static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, ID);

	@SuppressWarnings("unchecked")
	private static void murk() {
		Supplier<FluidType> type = TYPES.register("murk", () -> new FluidType(FluidType.Properties.create().canDrown(true).canSwim(true).descriptionId("block." + ID + ".murk")));
		Supplier<Fluid>[] source = new Supplier[1], flowing = new Supplier[1];
		Supplier<LiquidBlock>[] block = new Supplier[1];
		Supplier<Item>[] bucket = new Supplier[1];
		ForgeFlowingFluid.Properties props = new ForgeFlowingFluid.Properties(type, () -> source[0].get(), () -> flowing[0].get())
				.block(() -> block[0].get()).bucket(() -> bucket[0].get());
		source[0] = FLUIDS.register("murk", () -> new ForgeFlowingFluid.Source(props));
		flowing[0] = FLUIDS.register("flowing_murk", () -> new ForgeFlowingFluid.Flowing(props));
		// MinecraftForge's own constructors: the block and the bucket hold a supplier, not the fluid.
		block[0] = BLOCKS.register("murk", () -> new LiquidBlock(() -> (FlowingFluid) source[0].get(), BlockBehaviour.Properties.of().noCollision().strength(100f).noLootTable()
				.setId(ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(ID, "murk")))));
		bucket[0] = ITEMS.register("murk_bucket", () -> new BucketItem(() -> source[0].get(), new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1)
				.setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(ID, "murk_bucket")))));
	}

	static {
		murk();
	}

	public ForgeCanary(FMLJavaModLoadingContext context) {
		TYPES.register(context.getModBusGroup());
		FLUIDS.register(context.getModBusGroup());
		BLOCKS.register(context.getModBusGroup());
		ITEMS.register(context.getModBusGroup());
		TickEvent.ServerTickEvent.Post.BUS.addListener(e -> Core.tick(e.server()));
		LivingBreatheEvent.BUS.addListener(ForgeCanary::breathe);
		LivingDrownEvent.BUS.addListener((Predicate<LivingDrownEvent>) ForgeCanary::drown);
		LivingAttackEvent.BUS.addListener((Predicate<LivingAttackEvent>) ForgeCanary::attack);
		LivingHurtEvent.BUS.addListener((Predicate<LivingHurtEvent>) ForgeCanary::hurt);
	}

	static void breathe(LivingBreatheEvent e) {
		LivingEntity entity = e.getEntity();
		String seen = "forge can=" + e.canBreathe() + " canRefill=" + e.canRefillAir() + " consume=" + e.getConsumeAirAmount() + " refill=" + e.getRefillAirAmount();
		if (Core.tagged(entity, "f-refill2")) e.setRefillAirAmount(2);
		if (Core.tagged(entity, "f-cant")) { e.setCanBreathe(false); e.setConsumeAirAmount(3); }
		if (Core.tagged(entity, "f-can")) { e.setCanBreathe(true); e.setCanRefillAir(true); e.setRefillAirAmount(3); }
		Core.report(FAMILY, "breathe", entity, seen + " -> can=" + e.canBreathe() + " canRefill=" + e.canRefillAir() + " consume=" + e.getConsumeAirAmount() + " refill=" + e.getRefillAirAmount());
	}

	/** Returning true cancels: EventBus 7 keeps the cancel in what {@code post} returns, not on the event. */
	static boolean drown(LivingDrownEvent e) {
		LivingEntity entity = e.getEntity();
		String seen = "forge drown drowning=" + e.isDrowning() + " damage=" + e.getDamageAmount() + " bubbles=" + e.getBubbleCount() + " air=" + entity.getAirSupply();
		boolean cancel = Core.tagged(entity, "f-cancel");
		if (Core.tagged(entity, "f-notdrowning")) e.setDrowning(false);
		if (Core.tagged(entity, "f-damage3")) e.setDamageAmount(3f);
		if (Core.tagged(entity, "f-damage0")) e.setDamageAmount(0f);
		Core.report(FAMILY, "drown", entity, seen + " -> cancel=" + cancel + " drowning=" + e.isDrowning() + " damage=" + e.getDamageAmount());
		return cancel;
	}

	static boolean attack(LivingAttackEvent e) {
		Core.report(FAMILY, "attack", e.getEntity(), "forge attack amount=" + e.getAmount() + " source=" + e.getSource().getMsgId());
		return false;
	}

	static boolean hurt(LivingHurtEvent e) {
		LivingEntity entity = e.getEntity();
		String seen = "forge hurt amount=" + e.getAmount() + " source=" + e.getSource().getMsgId();
		if (Core.tagged(entity, "f-hurt-halve")) e.setAmount(e.getAmount() / 2);
		Core.report(FAMILY, "hurt", entity, seen + " -> amount=" + e.getAmount());
		return false;
	}
}
