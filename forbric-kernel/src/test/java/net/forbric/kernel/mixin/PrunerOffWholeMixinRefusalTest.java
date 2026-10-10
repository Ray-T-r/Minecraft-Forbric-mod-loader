package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.transform.GuestInjectorPruner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;

/**
 * The kill switch {@code -Dforbric.guestInjectorPruner=off} must never leave a mixin half-applied. Main pinned
 * fabric-model-loading-api's {@code ModelManagerMixin} out whole by name when the pruner was off, because loading it
 * without the pruner lets its {@code fromStream} redirect fail while its argument callback still consumes the Reader:
 * 4666 missingno block models. The name pin is gone; the source-protocol refusal has to reach the same verdict for
 * the real mixin against the real merged {@code ModelManager}, and has to leave the mixin in while the pruner is on.
 */
class PrunerOffWholeMixinRefusalTest {
	private static final String MIXIN = "net/fabricmc/fabric/mixin/client/model/loading/ModelManagerMixin";

	@AfterEach
	void clear() {
		System.clearProperty(GuestInjectorPruner.PROPERTY);
	}

	@Test
	void theRealModelPairIsLeftOutWholeOnlyWhileThePrunerIsOff() throws Exception {
		ClassNode mixin = StagedFabricMixinFixture.mixin("fabric-model-loading-api-v1", MIXIN);
		var merged = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(merged), "actual merged base required");
		try (ZipFile game = new ZipFile(merged.toFile())) {
			Function<String, byte[]> resources = name -> {
				ZipEntry entry = game.getEntry(name);
				if (entry == null) return null;
				try (var in = game.getInputStream(entry)) {
					return in.readAllBytes();
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			};
			assertNull(MergedBaseMixinCompat.refusal(mixin, resources, (ecosystem, owner) -> null),
					"with the pruner on, the pruner trims the pair and the rest of the mixin stays in");
			System.setProperty(GuestInjectorPruner.PROPERTY, "off");
			assertNotNull(MergedBaseMixinCompat.refusal(mixin, resources, (ecosystem, owner) -> null),
					"with the pruner off, the real mixin must be left out whole, not half-applied");
		}
	}
}
