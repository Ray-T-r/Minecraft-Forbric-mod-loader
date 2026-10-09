package org.example.ruins.mixin;

import java.util.Iterator;
import java.util.List;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureEntityInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Not Create, and not Create's three hooks: ruins never spawn pigs. One wrap of the entity iterator inside vanilla's
 * placeEntities, selected by its bare name, with no {@code @Local}, no pickup before the call and no cleanup after it.
 */
@Mixin(StructureTemplate.class)
public class RuinsMixin {
	@WrapOperation(method = "placeEntities", at = @At(value = "INVOKE", target = "Ljava/util/List;iterator()Ljava/util/Iterator;"))
	private Iterator<StructureEntityInfo> noPigs(List<StructureEntityInfo> entities, Operation<Iterator<StructureEntityInfo>> original) {
		return original.call(entities.stream().filter(info -> !info.name().equals("pig")).toList());
	}
}
