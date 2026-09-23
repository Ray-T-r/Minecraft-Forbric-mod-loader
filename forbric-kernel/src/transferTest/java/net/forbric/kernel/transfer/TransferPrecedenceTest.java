package net.forbric.kernel.transfer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.runtime.transfer.TransferPrecedence;
import net.forbric.kernel.runtime.transfer.TransferPrecedence.Answer;
import net.forbric.kernel.runtime.transfer.TransferPrecedence.ForgeAnswer;
import net.forbric.kernel.runtime.transfer.TransferPrecedence.Source;

/**
 * The owner of a block entity answers first, and Fabric's generic Container fallback (which wraps ANY Container,
 * on every face, as a writable store) never speaks for a Forge or NeoForge machine. BlockTransferBridge asks
 * TransferPrecedence.answer for every foreign query; these tests ask it the same way, through a Site that answers
 * from a table instead of a loaded world. M33's crates exercise the world-facing side in the game.
 */
class TransferPrecedenceTest {
	private static final String GENERIC = "Fabric's generic Container view", EXPLICIT = "Fabric provider for this block";
	/** Forge's own InvWrapper over the whole Container, and NeoForge's own wrapper of that Container. */
	private static final String WHOLE = "Forge's InvWrapper", NEO_CONTAINER = "NeoForge's Container wrapper";

	/**
	 * What a consumer receives, given what each source would answer. Nulls are "nothing on this face". Fabric's
	 * lookup is modelled as Fabric implements it (its providers for the block, then its generic fallbacks); which of
	 * the two the bridge may ask is TransferPrecedence's decision, recorded in the Site's log.
	 */
	private static String resolve(Ecosystem consumer, Ecosystem owner, String forge, String neo, String fabricExplicit, String fabricGeneric) {
		return resolve(consumer, new Table(owner, forge, neo, fabricExplicit, fabricGeneric));
	}
	private static String resolve(Ecosystem consumer, Table site) { return resolve(consumer, site, false); }
	private static String resolve(Ecosystem consumer, Table site, boolean replacingGenericView) {
		Answer answer = TransferPrecedence.answer(consumer, site, replacingGenericView);
		if (answer == null) return null;
		return switch (answer) {
			case NEOFORGE -> site.neo;
			case FORGE -> site.forge;
			case FABRIC -> site.fabricExplicit != null ? site.fabricExplicit : site.fabricGeneric;
			case FABRIC_EXPLICIT -> site.fabricExplicit;
			case NEOFORGE_CONTAINER -> NEO_CONTAINER;
		};
	}
	/** A Site that answers from a table and records every question the bridge's precedence asked it, in order. */
	static final class Table implements TransferPrecedence.Site {
		final Ecosystem owner; final String forge, neo, fabricExplicit, fabricGeneric;
		final List<String> asked = new ArrayList<>();
		/** Whether NeoForge's Container wrapper can write the Container behind a WHOLE answer as the game would. */
		boolean vanillaWrites = true;
		Table(Ecosystem owner, String forge, String neo, String fabricExplicit, String fabricGeneric) {
			this.owner = owner; this.forge = forge; this.neo = neo; this.fabricExplicit = fabricExplicit; this.fabricGeneric = fabricGeneric;
		}
		public Ecosystem owner() { return owner; }
		public boolean neo() { asked.add("neo"); return neo != null; }
		public ForgeAnswer forge() {
			asked.add("forge");
			return forge == null ? ForgeAnswer.NONE : forge.equals(WHOLE) ? ForgeAnswer.WHOLE_CONTAINER : ForgeAnswer.AUDITED;
		}
		public boolean neoContainer() { asked.add("neo-container"); return vanillaWrites; }
		public boolean fabric(boolean generic) {
			asked.add(generic ? "fabric" : "fabric-explicit");
			return fabricExplicit != null || generic && fabricGeneric != null;
		}
	}

	@Test void aNeoForgeConsumerNeverGetsFabricsGenericViewOfAForgeMachine() {
		// The Forge machine refused this face (or its handler is not an audited one): nothing, as in NeoForge.
		assertNull(resolve(Ecosystem.NEOFORGE, Ecosystem.FORGE, null, null, null, GENERIC));
		// Its own Forge capability is asked before anything Fabric has.
		assertEquals("forge", resolve(Ecosystem.NEOFORGE, Ecosystem.FORGE, "forge", null, EXPLICIT, GENERIC));
		// A NeoForge machine whose own provider declined gets no Fabric generic answer either.
		assertNull(resolve(Ecosystem.NEOFORGE, Ecosystem.NEOFORGE, null, null, null, GENERIC));
		// An explicit Fabric provider for exactly that block is still a real provider.
		assertEquals(EXPLICIT, resolve(Ecosystem.NEOFORGE, Ecosystem.FORGE, null, null, EXPLICIT, GENERIC));
	}
	@Test void aForgeConsumerNeverGetsFabricsGenericViewOfANeoForgeMachine() {
		assertNull(resolve(Ecosystem.FORGE, Ecosystem.NEOFORGE, null, null, null, GENERIC));
		assertEquals("neo", resolve(Ecosystem.FORGE, Ecosystem.NEOFORGE, null, "neo", null, GENERIC));
		// For a Fabric-owned block entity the Fabric view is the owner's, and it is asked first.
		assertEquals(GENERIC, resolve(Ecosystem.FORGE, Ecosystem.FABRIC, null, "neo", null, GENERIC));
	}
	@Test void fabricModsAndVanillaContainersKeepFabricsGenericFallback() {
		assertTrue(TransferPrecedence.fabricGenericAllowed(Ecosystem.FABRIC));
		assertTrue(TransferPrecedence.fabricGenericAllowed(null));
		assertFalse(TransferPrecedence.fabricGenericAllowed(Ecosystem.FORGE));
		assertFalse(TransferPrecedence.fabricGenericAllowed(Ecosystem.NEOFORGE));
		assertEquals(GENERIC, resolve(Ecosystem.NEOFORGE, Ecosystem.FABRIC, "forge", null, null, GENERIC));
		assertEquals(GENERIC, resolve(Ecosystem.NEOFORGE, null, "forge", null, null, GENERIC));
	}
	@Test void aFabricConsumerReachesAForeignOwnerBeforeFabricsGenericFallbacks() {
		assertTrue(TransferPrecedence.fabricAsksBeforeGeneric(Ecosystem.FORGE));
		assertTrue(TransferPrecedence.fabricAsksBeforeGeneric(Ecosystem.NEOFORGE));
		assertFalse(TransferPrecedence.fabricAsksBeforeGeneric(Ecosystem.FABRIC));
		assertFalse(TransferPrecedence.fabricAsksBeforeGeneric(null));
		assertEquals(List.of(Source.FORGE, Source.NEOFORGE), TransferPrecedence.order(Ecosystem.FABRIC, Ecosystem.FORGE));
		assertEquals(List.of(Source.NEOFORGE, Source.FORGE), TransferPrecedence.order(Ecosystem.FABRIC, Ecosystem.NEOFORGE));
		// Unowned and Fabric-owned block entities keep the order they had.
		assertEquals(List.of(Source.NEOFORGE, Source.FORGE), TransferPrecedence.order(Ecosystem.FABRIC, null));
		assertEquals(List.of(Source.NEOFORGE, Source.FABRIC), TransferPrecedence.order(Ecosystem.FORGE, null));
	}
	/** The questions the bridge asks, in order: a later source is never consulted once an earlier one answered. */
	@Test void eachConsumerAsksTheOwnersEcosystemFirstAndStopsAtTheFirstAnswer() {
		// A NeoForge consumer of a Forge machine: its Forge capability, then only Fabric's providers for the block.
		Table forgeMachine = new Table(Ecosystem.FORGE, null, null, null, GENERIC);
		assertNull(resolve(Ecosystem.NEOFORGE, forgeMachine));
		assertEquals(List.of("forge", "fabric-explicit"), forgeMachine.asked);
		// ...and of a Fabric-owned block entity: Fabric's whole lookup, which answers, so Forge is never asked.
		Table fabricChest = new Table(Ecosystem.FABRIC, "forge", null, null, GENERIC);
		assertEquals(GENERIC, resolve(Ecosystem.NEOFORGE, fabricChest));
		assertEquals(List.of("fabric"), fabricChest.asked);
		// A Fabric consumer never asks Fabric through the bridge; a Forge owner goes before NeoForge.
		Table forgeOwned = new Table(Ecosystem.FORGE, null, "neo", EXPLICIT, GENERIC);
		assertEquals("neo", resolve(Ecosystem.FABRIC, forgeOwned));
		assertEquals(List.of("forge", "neo"), forgeOwned.asked);
		Table neoOwned = new Table(Ecosystem.NEOFORGE, "forge", null, EXPLICIT, GENERIC);
		assertEquals("forge", resolve(Ecosystem.FABRIC, neoOwned));
		assertEquals(List.of("neo", "forge"), neoOwned.asked);
		// A Forge consumer: a Fabric owner's lookup first; a NeoForge owner's capability, then Fabric's providers only.
		Table fabricOwned = new Table(Ecosystem.FABRIC, null, "neo", null, GENERIC);
		assertEquals(GENERIC, resolve(Ecosystem.FORGE, fabricOwned));
		assertEquals(List.of("fabric"), fabricOwned.asked);
		Table neoMachine = new Table(Ecosystem.NEOFORGE, null, null, null, GENERIC);
		assertNull(resolve(Ecosystem.FORGE, neoMachine));
		assertEquals(List.of("neo", "fabric-explicit"), neoMachine.asked);
	}
	/**
	 * A BaseContainerBlockEntity already answers a Forge consumer with Forge's own InvWrapper over the whole
	 * Container (forgeOwnerFirst). Only the owner's real capability replaces it, never Fabric's generic view of the
	 * same Container: that would trade a native IItemHandlerModifiable for a Forbric write bridge.
	 */
	@Test void fabricsGenericViewNeverReplacesForgesOwnGenericView() {
		// A Fabric mod's barrel with no storage of its own: nothing replaces the InvWrapper.
		Table plainBarrel = new Table(Ecosystem.FABRIC, null, null, null, GENERIC);
		assertNull(resolve(Ecosystem.FORGE, plainBarrel, true));
		assertEquals(List.of("fabric-explicit", "neo"), plainBarrel.asked);
		// The Fabric owner's own storage does, and a NeoForge owner's capability does, ahead of any Fabric provider.
		assertEquals(EXPLICIT, resolve(Ecosystem.FORGE, new Table(Ecosystem.FABRIC, null, null, EXPLICIT, GENERIC), true));
		assertEquals("neo", resolve(Ecosystem.FORGE, new Table(Ecosystem.NEOFORGE, null, "neo", EXPLICIT, GENERIC), true));
		// A Fabric Container that is not a BaseContainerBlockEntity has no Forge view at all, so the owner's generic
		// view is still the only one there, as before.
		assertEquals(GENERIC, resolve(Ecosystem.FORGE, new Table(Ecosystem.FABRIC, null, null, null, GENERIC), false));
	}
	/**
	 * A Forge mod's chest that leaves getCapability alone answers every face with Forge's InvWrapper over its whole
	 * Container. That is the owner's choice, not a handler to audit: a NeoForge consumer gets NeoForge's own wrapper
	 * of the same Container, the one NeoForge uses for a vanilla chest. Before this it got nothing, and a NeoForge
	 * pipe could not move a single item out of any such chest.
	 */
	@Test void aNeoForgeConsumerGetsItsOwnContainerViewOfAForgeOwnersWholeContainer() {
		Table forgeChest = new Table(Ecosystem.FORGE, WHOLE, null, null, GENERIC);
		assertEquals(NEO_CONTAINER, resolve(Ecosystem.NEOFORGE, forgeChest));
		assertEquals(List.of("forge", "neo-container"), forgeChest.asked);
		// A Forge machine whose Container declares its own writes: nothing, and still no Fabric generic view.
		Table forgeMachine = new Table(Ecosystem.FORGE, WHOLE, null, null, GENERIC);
		forgeMachine.vanillaWrites = false;
		assertNull(resolve(Ecosystem.NEOFORGE, forgeMachine));
		assertEquals(List.of("forge", "neo-container", "fabric-explicit"), forgeMachine.asked);
		// The owner's explicit Fabric storage still answers there, if it has one.
		Table withStorage = new Table(Ecosystem.FORGE, WHOLE, null, EXPLICIT, GENERIC);
		withStorage.vanillaWrites = false;
		assertEquals(EXPLICIT, resolve(Ecosystem.NEOFORGE, withStorage));
	}
	/** The same InvWrapper, inherited by a block entity Forge does not own, speaks for nobody. */
	@Test void forgesInvWrapperSpeaksOnlyForAForgeOwnerAndOnlyToNeoForge() {
		// A NeoForge machine whose own provider declined this face: nothing, as in NeoForge.
		Table neoMachine = new Table(Ecosystem.NEOFORGE, WHOLE, null, null, GENERIC);
		assertNull(resolve(Ecosystem.NEOFORGE, neoMachine));
		assertEquals(List.of("forge", "fabric-explicit"), neoMachine.asked);
		// A Fabric consumer: Fabric's own generic view answers after the bridge, so the bridge offers nothing.
		Table forgeChest = new Table(Ecosystem.FORGE, WHOLE, null, null, GENERIC);
		assertNull(resolve(Ecosystem.FABRIC, forgeChest));
		assertEquals(List.of("forge", "neo"), forgeChest.asked);
		assertNull(resolve(Ecosystem.FABRIC, new Table(Ecosystem.NEOFORGE, WHOLE, null, null, GENERIC)));
		// A Forge consumer of a NeoForge machine never gets the NeoForge wrapper of it through the bridge.
		assertNull(resolve(Ecosystem.FORGE, new Table(Ecosystem.NEOFORGE, WHOLE, null, null, GENERIC), true));
	}
	@Test void theOwnerIsTheModThatRegisteredTheTypeNamespace() {
		var mods = List.of(entry(Ecosystem.FABRIC, "forbrictransferfabric"), entry(Ecosystem.FORGE, "forbrictransferforge"),
				entry(Ecosystem.NEOFORGE, "forbrictransferneo"));
		assertEquals(Ecosystem.FORGE, TransferPrecedence.ownerOf("forbrictransferforge", mods));
		assertEquals(Ecosystem.NEOFORGE, TransferPrecedence.ownerOf("forbrictransferneo", mods));
		assertEquals(Ecosystem.FABRIC, TransferPrecedence.ownerOf("forbrictransferfabric", mods));
		assertNull(TransferPrecedence.ownerOf("minecraft", mods));
		assertNull(TransferPrecedence.ownerOf("unknownmod", mods));
		assertNull(TransferPrecedence.ownerOf(null, mods));
	}
	private static ModCatalog.Entry entry(Ecosystem ecosystem, String id) {
		return new ModCatalog.Entry(ecosystem, id, id, "1", "", List.of(), id + ".jar", "", "");
	}
}
