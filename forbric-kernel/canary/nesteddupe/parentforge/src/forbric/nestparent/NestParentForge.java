package forbric.nestparent;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

/**
 * The MinecraftForge consumer, the other side of the same library.
 *
 * <p>It asks whether the library is installed, because that is the question arbitration has to keep answerable.
 * When the Fabric build wins, this side's nested jar is withdrawn — and {@code aliasesFor(Ecosystem.FORGE)} has
 * no consumer, so the identity has to come back through {@code ModPresence} instead. Asserting on the arbiter's
 * own "aliased into [FORGE]" line would only prove the arbiter said something; this prints what a real mod is
 * told.
 */
@Mod("forbricnestforge")
public final class NestParentForge {
	public NestParentForge() {
		System.out.println("[ForbricNestParent] forge parent up");
		boolean visible;
		try {
			// static on this carrier — ForeignModPresenceInjector reads the same shape (idSlot 0 when static).
			visible = ModList.isLoaded("forbricnestlib");
		} catch (Throwable t) {
			visible = false;
			System.out.println("[ForbricNestParent] forge isLoaded threw: " + t);
		}
		System.out.println("[ForbricNestParent] forge sees forbricnestlib=" + visible);
	}
}
