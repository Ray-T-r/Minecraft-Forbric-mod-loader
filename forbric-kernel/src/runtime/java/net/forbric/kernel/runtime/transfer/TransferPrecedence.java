package net.forbric.kernel.runtime.transfer;

import java.util.List;

import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;

/**
 * Which foreign provider answers a block query, decided by the ecosystem that OWNS the block entity (the mod that
 * registered its type), never by the registration order of generic fallbacks. Fabric API registers a generic
 * fallback that wraps ANY Container in a writable ContainerStorage, and it always runs before the bridge's own.
 * Asked first, it answered for NeoForge machines: their own capability and its face decisions were never consulted,
 * and simulate/abort went through the mod's Container.setItem.
 *
 * <p>Fabric mods do rely on that fallback, and vanilla containers are its purpose, so it still speaks for block
 * entities Fabric owns and for vanilla/unknown ones. For a NeoForge owner the bridge asks only Fabric's explicit
 * providers (one registered for the block, or a SidedStorageBlockEntity).
 *
 * <p>Pure: BlockTransferBridge asks {@link #answer} for every foreign query, and the transfer tests drive the same
 * function through their own Site, off-game.
 */
public final class TransferPrecedence {
	private TransferPrecedence() { }
	public enum Source { NEOFORGE, FABRIC }
	/** FABRIC answers through Fabric's whole lookup, FABRIC_EXPLICIT only through what Fabric has for exactly this block. */
	public enum Answer { NEOFORGE, FABRIC, FABRIC_EXPLICIT }
	/** What one query (one kind, one face) finds in each ecosystem. The bridge's endpoint asks the loaded world. */
	public interface Site {
		Ecosystem owner();
		/** NeoForge's capability answers. */
		boolean neo();
		/** Fabric answers: through its whole lookup when generic, otherwise only through its providers for this block. */
		boolean fabric(boolean generic);
	}

	/** The mod catalogue's ecosystem for the namespace that registered a block entity type; null for vanilla or unknown. */
	public static Ecosystem ownerOf(String namespace, List<ModCatalog.Entry> mods) {
		if (namespace == null || namespace.equals("minecraft")) return null;
		for (ModCatalog.Entry mod : mods) if (mod.modId().equals(namespace)) return mod.ecosystem();
		return null;
	}
	/** Whether Fabric's generic fallbacks (its Container wrapper above all) may speak for this block entity. */
	public static boolean fabricGenericAllowed(Ecosystem owner) { return owner == null || owner == Ecosystem.FABRIC; }
	/**
	 * A Fabric consumer reaches a NeoForge owner BEFORE Fabric's generic fallbacks, which would otherwise answer for any
	 * Container; every other block entity is bridged after them, as before.
	 */
	public static boolean fabricAsksBeforeGeneric(Ecosystem owner) { return owner == Ecosystem.NEOFORGE; }
	/** The foreign source a consumer tries once its own ecosystem found nothing. */
	public static List<Source> order(Ecosystem consumer, Ecosystem owner) {
		return switch (consumer) {
			case FABRIC -> List.of(Source.NEOFORGE);
			case NEOFORGE -> List.of(Source.FABRIC);
		};
	}
	/** The first foreign source that answers a consumer whose own ecosystem found nothing; null if none. */
	public static Answer answer(Ecosystem consumer, Site site) {
		Ecosystem owner = site.owner();
		boolean generic = fabricGenericAllowed(owner);
		for (Source source : order(consumer, owner)) {
			switch (source) {
				case NEOFORGE -> { if (site.neo()) return Answer.NEOFORGE; }
				case FABRIC -> { if (site.fabric(generic)) return generic ? Answer.FABRIC : Answer.FABRIC_EXPLICIT; }
			}
		}
		return null;
	}
}
