package org.example.crypt.mixin;

import java.util.Iterator;
import java.util.List;

import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureEntityInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Crypts keep a skeleton and never a pig. A {@code @Redirect} of the entity iterator inside vanilla's placeEntities that
 * takes the level the way Mixin appends a target argument after the redirected call's receiver — no {@code @Local} —
 * selected by its bare name, its point written with a dotted owner and whitespace.
 */
@Mixin(StructureTemplate.class)
public class CryptMixin {
	@Redirect(method = "placeEntities", at = @At(value = "INVOKE", target = "java.util.List.iterator ()Ljava/util/Iterator;"))
	private Iterator<StructureEntityInfo> crypt$skeleton(List<StructureEntityInfo> entities, ServerLevelAccessor level) {
		level.addFreshEntity("skeleton");
		return entities.stream().filter(info -> !info.name().equals("pig")).toList().iterator();
	}
}
