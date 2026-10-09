/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.access;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.forbric.api.ModCatalog;
import net.forbric.kernel.GateLogContract;

/**
 * gate-m9's access assertions, held to what {@link AccessCensus#report()} prints. They used to require
 * "replayed >= 1": true only while fabric-biome-api's featuresPerStep widener first missed and was replayed after
 * a COREMOD repair. The merged base now keeps both descriptors, the widener matches first time, and the replay
 * count is 0. The gate asserts the outcome instead, "(N AT, 0 AW)", which these runs of the real census pin down
 * from both sides.
 */
@org.junit.jupiter.api.parallel.ResourceLock("ModCatalog")
class AccessCensusGateM9ContractTest {
	private static final Path GATE = Path.of("run/gate-m9-client.sh");
	@TempDir Path root;
	private List<ModCatalog.Entry> previous;

	@BeforeEach
	void fresh() {
		previous = ModCatalog.everything();
		AccessCensus.reset();
	}

	@AfterEach
	void restore() {
		AccessCensus.reset();
		ModCatalog.publish(previous);
	}

	@Test
	void stalePlatformLinesAloneLeaveEveryWidenerMatchedAndNothingReplayed() throws Exception {
		AccessCensus.transformed();
		AccessCensus.unmatched("AT", "renamed-transformer-holder.jar", "PUBLIC unknown/Owner staleField");
		String out = report();
		assertEquals(1, grep("the access census ran", out), out);
		assertEquals(1, grep("no directive remains re-typed by an ecosystem", out), out);
		assertEquals(1, grep("every access widener reached its member", out), out);
		assertEquals(0, grep("fabric-biome-api's widener is not left unmatched", out), out);
		assertEquals(0, grep("and its mod is not marked for it", out), out);
		assertEquals(true, out.contains("replayed 0 previously unmatched"), "nothing to replay is still said: " + out);
	}

	@Test
	void aWidenerThatFoundNothingIsRedEvenWhenNothingWasReTyped() throws Exception {
		AccessCensus.transformed();
		AccessCensus.unmatched("AW", "renamed-widener-holder.jar", "accessible field unknown/Owner cells Ljava/util/function/Supplier;", false, false);
		String out = report();
		assertEquals(1, grep("no directive remains re-typed by an ecosystem", out), out);
		assertEquals(0, grep("every access widener reached its member", out), "one stale widener is enough to say so: " + out);
	}

	@Test
	void theBiomeWidenerLeftUnmatchedIsWhatTheGateNames() throws Exception {
		AccessCensus.transformed();
		AccessCensus.unmatched("AW", "fabric-biome-api-v1-16.0.0.jar",
				"accessible field net/minecraft/world/level/chunk/ChunkGenerator featuresPerStep Ljava/util/function/Supplier;",
				true, true, "Lnet/minecraftforge/common/util/ClearableLazy;");
		String out = report();
		assertEquals(0, grep("every access widener reached its member", out), out);
		assertEquals(0, grep("no directive remains re-typed by an ecosystem", out), out);
		assertEquals(1, grep("fabric-biome-api's widener is not left unmatched", out), out);
		assertEquals(1, grep("and its mod is not marked for it", out), out);
	}

	@Test
	void aRestoredMemberThatWasReplayedLeavesNoWidenerUnmatchedEither() throws Exception {
		AccessCensus.transformed();
		AccessCensus.unmatched("AW", "renamed-widener-holder.jar", "accessible field unknown/Owner cells Ljava/util/function/Supplier;", true, true);
		AccessCensus.restored("AW", "renamed-widener-holder.jar", "accessible field unknown/Owner cells Ljava/util/function/Supplier;");
		String out = report();
		assertEquals(1, grep("every access widener reached its member", out), "the outcome holds whichever path got there: " + out);
		assertEquals(true, out.contains("replayed 1 previously unmatched"), out);
	}

	private static String report() throws Exception {
		String[] out = new String[1];
		GateLogContract.capture(() -> { AccessCensus.report(); return null; }, out);
		return out[0];
	}

	private int grep(String check, String text) throws Exception {
		return GateLogContract.count(root, GateLogContract.pattern(GATE, check), text);
	}
}
