package net.forbric.tools;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.json.JsonFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The reviewed link baseline says which build it was reviewed on. That record once named a commit whose merge tools
 * could not have produced the recorded jar, and nothing read it; these checks keep it describing the baseline in the
 * tree and the tools that make the base, so a baseline edit or a new merge-tool source cannot leave it behind.
 */
class LinkBaselineProvenanceTest {
	private static final Path MERGE = Path.of(System.getProperty("user.dir"), "src", "test", "resources", "merge");
	private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

	private static Config provenance() throws Exception {
		return JsonFormat.fancyInstance().createParser().parse(new StringReader(
				Files.readString(MERGE.resolve("link-check-baseline.provenance.json"))));
	}

	@Test void itDescribesTheBaselineInThisTree() throws Exception {
		byte[] baseline = Files.readAllBytes(MERGE.resolve("link-check-baseline.txt"));
		Config provenance = provenance();
		assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(baseline)),
				provenance.get("baselineSha256"), "the baseline changed; review it and record the build it was reviewed on");
		long entries = Files.readAllLines(MERGE.resolve("link-check-baseline.txt")).stream()
				.filter(line -> !line.isBlank() && !line.startsWith("#")).count();
		assertEquals(entries, ((Number) provenance.get("rawReferences")).longValue());
	}

	@Test void itNamesTheReviewedJarsAndEveryToolSourceThatMakesTheBase() throws Exception {
		Config provenance = provenance();
		for (String input : List.of("merged", "neoRuntime", "forgeInterop"))
			assertTrue(HASH.matcher(String.valueOf((Object) provenance.get(List.of("inputs", input, "sha256")))).matches(), input);
		assertTrue(Pattern.matches("[0-9a-f]{40}", String.valueOf((Object) provenance.get("toolCommit"))),
				"a full tool commit, not an abbreviation that can become ambiguous");
		Config tools = provenance.get("toolSources");
		assertNotNull(tools, "which tool sources produced the reviewed jar");
		Set<String> compiled = new TreeSet<>();
		Matcher source = Pattern.compile("net/forbric/tools/(\\w+\\.java)").matcher(Files.readString(
				Path.of(System.getProperty("user.dir"), "run", "build-merged-base.sh")));
		while (source.find()) compiled.add(source.group(1));
		assertFalse(compiled.isEmpty());
		assertEquals(compiled, new TreeSet<>(tools.valueMap().keySet()),
				"every source build-merged-base.sh compiles is part of the tool the baseline was reviewed on");
		for (Object hash : tools.valueMap().values()) assertTrue(HASH.matcher(String.valueOf(hash)).matches());
	}
}
