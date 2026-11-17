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

package net.forbric.kernel.linkcheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.tree.ClassNode;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.linkcheck.game.GameLinkCheck;
import net.forbric.kernel.mixin.NativeGameReferences;
import net.forbric.kernel.transform.VanillaMemberTwinInjector;

/**
 * The link check on the real stage: NeoForge's patched game and carrier as the kernel's first COREMOD pair hands them on,
 * the kernel's game side, and the game's libraries. What the genuine base, its carrier and the kernel's game side refer to
 * links, with access levels and invokedynamic handles judged; and the check sees the two shapes the stage can break in.
 */
@ResourceLock("native-game-references")
class GameLinkStagedTest {
	private static final String HUD = "net/minecraft/client/gui/Hud";
	private static final String HUD_METHOD = "extractHotbarAndDecorations";
	private static final String HUD_DESC = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V";

	@Test void theGameALaunchAssemblesLinks() throws IOException {
		GameLinkCheck.Outcome outcome = check(List.of());
		assertTrue(outcome.viewComplete(), outcome.viewLine() + " " + outcome.viewFailures());
		assertTrue(outcome.viewed() > 0, outcome.viewLine());
		assertEquals(List.of(), outcome.result().violations().stream().map(LinkCheck.Violation::line).toList(),
				outcome.result().summary());
		assertTrue(outcome.result().judged() > 1_000_000, outcome.result().summary());
	}

	/**
	 * Hud's shape: {@code extractHotbarAndDecorations} is public on the merged base, absent from NeoForge's patched jar, and
	 * re-added by the vanilla twin as vanilla declares it, private. A class compiled against the merged base that calls
	 * it would link there; on this stage it is an IllegalAccessError, and the check says so only because it reads the
	 * twin table: against the jar alone it is a missing method.
	 */
	@Test void aCallerOfHudsTwinIsAnAccessViolationOnlyWithTheTwins(@TempDir Path dir) throws IOException {
		Path probe = Files.createDirectories(dir.resolve("probe"));
		Files.write(Files.createDirectories(probe.resolve("net/forbric/linkprobe")).resolve("HudCaller.class"), hudCaller());

		GameLinkCheck.Outcome viewed = check(List.of(probe));
		assertTrue(viewed.viewComplete(), viewed.viewLine());
		assertEquals(List.of("[MEMBER_ACCESS] net/forbric/linkprobe/HudCaller#run()V  ->  METHOD " + HUD + "." + HUD_METHOD
				+ HUD_DESC + "  (private in " + HUD + ")"), lines(viewed.result()));

		LinkCheck raw = new LinkCheck();
		raw.scan(TestFixtures.stagedBase()).scan(TestFixtures.neoCarrier()).scan(probe);
		assertEquals(List.of("[MISSING_METHOD] net/forbric/linkprobe/HudCaller#run()V  ->  METHOD " + HUD + "." + HUD_METHOD
				+ HUD_DESC + "  (not declared or inherited)"), lines(raw.run()));
	}

	/**
	 * The install's red control on the real pair: a carrier with one member the base refers to narrowed to private. Its
	 * verdict line is the one the installer reads, and the run that judges the carrier exits 1 on it.
	 */
	@Test void aCarrierWithOneNarrowedMemberTheBaseUsesDoesNotLink(@TempDir Path dir) throws IOException {
		Path base = TestFixtures.stagedBase(), carrier = TestFixtures.neoCarrier();
		TestFixtures.requireFiles(Fixture.STAGED, "the genuine base and carrier", base, carrier);
		String owner = "net/neoforged/neoforge/common/NeoForge", field = "EVENT_BUS";
		Path narrowed = narrow(carrier, owner, field, dir.resolve("neoforge-runtime.jar"));
		String[] out = new String[1];
		assertEquals(0, cli(out, "--judge", carrier.toString(), base.toString(), carrier.toString()), out[0]);
		assertEquals(1, cli(out, "--judge", narrowed.toString(), base.toString(), narrowed.toString()), out[0]);
		assertTrue(out[0].lines().anyMatch(l -> l.startsWith("[MEMBER_ACCESS] net/minecraft/") && l.contains("->  FIELD " + owner + "."
				+ field + " ")), out[0]);
		assertTrue(out[0].lines().anyMatch(l -> l.matches("\\[link-check\\] loaded \\d+ classes .*; dangling references: [1-9]\\d*")),
				out[0]);
		// The run that judges the base is not where a carrier member is judged.
		assertEquals(0, cli(out, "--judge", base.toString(), base.toString(), narrowed.toString()), out[0]);
	}

	// --- helpers -----------------------------------------------------------------------------------------------------

	/** The game check over the stage, with {@code extraGameSide} added to the kernel's game side. */
	private static GameLinkCheck.Outcome check(List<Path> extraGameSide) throws IOException {
		Path base = TestFixtures.stagedBase(), carrier = TestFixtures.neoCarrier();
		TestFixtures.requireFiles(Fixture.STAGED, "the genuine base and carrier", base, carrier);
		Path runtime = Path.of("build/classes/java/runtime"), plugin = Path.of("build/classes/java/modMenuPlugin"),
				standIn = Path.of("build/classes/java/modMenuApi");
		TestFixtures.requireDirectory(Fixture.GAME_SIDE, "the kernel's compiled game side", runtime);
		Path libraries = TestFixtures.minecraftDir().resolve("libraries");
		TestFixtures.requireDirectory(Fixture.MC_LIBRARIES, "the game's libraries", libraries);
		List<Path> gameSide = new java.util.ArrayList<>(List.of(runtime));
		for (Path optional : List.of(plugin, standIn)) if (Files.isDirectory(optional)) gameSide.add(optional);
		gameSide.addAll(extraGameSide);
		NativeGameReferences.configure(TestFixtures.launchSources(base));
		try (ZipFile baseJar = new ZipFile(base.toFile())) {
			Function<String, ClassNode> vanilla = VanillaMemberTwinInjector.vanillaReferences(path -> {
				ZipEntry entry = baseJar.getEntry(path);
				if (entry == null) return null;
				try (InputStream in = baseJar.getInputStream(entry)) {
					return in.readAllBytes();
				} catch (IOException unreadable) {
					return null;
				}
			});
			return GameLinkCheck.check(new GameLinkCheck.Inputs(base, carrier, gameSide, List.of(libraries), null, List.of()),
					vanilla, LinkCheck.Options.all());
		} finally {
			NativeGameReferences.configure(null);
		}
	}

	private static List<String> lines(LinkCheck.Result result) {
		return result.violations().stream().map(LinkCheck.Violation::line).toList();
	}

	/** A class compiled against the merged base, where Hud's method was public: it calls it, as a plain instance call. */
	private static byte[] hudCaller() {
		return LinkCheckTest.caller("net/forbric/linkprobe/HudCaller", "java/lang/Object", mv -> {
			mv.visitInsn(ACONST_NULL);
			mv.visitInsn(ACONST_NULL);
			mv.visitInsn(ACONST_NULL);
			mv.visitMethodInsn(INVOKEVIRTUAL, HUD, HUD_METHOD, HUD_DESC, false);
		});
	}

	/** {@code carrier} with {@code owner.field} made private, every other entry as it was. */
	private static Path narrow(Path carrier, String owner, String field, Path out) throws IOException {
		boolean[] narrowed = { false };
		try (ZipFile in = new ZipFile(carrier.toFile()); ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out))) {
			for (ZipEntry entry : Collections.list(in.entries())) {
				byte[] bytes;
				try (InputStream stream = in.getInputStream(entry)) {
					bytes = stream.readAllBytes();
				}
				if (entry.getName().equals(owner + ".class")) {
					ClassWriter writer = new ClassWriter(0);
					new ClassReader(bytes).accept(new ClassVisitor(ASM9, writer) {
						@Override
						public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
							if (!name.equals(field)) return super.visitField(access, name, descriptor, signature, value);
							narrowed[0] = true;
							return super.visitField((access & ~(ACC_PUBLIC | ACC_PROTECTED)) | ACC_PRIVATE, name, descriptor,
									signature, value);
						}
					}, 0);
					bytes = writer.toByteArray();
				}
				zip.putNextEntry(new ZipEntry(entry.getName()));
				zip.write(bytes);
				zip.closeEntry();
			}
		}
		assertTrue(narrowed[0], "the carrier has no " + owner + "." + field + " to narrow");
		return out;
	}

	private static int cli(String[] out, String... args) {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		PrintStream both = new PrintStream(buffer, true, StandardCharsets.UTF_8);
		int code = LinkCheck.run(args, both, both);
		out[0] = buffer.toString(StandardCharsets.UTF_8);
		return code;
	}
}
