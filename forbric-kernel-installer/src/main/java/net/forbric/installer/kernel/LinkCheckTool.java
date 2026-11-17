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
 * method one of them refers to in the other, or in itself, must exist in one of the two and be accessible from the
 * class that refers to it, final members must not be written or overridden, and every invokedynamic bootstrap and
 * method-handle constant must resolve the same way (JVM semantics; see the kernel's
 * {@code net.forbric.kernel.linkcheck.LinkCheck}).
 *
 * <p>The checker is the kernel's own, built from the kernel tree into {@code forbric-link-check.jar} (its classes and
 * ASM's core, nothing else), which rides inside every installer build, a slim one included, as a resource and is
 * unpacked next to the build. It is run as a subprocess: it needs ASM, and keeping that in its own jar preserves the
 * installer's rule that it carries Forbric's jars and nothing else. It is carried rather than taken from the staged
 * kernel jar because the check runs before the installer stages any of Forbric's jars: a supplied set is checked
 * before anything is written, a built one before the profile is, and a slim installer has fetched nothing by then. A
 * single-entry {@code -cp} is also the one classpath form with nothing to get wrong about {@code ;} versus {@code :}.
 *
 * <p><b>Twice, once for each jar's members.</b> Each run scans the bodies of both jars and judges the references whose
 * owner is a class of one of them ({@code --judge}): first the base's, then the carrier's. Together they judge every
 * reference into either jar, in both directions; apart, each verdict line says which jar a failure is in. The base is
 * given first in both, as the game's loader serves it first. Measured on NeoForge 26.2.0.93: 0 violations in either
 * run, under a second each.
 *
 * <p>No baseline: NeoForge's patched game and its own carrier leave nothing dangling, so any violation is a defect and
 * fails the install (D10). Every kind counts in the one number the verdict line ends with.
 */
final class LinkCheckTool {

	/** Where the checker jar rides inside the installer jar. */
	static final String TOOLS_RESOURCE = "/forbric/tools/forbric-link-check.jar";

	/** Its file name once unpacked under {@code .forbric-build/tools}. */
	static final String TOOLS_FILE = "forbric-link-check.jar";

	private static final String LINK_CHECK_MAIN = "net.forbric.kernel.linkcheck.LinkCheck";

	/**
	 * The checker's verdict line: {@code [link-check] loaded N classes (...); dangling references: K}, where K counts
	 * every violation, missing, inaccessible, final and in method handles alike. Only K = 0 passes; a summary with
	 * anything after the count (the old checker's baseline form, {@code ... (known k, new n); ...}) does not match.
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
	 * Checks {@code base} against {@code neoRuntime} in both orders, and puts each verdict line in the install log.
	 *
	 * <p>A built set and a supplied one are checked alike. For a supplied set this is half of the guard against
	 * three unrelated jars with the right names (#13); the content check ({@link GameArtifacts#verifyContents}) is the
	 * other half.
	 *
	 * @throws Failed when the check ran and the jars did not pass it; any other IOException means it could not run
	 */
	void linkCheck(JdkLocator.Jvm jvm, Path base, Path neoRuntime) throws IOException {
		Path tools = unpackTools();
		check(jvm, tools, base, base, neoRuntime, "references into the game base");
		check(jvm, tools, neoRuntime, base, neoRuntime, "references into the NeoForge runtime");
	}

	/** One run of the checker over both jars, judging the references whose owner is a class of {@code judged}. */
	private void check(JdkLocator.Jvm jvm, Path tools, Path judged, Path base, Path neoRuntime, String what)
			throws IOException {
		List<String> tail = new ArrayList<>();
		int code = exec.exec(List.of(
				jvm.javaBin().toString(),
				"-cp", tools.toString(), LINK_CHECK_MAIN,
				"--judge", judged.toString(), base.toString(), neoRuntime.toString()),
				"link-checking the game base and the NeoForge runtime: " + what, tail);
		String summary = tail.stream()
				.filter(l -> l.contains("dangling references:"))
				.reduce((a, b) -> b)
				.orElse("[link-check] produced no summary line (exit " + code + ")")
				.strip();
		log.accept(summary);
		if (!passed(code, summary)) {
			throw new Failed("the game base and the NeoForge runtime do not link (" + what + ", exit " + code + "):\n"
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
	 * Copies the bundled checker jar out to disk, because a subprocess needs a path, not a resource.
	 *
	 * <p><b>Unconditionally</b>, overwriting whatever is there. It used to return the existing file when one was
	 * present, which is the same "the file exists, so it must be current" mistake {@link BuildStamp} was written
	 * to fix, one level further down — and it defeated that fix completely: the new stamp correctly invalidated
	 * every artifact, and the rebuild then ran the PREVIOUS installer's merge tool, left behind in
	 * {@code .forbric-build/tools}. The base came out with the bug the new tool existed to fix, and the install log
	 * said it had rebuilt everything, because it had. A stale link checker is the same hazard: it passes jars the
	 * current one would refuse.
	 *
	 * <p>Like the artifact cache, this only ever misbehaves on a machine that has installed before — the gate
	 * wipes its directory, so a gated install always unpacked into an empty one. It is 150 KB; there is nothing
	 * to save by being clever about it.
	 */
	private Path unpackTools() throws IOException {
		Files.createDirectories(toolsDir);
		Path dest = toolsDir.resolve(TOOLS_FILE);
		try (InputStream in = LinkCheckTool.class.getResourceAsStream(TOOLS_RESOURCE)) {
			if (in == null) {
				throw new IOException("this installer was built without " + TOOLS_RESOURCE
						+ " — rebuild it (its :bundleLinkCheck task builds the kernel's :linkCheckJar)");
			}
			Path part = dest.resolveSibling(dest.getFileName() + ".part");
			Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
			Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
		}
		return dest;
	}
}
