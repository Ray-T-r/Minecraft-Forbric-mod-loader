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
 * explicit providers (one registered for the block, or a SidedStorageBlockEntity).
 *
 * <p>Pure: BlockTransferBridge asks {@link #answer} for every foreign query, and the transfer tests drive the same
 * function through their own Site, off-game.
 */
public final class TransferPrecedence {
	private TransferPrecedence() { }
	public enum Source { NEOFORGE, FORGE, FABRIC }
	/**
	 * FABRIC answers through Fabric's whole lookup, FABRIC_EXPLICIT only through what Fabric has for exactly this
	 * block. NEOFORGE_CONTAINER is NeoForge's own wrapper of the whole Container a Forge owner exposes through
	 * Forge's generic InvWrapper; no Forbric bridge is involved.
	 */
	public enum Answer { NEOFORGE, FORGE, FABRIC, FABRIC_EXPLICIT, NEOFORGE_CONTAINER }
	/**
	 * What Forge's capability answered. WHOLE_CONTAINER is Forge's own generic InvWrapper (what BaseContainerBlockEntity
	 * hands out when a mod does not override the query): the whole Container, unsided, not an audited handler and
	 * not one to refuse either.
	 */
	public enum ForgeAnswer { NONE, AUDITED, WHOLE_CONTAINER }
	/** What one query (one kind, one face) finds in each ecosystem. The bridge's endpoint asks the loaded world. */
	public interface Site {
		Ecosystem owner();
		/** NeoForge's capability answers. */
		boolean neo();
		/** What Forge's capability answers. */
		ForgeAnswer forge();
		/**
		 * NeoForge's own Container wrapper can write the Container behind that InvWrapper exactly as the game would
		 * (BlockTransferBridge.vanillaWrites).
		 */
		boolean neoContainer();
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
	/** The first foreign source, in the owner's order, that answers a consumer whose own ecosystem found nothing; null if none. */
	public static Answer answer(Ecosystem consumer, Site site) { return answer(consumer, site, false); }
	/**
	 * As above, for a consumer whose own ecosystem already answered with its generic whole-Container view: Forge's
	 * InvWrapper on a BaseContainerBlockEntity. Only an owner's real capability may replace that, never Fabric's
	 * generic view of the same Container. That one is a Forbric write bridge that drops IItemHandlerModifiable,
	 * renumbers a WorldlyContainer's slots, and has every simulate write and restore the slots through setItem.
	 */
	public static Answer answer(Ecosystem consumer, Site site, boolean replacingGenericView) {
		Ecosystem owner = site.owner();
		boolean generic = fabricGenericAllowed(owner) && !replacingGenericView;
		for (Source source : order(consumer, owner)) {
			switch (source) {
				case NEOFORGE -> { if (site.neo()) return Answer.NEOFORGE; }
				case FORGE -> {
					ForgeAnswer forge = site.forge();
					if (forge == ForgeAnswer.AUDITED) return Answer.FORGE;
					// Forge's InvWrapper speaks for a Forge owner only: that owner chose "my whole Container". A NeoForge
					// consumer then gets NeoForge's own view of it. A Fabric consumer has one of its own, which answers
					// after the bridge, and a Forge consumer already holds the InvWrapper.
					if (forge == ForgeAnswer.WHOLE_CONTAINER && consumer == Ecosystem.NEOFORGE && owner == Ecosystem.FORGE
							&& site.neoContainer()) return Answer.NEOFORGE_CONTAINER;
				}
				case FABRIC -> { if (site.fabric(generic)) return generic ? Answer.FABRIC : Answer.FABRIC_EXPLICIT; }
			}
		}
		return null;
	}
}
