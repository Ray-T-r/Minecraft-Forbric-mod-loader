package net.forbric.kernel.transfer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.runtime.transfer.TransferPrecedence;
import net.forbric.kernel.runtime.transfer.TransferPrecedence.Source;

/**
 * The owner of a block entity answers first, and Fabric's generic Container fallback (which wraps ANY Container,
 * on every face, as a writable store) never speaks for a Forge or NeoForge machine. The wiring lives in
 * BlockTransferBridge and is exercised in the game by M33's crates; this pins the decision it follows.
 */
class TransferPrecedenceTest {
	private static final String GENERIC = "Fabric's generic Container view", EXPLICIT = "Fabric provider for this block";

	/** What a consumer receives, given what each source would answer. Nulls are "nothing on this face". */
	private static String resolve(Ecosystem consumer, Ecosystem owner, String forge, String neo, String fabricExplicit, String fabricGeneric) {
		for (Source source : TransferPrecedence.order(consumer, owner)) {
			String answer = switch (source) {
				case FORGE -> forge;
				case NEOFORGE -> neo;
				// Fabric's full lookup (its block providers, then its generic fallbacks) where those may speak;
				// otherwise only the providers Fabric has for exactly this block.
				case FABRIC -> TransferPrecedence.fabricGenericAllowed(owner) && fabricExplicit == null ? fabricGeneric : fabricExplicit;
			};
			if (answer != null) return answer;
		}
		return null;
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
