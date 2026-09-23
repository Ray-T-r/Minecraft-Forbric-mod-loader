package forbric.behavior;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** One NeoForge test mod subscribes to both live game buses; it is not presented as three separate mods. */
@Mod("forbricbehaviorprobe")
public final class BehaviorCanary {
    private static final DeferredRegister<EntityType<?>> TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, "forbricbehaviorprobe");
    public static final java.util.function.Supplier<EntityType<ProbeMob>> TYPE = TYPES.register("probe_mob", () ->
            EntityType.Builder.of(ProbeMob::new, MobCategory.CREATURE).sized(0.4F, 0.6F).noLootTable()
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, WorldProbe.MOB_ID)));
    public BehaviorCanary(IEventBus bus) {
        TYPES.register(bus);
        bus.addListener(EntityAttributeCreationEvent.class, event -> event.put(TYPE.get(), Mob.createMobAttributes().build()));
        WorldProbe.installListeners();
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> WorldProbe.arm(event.getServer()));
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> WorldProbe.tick(event.getServer()));
        System.out.println("[M35Behavior] REGISTERED one NeoForge driver with Forge and Neo event listeners");
    }
}
