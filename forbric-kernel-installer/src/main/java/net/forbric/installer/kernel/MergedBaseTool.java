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
import java.util.List;
import java.util.function.Consumer;

/**
 * Byte-merges vanilla, Forge-patched and NeoForge-patched Minecraft into the one base a Forbric instance runs on,
 * and patches the cross-runtime interop gaps that merge opens up.
 *
 * <p>Both steps live in {@code forbric-merge-tools.jar}, which rides inside the installer as a resource and is
 * unpacked next to the build. Three reasons it is a separate jar run as a subprocess rather than code called
 * in-process:
 *
 * <ul>
 *   <li>The merge wants a 4 GB heap ({@code -Xmx4g}, 30,471 output entries and ~17,680 diamond-default
 *       resolutions). A process cannot raise its own maximum heap after it starts, so the installer would have to
 *       have been launched with the right one — and it is launched by a double-click.</li>
 *   <li>The tools need ASM. Keeping it in its own jar preserves the installer's own rule that it carries
 *       Forbric's jars and nothing else, instead of quietly growing a dependency.</li>
 *   <li>A single-entry {@code -cp} is the one classpath form with nothing to get wrong about {@code ;} versus
 *       {@code :}. The shell script this replaces built its classpath with {@code paste -sd:}.</li>
 * </ul>
 *
 * <p>The interop step is not optional, and the installer skipped it until now. {@code build-merged-base.sh}
 * produces {@code forge-runtime-interop.jar} and every dev launch prefers it, but the installed profile was
 * staging the raw {@code forge-runtime.jar} — so an installed instance ran without the bridge method
 * {@code NamespacedWrapper$3.contents()}, which the merge makes abstract on {@code Registry$PendingTags} by
 * taking NeoForge's shape. The first NeoForge call down that path is an {@code AbstractMethodError}, and it is
 * far enough from the cause to be expensive.
 */
final class MergedBaseTool {

	/** Where the tools jar rides inside the installer jar. */
	private static final String TOOLS_RESOURCE = "/forbric/tools/forbric-merge-tools.jar";

	private static final String MERGE_MAIN = "net.forbric.tools.MergedBaseBuilder";
	private static final String INTEROP_MAIN = "net.forbric.tools.RuntimeInteropPatcher";

	/** What build-merged-base.sh gives the merge. Anything less and the tool runs out of heap mid-write. */
	private static final String MERGE_HEAP = "-Xmx4g";

	private final ForgeTool exec;
	private final Path toolsDir;
	private final Consumer<String> log;

	MergedBaseTool(Path toolsDir, Consumer<String> log) {
		this.exec = new ForgeTool(log);
		this.toolsDir = toolsDir;
		this.log = log;
	}

	/**
	 * Merges the three bases.
	 *
	 * @param report where the conflict report goes — kept rather than discarded, because a change in its contents
	 *               is the earliest signal that an upstream bump moved something the merge was calibrated against
	 */
	ArtifactResult merge(JdkLocator.Jvm jvm, Path vanilla, Path forgePatched, Path neoPatched,
	                     Path forgeRuntime, Path neoforgeRuntime, Path outJar, Path report,
	                     String coordinate) throws IOException {
		if (Files.isRegularFile(outJar) && Files.size(outJar) > 0) {
			log.accept("[merge] up-to-date: " + outJar.getFileName());
			return new ArtifactResult(coordinate, outJar, Util.sha1(outJar), Files.size(outJar));
		}
		Path tools = unpackTools();
		Files.createDirectories(outJar.getParent());

		log.accept("[merge] byte-merging vanilla + Forge + NeoForge → " + outJar.getFileName() + " …");
		exec.runProcess(List.of(
				jvm.javaBin().toString(), MERGE_HEAP,
				"-cp", tools.toString(), MERGE_MAIN,
				vanilla.toString(), forgePatched.toString(), neoPatched.toString(),
				outJar.toString(), report.toString(),
				forgeRuntime.toString(), neoforgeRuntime.toString()), "merged base");

		if (!Files.isRegularFile(outJar) || Files.size(outJar) == 0) {
			throw new IOException("the merge did not produce " + outJar);
		}
		long size = Files.size(outJar);
		log.accept("[merge] wrote " + outJar.getFileName() + " (" + (size / (1024 * 1024)) + " MB)");
		return new ArtifactResult(coordinate, outJar, Util.sha1(outJar), size);
	}

	/**
	 * Patches {@code forge-runtime.jar}'s own classes so they still satisfy the interfaces the merged base
	 * widened on NeoForge's behalf. The result is what gets staged; the input is left alone.
	 */
	ArtifactResult interop(JdkLocator.Jvm jvm, Path forgeRuntime, Path outJar, String coordinate)
			throws IOException {
		if (Files.isRegularFile(outJar) && Files.size(outJar) > 0) {
			log.accept("[interop] up-to-date: " + outJar.getFileName());
			return new ArtifactResult(coordinate, outJar, Util.sha1(outJar), Files.size(outJar));
		}
		Path tools = unpackTools();
		Files.createDirectories(outJar.getParent());

		exec.runProcess(List.of(
				jvm.javaBin().toString(),
				"-cp", tools.toString(), INTEROP_MAIN,
				forgeRuntime.toString(), outJar.toString()), "cross-runtime interop");

		if (!Files.isRegularFile(outJar) || Files.size(outJar) == 0) {
			throw new IOException("the interop patch did not produce " + outJar);
		}
		return new ArtifactResult(coordinate, outJar, Util.sha1(outJar), Files.size(outJar));
	}

	/** Copies the bundled tools jar out to disk, because a subprocess needs a path, not a resource. */
	private Path unpackTools() throws IOException {
		Files.createDirectories(toolsDir);
		Path dest = toolsDir.resolve("forbric-merge-tools.jar");
		if (Files.isRegularFile(dest) && Files.size(dest) > 0) return dest;
		try (InputStream in = MergedBaseTool.class.getResourceAsStream(TOOLS_RESOURCE)) {
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
