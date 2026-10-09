package fixture.effectclearveto;

import java.util.List;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import org.example.effects.Guards;

/**
 * Adds three effects on a server-side entity, then clears them: the mod's question keeps one, NeoForge's own listeners
 * keep another. Reports which effects the mod was asked about and what is left.
 */
public class Probe {
	public String run() {
		LivingEntity entity = new LivingEntity(new Level(false));
		for (String name : List.of("speed", "mod_kept", "neo_kept")) {
			entity.forceAddEffect(new MobEffectInstance(new Holder<>(new MobEffect(name))), null);
		}
		boolean removed = entity.removeAllEffects();
		return "asked=" + Guards.ASKED.stream().sorted().toList() + " removed=" + removed + " left=" + entity.effectNames();
	}
}
