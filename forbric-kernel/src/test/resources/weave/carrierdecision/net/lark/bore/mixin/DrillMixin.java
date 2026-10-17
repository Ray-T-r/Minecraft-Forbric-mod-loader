package net.lark.bore.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import fixture.carrierdecision.Trace;
import java.util.Map;
import java.util.TreeSet;
import org.quarry.pit.Bit;
import org.quarry.pit.Drill;
import org.quarry.pit.Holder;
import org.quarry.pit.Recipe;
import org.quarry.pit.Stack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A guest written against the drill's own decisions. */
@Mixin(Drill.class)
public abstract class DrillMixin {
	@Shadow public Bit bit;

	/** Keeps boring with a soft bit of the same kind whatever its wear; otherwise asks the original question. */
	@Redirect(method = "keepsBoring", at = @At(value = "INVOKE", target = "Lorg/quarry/pit/Bit;sameEdge(Lorg/quarry/pit/Bit;Lorg/quarry/pit/Bit;)Z"))
	private boolean bore$keep(Bit held, Bit current) {
		boolean same = Bit.sameEdge(held, this.bit);
		Trace.add("bore-keep " + same);
		if (!same && held.kind.equals(this.bit.kind) && held.kind.startsWith("soft")) same = true;
		return same;
	}

	@Redirect(method = "takes", at = @At(value = "INVOKE", target = "Lorg/quarry/pit/Recipe;fits(Lorg/quarry/pit/Stack;)Z"))
	private boolean bore$takes(Recipe recipe, Stack stack, Stack argument, Holder holder) {
		Trace.add("bore-takes " + recipe.name + " " + (argument == stack) + " " + (holder.value() == recipe));
		return recipe.fits(stack) || stack.name.equals("wild");
	}

	@Redirect(method = "price", at = @At(value = "INVOKE", target = "Lorg/quarry/pit/Recipe;fits(Lorg/quarry/pit/Stack;)Z"))
	private boolean bore$price(Recipe recipe, Stack stack, @Local(name = "recipe") Recipe named) {
		Trace.add("bore-price " + (named == recipe));
		return recipe.fits(stack) || stack.name.equals("wild");
	}

	@Inject(method = "feed", at = @At(value = "INVOKE", target = "Lorg/quarry/pit/Drill;canFeed(Lorg/quarry/pit/Stack;)Z", shift = At.Shift.AFTER))
	private void bore$feed(Stack stack, Object source, CallbackInfo ci) {
		Trace.add("bore-feed " + stack.name);
	}

	@Inject(method = "empty", at = @At(value = "INVOKE", target = "Lorg/quarry/pit/Bags;copy(Ljava/util/Map;)Ljava/util/Map;"), cancellable = true)
	private void bore$empty(CallbackInfoReturnable<Boolean> cir) {
		Trace.add("bore-empty " + new TreeSet<>(((Drill) (Object) this).items.keySet()));
		if (((Drill) (Object) this).items.containsKey("locked")) cir.setReturnValue(false);
	}

	@Redirect(method = "freeze", at = @At(value = "INVOKE", target = "Ljava/util/Map;copyOf(Ljava/util/Map;)Ljava/util/Map;"))
	private Map<String, String> bore$freeze(Map<String, String> loaded) {
		loaded.put("lark", "2");
		Trace.add("bore-freeze");
		return Map.copyOf(loaded);
	}

	@Redirect(method = "shine", at = @At(value = "INVOKE", target = "Lorg/quarry/pit/Bit;sameEdge(Lorg/quarry/pit/Bit;Lorg/quarry/pit/Bit;)Z"))
	private boolean bore$shine(Bit held, Bit current) {
		Trace.add("bore-shine");
		return true;
	}
}
