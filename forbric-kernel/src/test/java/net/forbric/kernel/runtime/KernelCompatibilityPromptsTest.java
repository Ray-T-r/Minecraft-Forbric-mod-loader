package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.kernel.ui.CompatibilityDecision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/** Executes the compiled GAME helper against recording game boundaries; no window or save is touched. */
@ResourceLock("ModCatalog") @ResourceLock("system-properties")
class KernelCompatibilityPromptsTest {
	@TempDir Path tmp;
	@BeforeEach @AfterEach void reset() throws Exception {
		CompatibilityFindings.reset(); CompatibilityDecision.reset();
		System.clearProperty(CompatibilityDecision.PROPERTY);
		// The tick now writes the reports; what the last test wrote must not make this one's write look redundant.
		var fresh = net.forbric.kernel.boot.KernelLoadReport.class.getDeclaredMethod("reset");
		fresh.setAccessible(true);
		fresh.invoke(null);
	}

	private static void loseFeature() {
		CompatibilityFindings.record(new CompatibilityFinding("lost", "demo", "Inventory", "test",
				CompatibilityFinding.Confidence.CONFIRMED, true, "item update not delivered", List.of("observed")));
	}

	@Test void continueRestoresTheScreenAndNeverClearsTheGateFailure() throws Exception {
		try (Fixture fixture = fixture()) {
			loseFeature(); fixture.tick();
			assertEquals("KernelCompatibilityScreen", fixture.screen().getClass().getSimpleName());
			fixture.answer(true); fixture.tick();
			assertNull(fixture.screen());
			assertEquals(List.of("screen:KernelCompatibilityScreen", "screen:null"), fixture.events());
			assertEquals(1, CompatibilityFindings.confirmedRequired().size());
		}
	}

	@Test void closingConfirmationSavesBeforeReturningAndAsksAgainInTheNextWorld() throws Exception {
		try (Fixture fixture = fixture()) {
			fixture.enterWorld(); loseFeature(); fixture.tick();
			fixture.loader.loadClass("net.minecraft.client.gui.screens.Screen").getMethod("onClose").invoke(fixture.screen());
			fixture.tick(); fixture.tick();
			assertEquals(List.of("screen:KernelCompatibilityScreen", "save", "screen:TitleScreen"), fixture.events());
			assertEquals(1, fixture.events().stream().filter("screen:KernelCompatibilityScreen"::equals).count(),
					"a declined contract does not reopen its prompt every tick at the title");
			fixture.enterWorld(); fixture.tick();
			// Not a silent bounce back to the title for the rest of the launch: the player is told why and may choose.
			assertEquals("KernelCompatibilityScreen", fixture.screen().getClass().getSimpleName());
			assertEquals(List.of("screen:KernelCompatibilityScreen", "save", "screen:TitleScreen", "screen:KernelCompatibilityScreen"),
					fixture.events());
			fixture.answer(true); fixture.tick();
			assertTrue(CompatibilityDecision.check(false), "an explicit Continue in the new world is consent");
			assertEquals(1, CompatibilityFindings.confirmedRequired().size(), "and it never clears the evidence");
		}
	}

	@Test void strictSavesAndStopsThroughMinecraftInsteadOfExitingTheJvm() throws Exception {
		System.setProperty(CompatibilityDecision.PROPERTY, "strict");
		try (Fixture fixture = fixture()) {
			fixture.enterWorld(); loseFeature(); fixture.tick(); fixture.tick();
			assertEquals(List.of("save", "stop"), fixture.events());
			assertTrue(CompatibilityDecision.launchStopRequested(), "the launcher reports the late strict stop as the policy stop");
		}
	}

	@Test void aDeathOrKickReplacingThePromptIsNeitherConsentNorRefusal() throws Exception {
		try (Fixture fixture = fixture()) {
			fixture.enterWorld(); loseFeature(); fixture.tick();
			fixture.setScreen("DeathScreen");
			fixture.tick();
			assertEquals("KernelCompatibilityScreen", fixture.screen().getClass().getSimpleName(), "asked again");
			assertFalse(fixture.events().contains("save"), "the player was not thrown out of the world");
			fixture.answer(true);
			assertEquals("DeathScreen", fixture.screen().getClass().getSimpleName(), "the answer returns to what replaced it");
			assertTrue(CompatibilityDecision.check(false));
		}
	}

	@Test void aRefusalOutsideAWorldKeepsTheScreenThatWasThere() throws Exception {
		try (Fixture fixture = fixture()) {
			fixture.setScreen("DisconnectedScreen");
			loseFeature(); fixture.tick();
			fixture.answer(false);
			assertEquals("DisconnectedScreen", fixture.screen().getClass().getSimpleName(), "a kick reason is not overwritten");
			assertFalse(fixture.events().contains("save"));
			assertFalse(CompatibilityDecision.check(false), "not consent");
		}
	}

	@Test void aContinueCoversOnlyTheFindingsItsScreenListed() throws Exception {
		try (Fixture fixture = fixture()) {
			fixture.enterWorld();
			for (int i = 0; i < 6; i++) {
				CompatibilityFindings.record(new CompatibilityFinding("lost" + i, "demo", "Feature " + i, "test",
						CompatibilityFinding.Confidence.CONFIRMED, true, "not delivered", List.of("observed")));
			}
			fixture.tick();
			String first = fixture.message();
			for (int i = 0; i < 4; i++) assertTrue(first.contains("Feature " + i), first);
			assertFalse(first.contains("Feature 4") || first.contains("Feature 5"), first);
			assertTrue(first.contains(net.forbric.kernel.ui.DialogLang.ofSystem().get("compat.more", 2)), "says more follow: " + first);
			fixture.answer(true);
			assertFalse(CompatibilityDecision.check(false), "two losses were never shown, so they were not accepted");
			fixture.tick();
			String second = fixture.message();
			assertTrue(second.contains("Feature 4") && second.contains("Feature 5"), second);
			assertFalse(second.contains(net.forbric.kernel.ui.DialogLang.ofSystem().get("compat.more", 2)), second);
			fixture.answer(true);
			assertTrue(CompatibilityDecision.check(false));
		}
	}

	@Test void failedScreenPresentationCannotBeMistakenForConsent() throws Exception {
		try (Fixture fixture = fixture()) {
			fixture.enterWorld(); fixture.gui.getClass().getField("fail").setBoolean(fixture.gui, true);
			loseFeature(); fixture.tick();
			assertEquals(List.of("save", "stop"), fixture.events());
			CompatibilityDecision.queue();
			assertEquals(1, CompatibilityDecision.drain().size(), "not acknowledged on UI failure");
		}
	}

	@Test void aReplacingModCannotDismissTheWarningAndLeaveThePlayerInTheWorld() throws Exception {
		try (Fixture fixture = fixture()) {
			fixture.enterWorld(); loseFeature(); fixture.tick();
			fixture.gui.getClass().getField("current").set(fixture.gui, null);
			fixture.tick();
			assertEquals(List.of("screen:KernelCompatibilityScreen", "screen:KernelCompatibilityScreen"), fixture.events(),
					"the warning comes straight back");
			assertFalse(CompatibilityDecision.check(false), "and being replaced was not consent");
		}
	}

	/** A spawner's call site the upgrade could not prove, recorded on the integrated server thread during play. */
	private static void loseSpawnerInput() {
		CompatibilityFindings.record(new CompatibilityFinding("spawner-finalize-input", "forbric", "Spawner finalization",
				"KernelSpawnerFinalize", CompatibilityFinding.Confidence.CONFIRMED, true,
				"The spawner call site did not supply its ValueInput; Forge finalization was not dispatched.",
				List.of("BaseSpawner.serverTick")));
	}

	@Test void aLossRecordedDuringPlayIsInTheReportsThePromptAndTheModsScreenPointAt() throws Exception {
		Path report = tmp.resolve(".forbric-kernel").resolve("load-report.txt");
		Path machine = report.resolveSibling("compatibility-report.json");
		net.forbric.kernel.boot.KernelLoadReport.setRunDir(tmp);
		try (Fixture fixture = fixture()) {
			fixture.enterWorld(); fixture.tick();
			assertFalse(Files.exists(report), "nothing has failed yet");
			// No catalogue row is "forbric", so the Mods screen can only count this and point at load-report.txt; on
			// a singleplayer client nothing else rewrites that file until the JVM exits.
			loseSpawnerInput(); fixture.tick();
			assertEquals("KernelCompatibilityScreen", fixture.screen().getClass().getSimpleName());
			assertTrue(Files.exists(report), "the file the Mods screen points at exists by the time the prompt is up");
			assertTrue(Files.readString(report).contains("spawner-finalize-input"), Files.readString(report));
			assertTrue(Files.readString(machine).contains("\"confirmedRequired\":1"), Files.readString(machine));
		} finally {
			net.forbric.kernel.boot.KernelLoadReport.setRunDir(null);
		}
	}

	@Test void theSameObservationOnEverySpawnDoesNotRewriteTheReportsEveryTick() throws Exception {
		Path machine = tmp.resolve(".forbric-kernel").resolve("compatibility-report.json");
		net.forbric.kernel.boot.KernelLoadReport.setRunDir(tmp);
		try (Fixture fixture = fixture()) {
			fixture.enterWorld(); loseSpawnerInput(); fixture.tick();
			fixture.answer(true); fixture.tick();
			assertTrue(Files.exists(machine));
			Files.delete(machine);
			for (int spawn = 0; spawn < 3; spawn++) { loseSpawnerInput(); fixture.tick(); }
			assertFalse(Files.exists(machine), "nothing changed, so nothing is written on the render thread");
			CompatibilityFindings.record(new CompatibilityFinding("transfer-component", "forbric", "Item and fluid transfer",
					"KernelTransferInterop", CompatibilityFinding.Confidence.CONFIRMED, false, "a component was skipped",
					List.of("observed")));
			fixture.tick();
			assertTrue(Files.exists(machine), "a real change is written");
		} finally {
			net.forbric.kernel.boot.KernelLoadReport.setRunDir(null);
		}
	}

	@Test void anActiveLoadingOverlayDefersThePromptWithoutLosingIt() throws Exception {
		try (Fixture fixture = fixture()) {
			fixture.gui.getClass().getField("loading").setBoolean(fixture.gui, true);
			loseFeature(); fixture.tick(); assertNull(fixture.screen());
			fixture.gui.getClass().getField("loading").setBoolean(fixture.gui, false);
			fixture.tick(); assertEquals("KernelCompatibilityScreen", fixture.screen().getClass().getSimpleName());
		}
	}

	private Fixture fixture() throws Exception {
		Map<String, String> sources = Map.ofEntries(
				Map.entry("net/minecraft/client/Minecraft.java", """
					package net.minecraft.client;
					public class Minecraft {
					 public final net.minecraft.client.gui.Gui gui = new net.minecraft.client.gui.Gui();
					 public net.minecraft.client.multiplayer.ClientLevel level;
					 public static final java.util.List<String> events = new java.util.ArrayList<>();
					 public boolean running = true;
					 public boolean isRunning() { return running; }
					 public void disconnectWithSavingScreen() { events.add("save"); level = null; }
					 public void stop() { events.add("stop"); running = false; }
					}
					"""),
				Map.entry("net/minecraft/client/gui/Gui.java", """
					package net.minecraft.client.gui;
					import net.minecraft.client.gui.screens.*;
					public class Gui {
					 public Screen current; public boolean loading; public boolean fail;
					 public Screen screen() { return current; }
					 public Overlay overlay() { return loading ? new Overlay() : null; }
					 public void setScreen(Screen screen) {
					  if (fail) throw new IllegalStateException("cannot draw");
					  current = screen; net.minecraft.client.Minecraft.events.add("screen:" + (screen == null ? "null" : screen.getClass().getSimpleName()));
					 }
					}
					"""),
				Map.entry("net/minecraft/client/gui/screens/Screen.java", """
					package net.minecraft.client.gui.screens;
					public class Screen {
					 public void onClose() {} protected void init() {}
					 protected void setInitialFocus(net.minecraft.client.gui.components.events.GuiEventListener item) {}
					}
					"""),
				Map.entry("net/minecraft/client/gui/screens/ConfirmScreen.java", """
					package net.minecraft.client.gui.screens;
					import net.minecraft.network.chat.Component;
					import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
					public class ConfirmScreen extends Screen {
					 protected net.minecraft.client.gui.components.Button noButton;
					 private final BooleanConsumer answer;
					 public final Component message;
					 public ConfirmScreen(BooleanConsumer a, Component title, Component message, Component yes, Component no) { answer=a; this.message=message; }
					 public void respond(boolean yes) { answer.accept(yes); }
					}
					"""),
				Map.entry("net/minecraft/client/gui/screens/TitleScreen.java", "package net.minecraft.client.gui.screens; public class TitleScreen extends Screen {}"),
				Map.entry("net/minecraft/client/gui/screens/Overlay.java", "package net.minecraft.client.gui.screens; public class Overlay {}"),
				Map.entry("net/minecraft/client/multiplayer/ClientLevel.java", "package net.minecraft.client.multiplayer; public class ClientLevel {}"),
				Map.entry("net/minecraft/client/gui/components/events/GuiEventListener.java", "package net.minecraft.client.gui.components.events; public interface GuiEventListener {}"),
				Map.entry("net/minecraft/client/gui/components/Button.java", "package net.minecraft.client.gui.components; public class Button implements net.minecraft.client.gui.components.events.GuiEventListener {}"),
				Map.entry("net/minecraft/network/chat/Component.java", "package net.minecraft.network.chat; public interface Component { static MutableComponent literal(String value) { return new MutableComponent(value); } }"),
				Map.entry("net/minecraft/network/chat/MutableComponent.java", "package net.minecraft.network.chat; public class MutableComponent implements Component { public final String text; public MutableComponent(String text) { this.text = text; } }"),
				Map.entry("net/minecraft/client/gui/screens/DeathScreen.java", "package net.minecraft.client.gui.screens; public class DeathScreen extends Screen {}"),
				Map.entry("net/minecraft/client/gui/screens/DisconnectedScreen.java", "package net.minecraft.client.gui.screens; public class DisconnectedScreen extends Screen {}"),
				Map.entry("it/unimi/dsi/fastutil/booleans/BooleanConsumer.java", "package it.unimi.dsi.fastutil.booleans; public interface BooleanConsumer { void accept(boolean yes); }"));
		Path classes = tmp.resolve("classes"); Files.createDirectories(classes);
		List<String> args = new ArrayList<>(List.of("-d", classes.toString()));
		for (var entry : sources.entrySet()) {
			Path source = tmp.resolve("src").resolve(entry.getKey());
			Files.createDirectories(source.getParent()); Files.writeString(source, entry.getValue()); args.add(source.toString());
		}
		assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
		Path runtime = Path.of("build/classes/java/runtime").toAbsolutePath();
		assertTrue(Files.isRegularFile(runtime.resolve("net/forbric/kernel/runtime/KernelCompatibilityPrompts.class")));
		return new Fixture(new URLClassLoader(new URL[] {classes.toUri().toURL(), runtime.toUri().toURL()}, getClass().getClassLoader()));
	}

	private static final class Fixture implements AutoCloseable {
		final URLClassLoader loader; final Class<?> type; final Object minecraft; final Object gui;
		Fixture(URLClassLoader loader) throws Exception {
			this.loader = loader; type = loader.loadClass("net.minecraft.client.Minecraft");
			minecraft = type.getConstructor().newInstance(); gui = type.getField("gui").get(minecraft);
		}
		void tick() throws Exception { loader.loadClass("net.forbric.kernel.runtime.KernelCompatibilityPrompts").getMethod("tick", type).invoke(null, minecraft); }
		Object screen() throws Exception { return gui.getClass().getMethod("screen").invoke(gui); }
		void answer(boolean yes) throws Exception { loader.loadClass("net.minecraft.client.gui.screens.ConfirmScreen").getMethod("respond", boolean.class).invoke(screen(), yes); }
		void setScreen(String simpleName) throws Exception {
			gui.getClass().getField("current").set(gui, loader.loadClass("net.minecraft.client.gui.screens." + simpleName).getConstructor().newInstance());
		}
		String message() throws Exception {
			Object message = loader.loadClass("net.minecraft.client.gui.screens.ConfirmScreen").getField("message").get(screen());
			return (String) message.getClass().getField("text").get(message);
		}
		void enterWorld() throws Exception { type.getField("level").set(minecraft, loader.loadClass("net.minecraft.client.multiplayer.ClientLevel").getConstructor().newInstance()); }
		@SuppressWarnings("unchecked") List<String> events() throws Exception { return (List<String>) type.getField("events").get(null); }
		@Override public void close() throws Exception { loader.close(); }
	}
}
