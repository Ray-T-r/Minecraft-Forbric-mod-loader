package fixture.defaultconflict.mixin;

import fixture.defaultconflict.FabricItemStack;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ItemStack.class)
public abstract class ItemStackMixin implements FabricItemStack {
}
