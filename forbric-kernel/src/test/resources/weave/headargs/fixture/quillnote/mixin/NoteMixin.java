package fixture.quillnote.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import fixture.quillnote.Nudge;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;

/**
 * Written against vanilla's one {@code tooltip}: the head callback records x and y and fires the guest's position event,
 * and two argument modifiers at the same head hand the method the moved position.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class NoteMixin {
	@Inject(method = "tooltip", at = @At("HEAD"), cancellable = true)
	private void quill$record(Font font, List<?> lines, int x, int y, ClientTooltipPositioner positioner, Identifier style, CallbackInfo ci) {
		int[] at = Nudge.AT.get();
		at[0] = x;
		at[1] = y;
		Nudge.fire(at);
	}

	@ModifyVariable(method = "tooltip", at = @At("HEAD"), ordinal = 0, argsOnly = true)
	private int quill$x(int x) {
		return Nudge.AT.get()[0];
	}

	@ModifyVariable(method = "tooltip", at = @At("HEAD"), ordinal = 1, argsOnly = true)
	private int quill$y(int y) {
		return Nudge.AT.get()[1];
	}
}
