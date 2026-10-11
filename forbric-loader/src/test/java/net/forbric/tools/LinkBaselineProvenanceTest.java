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
		Matcher source = Pattern.compile("net/forbric/tools/(\\w+\\.java)").matcher(buildScript());
		while (source.find()) compiled.add(source.group(1));
		assertFalse(compiled.isEmpty());
		assertEquals(compiled, new TreeSet<>(tools.valueMap().keySet()),
				"every source build-merged-base.sh compiles is part of the tool the baseline was reviewed on");
		for (Object hash : tools.valueMap().values()) assertTrue(HASH.matcher(String.valueOf(hash)).matches());
	}

	/**
	 * The merge is a function of its inputs as much as of its sources: the edit splice made it read both userdev
	 * artifacts, and a record that names five inputs would describe a build the current script cannot make. The
	 * link baseline is the one input that is not the merge's own; the record names it by its hash.
	 */
	@Test void itNamesEveryInputTheMergeReads() throws Exception {
		Set<String> read = new TreeSet<>();
		Matcher input = Pattern.compile("--input ([\\w-]+)=").matcher(buildScript());
		while (input.find()) read.add(input.group(1));
		assertTrue(read.remove("link-baseline"), "build-merged-base.sh records the baseline it checked against");
		assertTrue(read.containsAll(Set.of("vanilla", "forge-patched", "neo-patched")), read.toString());
		Config inputs = provenance().get("mergeInputs");
		assertNotNull(inputs, "which inputs the reviewed jar was merged from");
		assertEquals(read, new TreeSet<>(inputs.valueMap().keySet()),
				"every input build-merged-base.sh hands the merge is recorded for the reviewed build");
		for (String role : read) {
			assertTrue(HASH.matcher(String.valueOf((Object) inputs.get(List.of(role, "sha256")))).matches(), role);
			assertFalse(String.valueOf((Object) inputs.get(List.of(role, "file"))).isBlank(), role);
		}
	}

	private static String buildScript() throws Exception {
		return Files.readString(Path.of(System.getProperty("user.dir"), "run", "build-merged-base.sh"));
	}
}
