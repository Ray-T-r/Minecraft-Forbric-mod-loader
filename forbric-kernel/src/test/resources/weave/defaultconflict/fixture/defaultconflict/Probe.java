package fixture.defaultconflict;

import java.util.function.Supplier;

import net.minecraft.fixture.defaultconflict.CampfireRenderer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;

/**
 * Calls each contested method the way unrelated game code would. The JVM raises the conflict at the CALL, not at
 * load, so each call is caught on its own and every one of them reports what it did. The stack-typed Item calls go
 * through FabricItem because this fixture's Item, like the jar's, does not carry it until the mixin adds it.
 */
public class Probe {
	public String run() {
		return "renderer=" + attempt(() -> new CampfireRenderer().getRenderBoundingBox("campfire"))
				+ " item=" + attempt(() -> fabric(new Item(new ItemStackTemplate("bowl"))).getCraftingRemainder(new ItemStack("stew")).id())
				+ " neoforgeItem=" + attempt(() -> fabric(new NeoForgeBucketItem()).getCraftingRemainder(new ItemStack("milk")).id())
				+ " neoforgeStack=" + attempt(() -> new ItemStack("milk", new NeoForgeBucketItem()).getCraftingRemainder().id())
				+ " fabricStack=" + attempt(() -> new ItemStack("stew", new FabricBowlItem()).getCraftingRemainder().id())
				+ " frames=" + new Item(new ItemStackTemplate("bowl")).hasCraftingRemainder();
	}

	private static FabricItem fabric(Item item) {
		return (FabricItem) (Object) item;
	}

	private static String attempt(Supplier<String> call) {
		try {
			return call.get();
		} catch (IncompatibleClassChangeError conflict) {
			return conflict.getClass().getSimpleName() + "[" + conflict.getMessage() + "]";
		}
	}
}
