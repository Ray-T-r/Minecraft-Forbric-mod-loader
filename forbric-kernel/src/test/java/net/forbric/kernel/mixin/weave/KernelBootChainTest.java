package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import net.forbric.kernel.transform.HudContextQueryInjector;
import net.forbric.kernel.transform.BlockSoundQueryInjector;
import net.forbric.kernel.transform.DuplicateLambdaPruneInjector;
import net.forbric.kernel.transform.InterfaceDefaultConflictRepair;
import net.forbric.kernel.transform.ItemUseOnInjector;
import net.forbric.kernel.transform.SpawnPositionCallsInjector;
import net.forbric.kernel.transform.TransformChain;
import net.forbric.kernel.transform.TransformPhase;
import net.forbric.kernel.transform.VanillaEarlyReturns;

/**
 * The weave harness registers a chain transformer as KernelBoot does, read from KernelBoot's bytecode: these are the
 * facts a reader of KernelBoot.java sees, so a misread is a failure here rather than a scenario woven through a chain
 * no boot runs. What cannot be reproduced exactly is refused by name.
 */
class KernelBootChainTest {
	@Test void readsPhaseSortIndexAndSwitchAsKernelBootRegistersThem() throws Exception {
		KernelBootChain.Registration earlyReturns = KernelBootChain.registration(VanillaEarlyReturns.class.getName());
		assertEquals(TransformPhase.COREMOD, earlyReturns.phase());
		assertEquals(Integer.MAX_VALUE, earlyReturns.sortIndex(), "registered LAST in the coremod phase");
		assertTrue(earlyReturns.gated(), "registered only while VanillaEarlyReturns.enabled()");

		for (Class<?> plain : java.util.List.of(ItemUseOnInjector.class, DuplicateLambdaPruneInjector.class, BlockSoundQueryInjector.class)) {
			KernelBootChain.Registration r = KernelBootChain.registration(plain.getName());
			assertEquals(TransformPhase.COREMOD, r.phase(), plain.getSimpleName());
			assertEquals(0, r.sortIndex(), plain.getSimpleName());
			assertFalse(r.gated(), plain.getSimpleName() + " is registered unconditionally; its switch is inside transform");
		}
	}

	/** Within one sort index TransformChain runs registration order, so the harness must keep KernelBoot's. */
	@Test void keepsKernelBootsRegistrationOrder() throws Exception {
		long earlyReturns = KernelBootChain.registration(VanillaEarlyReturns.class.getName()).position();
		long prune = KernelBootChain.registration(DuplicateLambdaPruneInjector.class.getName()).position();
		long useOn = KernelBootChain.registration(ItemUseOnInjector.class.getName()).position();
		assertTrue(earlyReturns < prune && prune < useOn, earlyReturns + " " + prune + " " + useOn);
	}

	@Test void refusesWhatItCannotReproduceFaithfully() {
		assertRefused(HudContextQueryInjector.class, "only under a condition other than its own enabled()");
		assertRefused(SpawnPositionCallsInjector.class, "constructed with arguments");
		assertRefused(TransformChain.class, "is not registered as chain.register(PHASE, new X(...))");
		assertRefused(InterfaceDefaultConflictRepair.class, "never constructs");
	}

	private static void assertRefused(Class<?> type, String reason) {
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> KernelBootChain.registration(type.getName()), type.getSimpleName());
		assertTrue(refused.getMessage().contains(reason), refused.getMessage());
	}
}
