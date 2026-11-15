/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ServiceLoader;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * Gives NeoForge's fatal-error report on a client the early window it shows the error in natively.
 *
 * <p><b>Native.</b> A NeoForge 26.2.0.93 client opens FML's early window ({@code ImmediateWindowHandler.load}, provider
 * {@code fmlearlywindow}) before it loads any mod. When loading fails, {@code ClientModLoader.reportFatalError} hands the
 * error to {@code FatalErrorReporting}, whose first step is {@code ImmediateWindowHandler.displayFatalErrorAndExit}: that
 * window turns into NeoForge's error screen — "Error loading mods", the mod and the cause, and the buttons Open Mods Folder,
 * Open Log File, Open Crash Report, Quit Game. Only without a provider does NeoForge fall back to a message box: Swing when
 * AWT is not headless, else tinyfd with its quotes replaced.
 *
 * <p><b>Under the kernel.</b> The early window is never opened (the game creates its own window in
 * {@code Minecraft.<init>}, see {@code PassiveSeeder.seedNeoForgeNoEarlyWindow}), so the provider is null and NeoForge
 * took its fallback. On macOS neither fallback shows the error: the game JVM is AWT-headless on purpose
 * ({@link MacAwtBootstrap}), and tinyfd refused NeoForge's text, so the player read "INVALID MESSAGE WITH QUOTES"; and the
 * Swing box cannot show at all on a JVM started with {@code -XstartOnFirstThread}, which every macOS launch is — measured
 * on native NeoForge with {@code earlyWindowControl=false}: the main thread waits in {@code JOptionPane.showMessageDialog}
 * and no window ever appears.
 *
 * <p><b>Here.</b> Called first thing in {@code ClientModLoader.reportFatalError} (FatalErrorWindowInjector): when no
 * provider exists, this opens the one native NeoForge would have opened at startup — chosen by name from FML's config the
 * way {@code ImmediateWindowHandler.load} chooses it, initialised with the game's arguments, on the calling thread (the
 * main thread, which GLFW needs on macOS) — and installs it as the handler's provider. NeoForge's own report then runs
 * unchanged and shows its own screen, with its own text. Nothing of Forbric's is drawn.
 *
 * <p><b>Only before the game has a window.</b> Natively the early window exists from startup and the game takes it over in
 * {@code Minecraft.<init>}, so a report made later (ClientModLoader's {@code finish}, inside {@code Minecraft.<init>}) draws
 * the error screen on the game's own window ({@code DisplayWindow.displayFatalErrorAndExit} takes the window over again and
 * hands it to {@code ErrorDisplay.fatal}). Under the kernel the game's window is the game's own, not the provider's; opening
 * the provider then would put a second GLFW window, with its own render thread, beside the game's. So a report made from
 * inside the game ({@code net.minecraft.client.Minecraft} on the calling thread's stack: its constructor or anything it
 * runs) is left to NeoForge's fallback, as before this class. Under the kernel the one live caller is {@code Main.main}'s
 * handler, before {@code new Minecraft} (the carrier's other caller, {@code ClientModLoader.finish}, is neutered by the
 * kernel); the check is for any caller that reports from inside the game.
 *
 * <p>It never makes the report worse: when GLFW cannot start (no display), or anything here fails, the provider stays null
 * and NeoForge's fallback runs as before. The provider's initialisation writes the window size into FML's config, which
 * would save {@code config/fml.toml}; the kernel's config is an in-memory one ({@code earlyWindowControl=false}), so it is
 * set aside for the call (every value reads its default meanwhile, the same answers but that one) and nothing is written.
 * {@code -Dforbric.fatalErrorWindow=off} leaves NeoForge's fallback as it was.
 */
public final class NeoFatalErrorWindow {
	public static final String PROPERTY = "forbric.fatalErrorWindow";
	/** The provider FML picks when its config names none: its own early window. */
	static final String DEFAULT_PROVIDER = "fmlearlywindow";

	private static volatile String[] gameArguments = new String[0];

	private NeoFatalErrorWindow() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** The arguments the game's {@code Main} receives, which native FML hands its window provider. */
	static void noteGameArguments(String[] args) {
		gameArguments = args == null ? new String[0] : args.clone();
	}

	/** What {@link #open} did; for the log and the tests. */
	enum Outcome {
		/** A provider was already there (natively always): NeoForge shows its screen with it. */
		PRESENT,
		/** The kernel opened NeoForge's early window; NeoForge's report draws its error screen on it. */
		OPENED,
		/** Switched off, or no window could be opened: NeoForge's message-box fallback runs as before. */
		FALLBACK,
		/** Reported from inside the game, which already has its own window: NeoForge's fallback, no second window. */
		IN_GAME
	}

	/** The game's class whose constructor creates the game's window and whose loop runs the game. */
	static final String GAME_CLASS = "net.minecraft.client.Minecraft";

	/**
	 * Makes sure NeoForge's window provider exists before NeoForge's fatal-error report asks it for the error screen.
	 * Never throws.
	 */
	public static Outcome open(ClassLoader gameLoader) {
		if (!enabled()) {
			ForbricLog.warn("[Forbric/FatalError] -D%s=off — NeoForge reports this error without its early window "
					+ "(its message-box fallback)", PROPERTY);
			return Outcome.FALLBACK;
		}
		if (reportedFromInsideTheGame()) {
			ForbricLog.warn("[Forbric/FatalError] the error is reported from inside the game, which has its own window — "
					+ "NeoForge reports it with its message-box fallback; no second window is opened");
			return Outcome.IN_GAME;
		}
		try {
			Class<?> handler = Class.forName(ForeignType.IMMEDIATE_WINDOW_HANDLER.binary(Ecosystem.NEOFORGE), true,
					gameLoader);
			Field providerField = handler.getDeclaredField("provider");
			providerField.setAccessible(true);
			if (providerField.get(null) != null) return Outcome.PRESENT;

			Class<?> providerType = Class.forName(ForeignType.IMMEDIATE_WINDOW_PROVIDER.binary(Ecosystem.NEOFORGE), true,
					gameLoader);
			String wanted = configuredProvider(gameLoader);
			Object provider = null;
			for (Object candidate : ServiceLoader.load(providerType, gameLoader)) {
				if (wanted.equals(providerType.getMethod("name").invoke(candidate))) {
					provider = candidate;
					break;
				}
			}
			if (provider == null) {
				ForbricLog.warn("[Forbric/FatalError] NeoForge has no early-window provider named '%s' — it reports this "
						+ "error with its message-box fallback", wanted);
				return Outcome.FALLBACK;
			}
			if (!graphicsStart(gameLoader)) {
				ForbricLog.warn("[Forbric/FatalError] GLFW could not start (no display?) — NeoForge reports this error "
						+ "with its message-box fallback");
				return Outcome.FALLBACK;
			}
			Class<?> programArgs = Class.forName(ForeignType.PROGRAM_ARGS.binary(Ecosystem.NEOFORGE), true, gameLoader);
			Object arguments = programArgs.getMethod("from", String[].class).invoke(null, (Object) gameArguments.clone());
			Method initialize = providerType.getMethod("initialize", programArgs);
			Object chosen = provider;
			withoutKernelConfig(gameLoader, () -> initialize.invoke(chosen, arguments));
			providerField.set(null, provider);
			ForbricLog.info("[Forbric/FatalError] opened NeoForge's early window ('%s') for its fatal-error report — the "
					+ "error shows on NeoForge's own screen, as natively", wanted);
			return Outcome.OPENED;
		} catch (ClassNotFoundException absent) {
			// No NeoForge carrier on this game loader: nothing of NeoForge's to report with.
			Caught.expected(absent);
			return Outcome.FALLBACK;
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/FatalError] could not open NeoForge's early window for its fatal-error report — "
					+ "NeoForge falls back to its message box");
			return Outcome.FALLBACK;
		}
	}

	/**
	 * Whether {@link #GAME_CLASS} is on the calling thread's stack: the report comes from the game's constructor (after it
	 * has created its window, or on the way to it) or from anything the game runs. By class name, so a stand-in under the
	 * game's name counts in the tests.
	 */
	static boolean reportedFromInsideTheGame() {
		return StackWalker.getInstance().walk(frames -> frames.anyMatch(f -> GAME_CLASS.equals(f.getClassName())));
	}

	/** {@code earlyWindowProvider} from FML's config, as {@code ImmediateWindowHandler.load} reads it. */
	static String configuredProvider(ClassLoader gameLoader) throws ReflectiveOperationException {
		String config = ForeignType.FML_CONFIG.binary(Ecosystem.NEOFORGE);
		Class<?> configType = Class.forName(config, true, gameLoader);
		Class<?> valueType = Class.forName(config + "$ConfigValue", true, gameLoader);
		Object key = null;
		for (Object constant : valueType.getEnumConstants()) {
			if (((Enum<?>) constant).name().equals("EARLY_WINDOW_PROVIDER")) key = constant;
		}
		if (key == null) return DEFAULT_PROVIDER;
		Object value = configType.getMethod("getConfigValue", valueType).invoke(null, key);
		return value == null || value.toString().isBlank() ? DEFAULT_PROVIDER : value.toString();
	}

	/**
	 * Whether GLFW starts on this machine. {@code glfwInit} is idempotent, and the provider calls it again; asking first
	 * keeps a machine without a display on NeoForge's message box instead of the provider's own "unable to initialize the
	 * graphics system" box, which would replace the real error.
	 */
	private static boolean graphicsStart(ClassLoader gameLoader) throws ReflectiveOperationException {
		Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW", true, gameLoader);
		return (Boolean) glfw.getMethod("glfwInit").invoke(null);
	}

	private interface Call {
		void run() throws Exception;
	}

	/**
	 * Runs {@code call} with FML's config data set aside. The kernel's config is in memory; the provider's
	 * {@code FMLConfig.updateConfig} would save it to {@code config/fml.toml}, which nothing under Forbric reads and which a
	 * native NeoForge launch of the same folder would then read ({@code earlyWindowControl=false} among it).
	 */
	private static void withoutKernelConfig(ClassLoader gameLoader, Call call) throws Exception {
		Class<?> configType = Class.forName(ForeignType.FML_CONFIG.binary(Ecosystem.NEOFORGE), true, gameLoader);
		Field instanceField = configType.getDeclaredField("INSTANCE");
		instanceField.setAccessible(true);
		Object instance = instanceField.get(null);
		Field data = configType.getDeclaredField("configData");
		data.setAccessible(true);
		Object kept = data.get(instance);
		data.set(instance, null);
		try {
			call.run();
		} finally {
			data.set(instance, kept);
		}
	}
}
