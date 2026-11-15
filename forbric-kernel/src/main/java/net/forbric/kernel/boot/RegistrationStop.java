/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * NeoForge's registration failing stops the boot, as native NeoForge 26.2.0.93 stops it (owner ruling D82, "stop like
 * native").
 *
 * <p>Natively, {@code CommonModLoader.begin} runs {@code GameData.postRegisterEvents} as the init task "Registry
 * initialization" ({@code ModLoader.runInitTask}). When a mod's RegisterEvent listener throws, {@code ModContainer.acceptEvent}
 * rethrows it as a {@code ModLoadingException} naming the mod, which ends that registry's dispatch; anything else thrown
 * inside the per-registry try is collected the same way. After the loop {@code postRegisterEvents} logs "Rolling back to
 * VANILLA state", calls {@code RegistryManager.revertToVanilla()} and throws, and {@code ModLoader.waitForFuture} turns that
 * into the {@code ModLoadingException} the boot ends on: one issue per failure (a listener's own, or
 * {@code fml.modloadingissue.uncaughterror} with the cause), the FATAL line "Failed to wait for future Registry initialization,
 * N errors found", "ERROR DURING MOD LOADING" and an emptied {@code ModList}. A dedicated server's {@code ServerModLoader.load}
 * then writes the {@code crash-*-fml.txt} report and rethrows, {@code Main} logs "Failed to start the minecraft server" and
 * returns, and FML's launcher, finding no server thread, exits with status 1. A client's {@code Main} writes its crash report,
 * shows NeoForge's fatal-error window ({@code ClientModLoader.reportFatalError}) and exits. No world is created or loaded.
 * (javap of 26.2.0.93's GameData, CommonModLoader, ModLoader, ModContainer, ServerModLoader, server and client Main, FML's
 * Server and FatalErrorReporting; measured on the native server with the register-stop canary.)
 *
 * <p>The kernel calls {@code postRegisterEvents} itself, from the redirected {@code ServerModLoader.load} /
 * {@code ClientModLoader.begin}, so it builds that exception with NeoForge's own {@code ModLoader.waitForTask} and lets it
 * leave the redirect: from there the game's own handlers do the rest, as natively. It used to contain the failure instead
 * — one mod's listener cost that mod the registry, and a throw that rolled everything back left the boot running on vanilla
 * registries with one ERROR line.
 */
final class RegistrationStop {
	/** The init task {@code CommonModLoader.begin} runs NeoForge's registry phase as (javap of 26.2.0.93). */
	static final String TASK = "Registry initialization";
	/** What FML's {@code Server.main} exits with when {@code Main} returned without starting the server thread. */
	static final int SERVER_EXIT = 1;
	/** The only exception {@code ModLoader.waitForTask} stops a failed init task with (javap of 26.2.0.93). */
	static final String MOD_LOADING_EXCEPTION = "net.neoforged.fml.ModLoadingException";

	/** The exception this boot stops on, once there is one. */
	private static volatile Throwable stopping;

	private RegistrationStop() {
	}

	/**
	 * The exception native NeoForge stops the boot with for {@code failure}, what {@code GameData.postRegisterEvents} threw,
	 * built by NeoForge's own {@code ModLoader.waitForTask} (so its issues, log lines and emptied {@code ModList} are NeoForge's).
	 * The caller throws it. When NeoForge's {@code ModLoader} cannot build it — it is not there, or what it throws is not its
	 * {@code ModLoadingException} — the boot stops on {@code failure} itself (unwrapped from reflection), never on
	 * whatever {@code ModLoader} threw instead.
	 */
	static Throwable stop(ClassLoader cl, Throwable failure) {
		Throwable cause = failure instanceof InvocationTargetException reflected && reflected.getCause() != null
				? reflected.getCause() : failure;
		ForbricLog.error("[Forbric/Lifecycle] %s — the boot stops here, as native NeoForge stops it: NeoForge's mod-loading error "
				+ "follows, and the mods' content does not keep running on what is left", describe(cause));
		Throwable stop = nativeStop(cl, cause);
		stopping = stop;
		return stop;
	}

	/** What the stop line says about {@code failure}: NeoForge's roll-back with its causes, or the throw itself. */
	static String describe(Throwable failure) {
		if (!rolledBackToVanilla(failure)) return "NeoForge's GameData.postRegisterEvents threw " + failure;
		StringBuilder causes = new StringBuilder();
		for (Throwable cause : failure.getSuppressed()) causes.append("; ").append(cause);
		return "NeoForge's GameData.postRegisterEvents failed for " + failure.getSuppressed().length + " registr(ies) and rolled "
				+ "every registry back to vanilla (RegistryManager.revertToVanilla)" + causes;
	}

	/**
	 * Whether {@code t} is the aggregate {@code GameData.postRegisterEvents} throws after its roll-back: the
	 * {@code RuntimeException} it built itself (so its top frame is that method), carrying each registry's failure as a
	 * suppressed exception.
	 */
	static boolean rolledBackToVanilla(Throwable t) {
		StackTraceElement[] at = t.getStackTrace();
		return t.getClass() == RuntimeException.class && t.getSuppressed().length > 0 && at.length > 0
				&& at[0].getMethodName().equals("postRegisterEvents") && at[0].getClassName().endsWith(".GameData");
	}

	private static Throwable nativeStop(ClassLoader cl, Throwable cause) {
		try {
			Class<?> loader = Class.forName("net.neoforged.fml.ModLoader", true, cl);
			Method waitForTask = loader.getMethod("waitForTask", String.class, Runnable.class, CompletableFuture.class);
			bindModList(cl, loader);
			Runnable noProgress = () -> { };
			waitForTask.invoke(null, TASK, noProgress, CompletableFuture.failedFuture(cause));
		} catch (InvocationTargetException thrown) {
			Throwable built = thrown.getCause();
			if (isModLoadingException(cl, built)) {
				// The answer: NeoForge's ModLoadingException, handed on to be thrown.
				Caught.expected(thrown);
				return built;
			}
			// Not the error native NeoForge stops with (an NPE of ModLoader's own, say): stopping on it would put a
			// loader's accident in front of the player in place of the mod's failure.
			Caught.warn(thrown, "[Forbric/Lifecycle] NeoForge's ModLoader threw %s instead of its mod-loading error — the boot "
					+ "stops on what NeoForge's registration threw instead", built);
		} catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
			Caught.warn(unavailable, "[Forbric/Lifecycle] NeoForge's ModLoader could not build its mod-loading error — the boot "
					+ "stops on what NeoForge's registration threw instead");
		}
		return cause;
	}

	/** Whether {@code t} is NeoForge's {@code ModLoadingException}, as {@code cl} defines it. */
	private static boolean isModLoadingException(ClassLoader cl, Throwable t) {
		if (t == null) return false;
		try {
			return Class.forName(MOD_LOADING_EXCEPTION, false, cl).isInstance(t);
		} catch (ClassNotFoundException | LinkageError absent) {
			Caught.expected(absent);
			return false;
		}
	}

	/**
	 * {@code ModLoader.waitForFuture} ends by emptying the {@code ModList} it keeps in a field that only
	 * {@code ModLoader.gatherAndInitializeMods} sets, which the kernel does not run; so that field names the list the
	 * kernel published, as it does natively by the time the registry phase runs.
	 */
	private static void bindModList(ClassLoader cl, Class<?> loader) {
		try {
			Field field = loader.getDeclaredField("modList");
			field.setAccessible(true);
			if (field.get(null) != null) return;
			Class<?> modList = Class.forName(ForeignType.MOD_LIST.binary(Ecosystem.NEOFORGE), false, cl);
			field.set(null, modList.getMethod("get").invoke(null));
		} catch (ReflectiveOperationException | LinkageError | RuntimeException unbound) {
			Caught.warn(unbound, "[Forbric/Lifecycle] could not hand NeoForge's ModLoader the mod list it empties when mod loading fails");
		}
	}

	/** Whether {@code t} is the exception this boot stops on. */
	static boolean isStop(Throwable t) {
		return t != null && t == stopping;
	}

	/** Whether this boot stopped on a NeoForge registration failure. */
	static boolean stopped() {
		return stopping != null;
	}

	/**
	 * What {@code ServerModLoader.load} does with a {@code ModLoadingException} before rethrowing it (javap of 26.2.0.93):
	 * {@code hasErrors = true}, {@code LanguageHook.loadBuiltinLanguages()}, and the {@code crash-*-fml.txt} report through
	 * {@code CrashReportExtender.dumpModLoadingCrashReport(LOGGER, issues, new File("."))}. The kernel's redirect replaces
	 * that call, so it does this for it; the caller rethrows {@code stop}. Never throws.
	 */
	static void asServerModLoaderDoes(ClassLoader cl, Throwable stop) {
		Class<?> serverModLoader = null;
		try {
			serverModLoader = Class.forName(ForeignType.SERVER_MOD_LOADER.binary(Ecosystem.NEOFORGE), true, cl);
			Field hasErrors = serverModLoader.getDeclaredField("hasErrors");
			hasErrors.setAccessible(true);
			hasErrors.setBoolean(null, true);
		} catch (ReflectiveOperationException | LinkageError | RuntimeException unset) {
			Caught.warn(unset, "[Forbric/Lifecycle] could not mark NeoForge's ServerModLoader as failed");
		}
		try {
			Class.forName("net.neoforged.neoforge.server.LanguageHook", true, cl).getMethod("loadBuiltinLanguages").invoke(null);
		} catch (ReflectiveOperationException | LinkageError | RuntimeException untranslated) {
			Caught.warn(untranslated, "[Forbric/Lifecycle] could not load NeoForge's built-in languages for the mod-loading crash report");
		}
		try {
			List<?> issues = (List<?>) stop.getClass().getMethod("getIssues").invoke(stop);
			Class<?> logger = Class.forName("org.apache.logging.log4j.Logger", false, cl);
			Object log = Class.forName("org.apache.logging.log4j.LogManager", true, cl).getMethod("getLogger", Class.class)
					.invoke(null, serverModLoader != null ? serverModLoader : RegistrationStop.class);
			Class.forName("net.neoforged.neoforge.logging.CrashReportExtender", true, cl)
					.getMethod("dumpModLoadingCrashReport", logger, List.class, File.class).invoke(null, log, issues, new File("."));
		} catch (ReflectiveOperationException | LinkageError | RuntimeException unreported) {
			Caught.warn(unreported, "[Forbric/Lifecycle] could not write NeoForge's mod-loading crash report");
		}
	}

	/**
	 * After the server's {@code Main} returned: FML's {@code Server.main} finds no server thread when mod loading failed and
	 * exits with status {@value #SERVER_EXIT} ("Couldn't find Minecraft server thread. Startup likely failed."). 0 when this
	 * boot did not stop here.
	 */
	static int serverExit() {
		if (!stopped()) return 0;
		String line = "[Forbric/Boot] mod loading failed, so the server did not start (the error and its crash report are above)"
				+ " — exiting with status " + SERVER_EXIT + ", as NeoForge's own launcher does";
		ForbricLog.error("%s", line);
		System.err.println(line);
		return SERVER_EXIT;
	}

	/** Throws {@code failure} unchanged from a method that declares nothing. */
	@SuppressWarnings("unchecked")
	static <T extends Throwable> RuntimeException propagate(Throwable failure) throws T {
		throw (T) failure;
	}
}
