package net.forbric.installer.kernel;

import java.util.List;

/**
 * An NFRT bump must change the game base's coordinate.
 *
 * <p>The base is {@code net.forbric:patched-mc-neoforge:<mc>-<neoforge>} ({@link Installer#artifactVersion}), and its
 * bytes are what NFRT writes: a different {@link Pins#NFRT} (whose own jar digest is in NFRT's cache key) or
 * {@link Pins#NFRT_RESULT} is entitled to produce different bytes. The coordinate does not name NFRT, so moving that
 * pin alone would rewrite a published coordinate's bytes in place — a second profile, or an install interrupted
 * between staging and writing the profile, would then name a jar whose SHA-1 changed under it.
 *
 * <p>{@link #RECORDED} is every NFRT build the base has been built with, and the coordinate version it was published
 * under. The current pins must be a row of it, and no two rows may share a version: bumping NFRT means adding a row,
 * and a row whose version is already taken fails here until NeoForge moves with it (or the NFRT build goes into the
 * coordinate).
 */
public final class BaseCoordinatePinTest {

	/** NFRT build, NFRT result, the base coordinate's version: one row per NFRT build the base was published from. */
	private static final List<List<String>> RECORDED = List.of(
			List.of("2.0.18", "gameJarNoRecomp", "26.2-26.2.0.93"));

	public static void main(String[] args) {
		String version = Installer.artifactVersion(ArtifactBuilder.BASE, Pins.MINECRAFT);
		require(version.equals(Pins.MINECRAFT + "-" + Pins.NEOFORGE), "the base's coordinate is not <mc>-<neoforge>: " + version);
		List<String> current = List.of(Pins.NFRT, Pins.NFRT_RESULT, version);
		require(RECORDED.contains(current), "Pins.NFRT/NFRT_RESULT and the base coordinate are " + current
				+ ", which BaseCoordinatePinTest.RECORDED does not list: record the row (and read the next check)");
		for (List<String> row : RECORDED) {
			for (List<String> other : RECORDED) {
				require(row == other || !row.get(2).equals(other.get(2)), "NFRT " + row.get(0) + "/" + row.get(1) + " and "
						+ other.get(0) + "/" + other.get(1) + " both publish net.forbric:patched-mc-neoforge:" + row.get(2)
						+ ": an NFRT bump rewrites the base's bytes, so it must change the coordinate (bump NeoForge with"
						+ " it, or put the NFRT build into Installer.artifactVersion)");
			}
		}
		// The sidecar is versioned by the base it was measured against, so it moves with it.
		require(Installer.artifactVersion(ArtifactBuilder.VANILLA_REFERENCE, Pins.MINECRAFT).equals(version),
				"the vanilla reference is not versioned like the base");
		System.out.println("PASS base coordinate: net.forbric:patched-mc-neoforge:" + version + " recorded for NFRT "
				+ Pins.NFRT + " " + Pins.NFRT_RESULT + ", no NFRT build shares a coordinate");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
