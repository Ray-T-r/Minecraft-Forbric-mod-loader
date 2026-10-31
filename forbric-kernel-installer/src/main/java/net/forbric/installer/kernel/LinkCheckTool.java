/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.installer.kernel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Link-checks the game base against the NeoForge carrier before a profile may name them: every class, field and
 * method one of them refers to must exist in one of the two.
 *
 * <p>The checker ({@code net.forbric.tools.MergedLinkChecker}) lives in {@code forbric-merge-tools.jar}, which rides
 * inside the installer as a resource and is unpacked next to the build. It is run as a subprocess: it needs ASM, and
 * keeping that in its own jar preserves the installer's rule that it carries Forbric's jars and nothing else; a
 * single-entry {@code -cp} is also the one classpath form with nothing to get wrong about {@code ;} versus
 * {@code :}.
 *
 * <p>No baseline. The packaged one lists the merged base's accepted dangling references, all of them MinecraftForge
 * members; NeoForge's patched game and its own carrier leave none (measured on NeoForge 26.2.0.93: 13,394 classes,
 * {@code dangling references: 0}), so any dangling reference is a defect and fails the install.
 */
final class LinkCheckTool {

	/** Where the tools jar rides inside the installer jar. */
	private static final String TOOLS_RESOURCE = "/forbric/tools/forbric-merge-tools.jar";

	private static final String LINK_CHECK_MAIN = "net.forbric.tools.MergedLinkChecker";

	/**
	 * The checker's verdict line when it runs without a baseline: {@code [link-check] loaded N classes (M from the
	 * merged jar); dangling references: K}. Only K = 0 passes; a baseline run's summary ({@code ... (known k, new n);
	 * ...}) does not match, so a checker that was handed a baseline cannot pass here either.
	 */
	private static final Pattern CLEAN = Pattern.compile("\\[link-check\\] loaded \\d+ classes .*; dangling references: 0");

	private final ToolProcess exec;
	private final Path toolsDir;
	private final Consumer<String> log;

	LinkCheckTool(Path toolsDir, Consumer<String> log) {
		this.exec = new ToolProcess(log, "[link-check]");
		this.toolsDir = toolsDir;
		this.log = log;
	}

	/**
	 * Checks {@code base} against {@code neoRuntime}, and puts the verdict line in the install log.
	 *
	 * <p>A built set and a supplied one are checked alike. For a supplied set this is half of the guard against
	 * three unrelated jars with the right names (#13); the content check ({@link GameArtifacts#verifyContents}) is the
	 * other half.
	 *
	 * @throws Failed when the check ran and the jars did not pass it; any other IOException means it could not run
	 */
	void linkCheck(JdkLocator.Jvm jvm, Path base, Path neoRuntime) throws IOException {
		Path tools = unpackTools();
		List<String> tail = new ArrayList<>();
		int code = exec.exec(List.of(
				jvm.javaBin().toString(),
				"-cp", tools.toString(), LINK_CHECK_MAIN,
				base.toString(), neoRuntime.toString()),
				"link-checking the game base against the NeoForge runtime", tail);
		String summary = tail.stream()
				.filter(l -> l.contains("dangling references:"))
				.reduce((a, b) -> b)
				.orElse("[link-check] produced no summary line (exit " + code + ")")
				.strip();
		log.accept(summary);
		if (!passed(code, summary)) {
			throw new Failed("the game base and the NeoForge runtime do not link (exit " + code + "):\n"
					+ String.join("\n", tail));
		}
	}

	/** Exit 0 and a summary saying zero dangling references: the checker's own success, nothing looser. */
	static boolean passed(int exitCode, String summary) {
		return exitCode == 0 && CLEAN.matcher(summary).matches();
	}

	/**
	 * The verdict "these jars do not link", as distinct from the check not running at all — so a caller that
	 * did not build the jars itself can say whose they are.
	 */
	static final class Failed extends IOException {
		Failed(String message) {
			super(message);
		}
	}

	/**
	 * Copies the bundled tools jar out to disk, because a subprocess needs a path, not a resource.
	 *
	 * <p><b>Unconditionally</b>, overwriting whatever is there. It used to return the existing file when one was
	 * present, which is the same "the file exists, so it must be current" mistake {@link BuildStamp} was written
	 * to fix, one level further down — and it defeated that fix completely: the new stamp correctly invalidated
	 * every artifact, and the rebuild then ran the PREVIOUS installer's merge tool, left behind in
	 * {@code .forbric-build/tools}. The base came out with the bug the new tool existed to fix, and the install log
	 * said it had rebuilt everything, because it had. The link checker rides in the same jar, so it is the same
	 * hazard: a stale checker passes jars the current one would refuse.
	 *
	 * <p>Like the artifact cache, this only ever misbehaves on a machine that has installed before — the gate
	 * wipes its directory, so a gated install always unpacked into an empty one. It is 280 KB; there is nothing
	 * to save by being clever about it.
	 */
	private Path unpackTools() throws IOException {
		Files.createDirectories(toolsDir);
		Path dest = toolsDir.resolve("forbric-merge-tools.jar");
		try (InputStream in = LinkCheckTool.class.getResourceAsStream(TOOLS_RESOURCE)) {
			if (in == null) {
				throw new IOException("this installer was built without " + TOOLS_RESOURCE
						+ " — run ':forbric-loader:mergeToolsJar' and rebuild it");
			}
			Path part = dest.resolveSibling(dest.getFileName() + ".part");
			Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
			Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
		}
		return dest;
	}
}
