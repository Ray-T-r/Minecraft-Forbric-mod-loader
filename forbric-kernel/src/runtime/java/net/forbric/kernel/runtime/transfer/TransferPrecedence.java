package net.forbric.kernel.runtime.transfer;

import java.util.List;

import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;

/**
 * Which foreign provider answers a block query, decided by the ecosystem that OWNS the block entity (the mod that
 * registered its type), never by the registration order of generic fallbacks. Fabric API registers a generic
 * fallback that wraps ANY Container in a writable ContainerStorage, and it always runs before the bridge's own.
 * Asked first, it answered for Forge and NeoForge machines: their own capability, its face decisions and the
 * audited-handler rule were never consulted, and simulate/abort went through the mod's Container.setItem.
 *
 * <p>Fabric mods do rely on that fallback, and vanilla containers are its purpose, so it still speaks for block
 * entities Fabric owns and for vanilla/unknown ones. For a Forge or NeoForge owner the bridge asks only Fabric's
 * explicit providers (one registered for the block, or a SidedStorageBlockEntity). Pure: tested off-game.
 */
public final class TransferPrecedence {
	private TransferPrecedence() { }
	public enum Source { NEOFORGE, FORGE, FABRIC }

	/** The mod catalogue's ecosystem for the namespace that registered a block entity type; null for vanilla or unknown. */
	public static Ecosystem ownerOf(String namespace, List<ModCatalog.Entry> mods) {
		if (namespace == null || namespace.equals("minecraft")) return null;
		for (ModCatalog.Entry mod : mods) if (mod.modId().equals(namespace)) return mod.ecosystem();
		return null;
	}
	/** Whether Fabric's generic fallbacks (its Container wrapper above all) may speak for this block entity. */
	public static boolean fabricGenericAllowed(Ecosystem owner) { return owner == null || owner == Ecosystem.FABRIC; }
	/**
	 * A Fabric consumer reaches a Forge or NeoForge owner BEFORE Fabric's generic fallbacks, which would otherwise
	 * answer for any Container; every other block entity is bridged after them, as before.
	 */
	public static boolean fabricAsksBeforeGeneric(Ecosystem owner) { return owner == Ecosystem.FORGE || owner == Ecosystem.NEOFORGE; }
	/** The foreign sources a consumer tries, in order, once its own ecosystem found nothing. The owner goes first. */
	public static List<Source> order(Ecosystem consumer, Ecosystem owner) {
		return switch (consumer) {
			case FABRIC -> owner == Ecosystem.FORGE ? List.of(Source.FORGE, Source.NEOFORGE) : List.of(Source.NEOFORGE, Source.FORGE);
			case NEOFORGE -> fabricGenericAllowed(owner) ? List.of(Source.FABRIC, Source.FORGE) : List.of(Source.FORGE, Source.FABRIC);
			case FORGE -> owner == Ecosystem.FABRIC ? List.of(Source.FABRIC, Source.NEOFORGE) : List.of(Source.NEOFORGE, Source.FABRIC);
		};
	}
}
