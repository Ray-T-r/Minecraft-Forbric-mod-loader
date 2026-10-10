package org.example.census.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A census mod: a template's running count of the entities it added stops at one, and the level it places into hears
 * each tally. A {@code @WrapWithCondition} of the field write inside vanilla's placeEntities — the write's receiver and
 * value are the injector's own operands — that takes the level the way Mixin appends a target argument after them, no
 * {@code @Local}, and names the method by its whole descriptor.
 */
@Mixin(StructureTemplate.class)
public class HeadcountMixin {
	@WrapWithCondition(method = "placeEntities(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Mirror;Lnet/minecraft/world/level/block/Rotation;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;ZLnet/minecraft/util/ProblemReporter;)V",
			at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;added:I", opcode = Opcodes.PUTFIELD))
	private boolean census$tallyOnce(StructureTemplate template, int next, ServerLevelAccessor level) {
		level.addFreshEntity("tally " + next);
		return next <= 1;
	}
}
