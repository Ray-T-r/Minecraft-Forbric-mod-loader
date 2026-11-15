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
 * NeoForge's mod loading failing stops the boot, as native NeoForge 26.2.0.93 stops it (owner ruling D82, "stop like
 * native", extended by D87 to every phase).
 *
 * <p>Natively, {@code CommonModLoader.begin} and {@code load} run every step of mod loading as an init task of
 * {@code ModLoader}: "Mod Construction" (each mod's constructor, its {@code @EventBusSubscriber} classes, its
 * {@code FMLConstructModEvent}), "Registry initialization" ({@code NewRegistryEvent}, {@code GameData.postRegisterEvents},
 * the freeze), "Common setup", "Sided setup", "Registration events", "Enqueue IMC", "Process IMC", "Complete loading of N
 * mods", "Network registry lock", and each parallel phase's "&lt;phase&gt;: Deferred Queue". A mod's listener that throws
 * becomes, in {@code ModContainer.acceptEvent}, a {@code ModLoadingException} naming the mod; a constructor or subscriber
 * class that throws becomes {@code fml.modloadingissue.failedtoloadmod} naming the mod; anything else is collected as it is.
 * When the task ends, {@code ModLoader.waitForFuture} turns what was collected into the {@code ModLoadingException} the boot
 * ends on: one issue per failure (a mod's own, or {@code fml.modloadingissue.uncaughterror} with the task's name and the
 * cause), the FATAL line "Failed to wait for future &lt;task&gt;, N errors found", "ERROR DURING MOD LOADING" and an emptied
 * {@code ModList}. A dedicated server's {@code ServerModLoader.load} then writes the {@code crash-*-fml.txt} report and
 * rethrows, {@code Main} logs "Failed to start the minecraft server" and returns, and FML's launcher, finding no server
 * thread, exits with status 1. A client's {@code Main} (construction and registration, inside {@code ClientModLoader.begin})
 * or {@code ClientModLoader.finish}'s own handler (every later task) writes the crash report and shows NeoForge's
 * fatal-error window. No world is created or loaded. (javap of 26.2.0.93's ModLoader, CommonModLoader, ModContainer,
 * FMLModContainer, AutomaticEventSubscriber, DeferredWorkQueue, ModList, GameData, ServerModLoader, ClientModLoader, server
 * and client Main, FML's Server and FatalErrorReporting; measured on the native server with the register-stop canary.)
 *
 * <p>The kernel drives those steps itself, so it builds that exception with NeoForge's own {@code ModLoader.waitForTask} and
 * lets it leave the redirected {@code ServerModLoader.load} / {@code ClientModLoader.begin} / {@code ClientModLoader.finish}
 * call site: from there the game's own handlers do the rest, as natively. It used to contain each failure instead — the
 * mod lost that phase (its registry, its setup, its deferred work) and the boot carried on without it.
 */
public final class RegistrationStop {
	/** The init task {@code CommonModLoader.begin} runs NeoForge's registry phase as (javap of 26.2.0.93). */
	public static final String TASK = "Registry initialization";
	/** The init task {@code ModLoader.gatherAndInitializeMods} constructs the mods in. */
	public static final String CONSTRUCTION = "Mod Construction";
	/** {@code CommonModLoader.load}'s task for {@code RegistrationEvents.init}. */
	public static final String REGISTRATION_EVENTS = "Registration events";
	/** {@code CommonModLoader.load}'s task for {@code NetworkRegistry.setup}. */
	public static final String NETWORK = "Network registry lock";
	/** {@code ClientModLoader.finish}'s task for {@code ClientNetworkRegistry.setup}. */
	public static final String CLIENT_NETWORK = "Client network registry lock";
	/** What FML's {@code Server.main} exits with when {@code Main} returned without starting the server thread. */
	static final int SERVER_EXIT = 1;
	/** The only exception {@code ModLoader.waitForTask} stops a failed init task with (javap of 26.2.0.93). */
	static final String MOD_LOADING_EXCEPTION = "net.neoforged.fml.ModLoadingException";

	/** The task a parallel phase's deferred work runs as: {@code ModLoader.dispatchParallelEvent}'s "\u0001: Deferred Queue". */
	public static String deferredQueue(String task) {
		return task + ": Deferred Queue";
	}

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
	public static Throwable stop(ClassLoader cl, Throwable failure) {
		return stop(cl, TASK, failure);
	}

	/**
	 * The same for the init task {@code task}, whose one failure is {@code failure}: what the task's future failed with
	 * natively — a mod's {@code ModLoadingException}, an aggregate carrying each failure as a suppressed exception
	 * ({@code GameData.postRegisterEvents}' roll-back, {@code DeferredWorkQueue.runTasks}), or a step's own throw.
	 */
	public static Throwable stop(ClassLoader cl, String task, Throwable failure) {
		Throwable cause = failure instanceof InvocationTargetException reflected && reflected.getCause() != null
				? reflected.getCause() : failure;
		ForbricLog.error("[Forbric/Lifecycle] %s — the boot stops here, as native NeoForge stops it: NeoForge's mod-loading error "
				+ "follows, and the mods' content does not keep running on what is left", describe(task, cause));
		Throwable stop = nativeStop(cl, task, cause);
		stopping = stop;
		return stop;
	}

	/**
	 * The same for a parallel phase ({@code ModLoader.dispatchParallelTask}) in which every mod took its turn and
	 * {@code failures} are the ones that threw, in mod order: natively they meet in {@code ModList.completableFutureFromExceptionList}
	 * as the suppressed exceptions of one {@code RuntimeException}, which is what {@code waitForFuture} reads. One failure
	 * alone is the same aggregate with one suppressed exception.
	 */
	public static Throwable stopAll(ClassLoader cl, String task, List<? extends Throwable> failures) {
		RuntimeException aggregate = new RuntimeException();
		for (Throwable failure : failures) aggregate.addSuppressed(failure);
		return stop(cl, task, aggregate);
	}

	/**
	 * What {@code FMLModContainer.constructMod} throws for a mod whose constructor, or one of whose
	 * {@code @EventBusSubscriber} classes, threw: {@code new ModLoadingException(ModLoadingIssue.error(
	 * "fml.modloadingissue.failedtoloadmod").withCause(cause).withAffectedMod(modInfo))} (javap of 26.2.0.93). {@code container}
	 * is the mod's NeoForge {@code ModContainer}. When NeoForge's classes cannot build it, {@code cause} itself.
	 */
	public static Throwable failedToLoadMod(ClassLoader cl, Object container, Throwable cause) {
		Throwable unwrapped = cause instanceof InvocationTargetException reflected && reflected.getCause() != null
				? reflected.getCause() : cause;
		try {
			Class<?> issueClass = Class.forName("net.neoforged.fml.ModLoadingIssue", true, cl);
			Object issue = issueClass.getMethod("error", String.class, Object[].class)
					.invoke(null, "fml.modloadingissue.failedtoloadmod", new Object[0]);
			issue = issueClass.getMethod("withCause", Throwable.class).invoke(issue, unwrapped);
			if (container != null) {
				Object info = container.getClass().getMethod("getModInfo").invoke(container);
				Class<?> modInfo = Class.forName("net.neoforged.neoforgespi.language.IModInfo", false, cl);
				issue = issueClass.getMethod("withAffectedMod", modInfo).invoke(issue, info);
			}
			return (Throwable) Class.forName(MOD_LOADING_EXCEPTION, true, cl).getConstructor(issueClass).newInstance(issue);
		} catch (ReflectiveOperationException | LinkageError | RuntimeException unbuilt) {
			Caught.warn(unbuilt, "[Forbric/Lifecycle] could not build NeoForge's failedtoloadmod issue — the boot stops on the "
					+ "mod's own failure instead");
			return unwrapped;
		}
	}

	/** What the stop line says about {@code failure}: NeoForge's roll-back with its causes, or the throw itself. */
	static String describe(Throwable failure) {
		return describe(TASK, failure);
	}

	/**
	 * What the stop line says about {@code failure} in {@code task}: NeoForge's roll-back with its causes, an aggregate's
	 * causes, or the throw itself.
	 */
	static String describe(String task, Throwable failure) {
		StringBuilder causes = new StringBuilder();
		for (Throwable cause : failure.getSuppressed()) causes.append("; ").append(cause);
		if (TASK.equals(task)) {
			if (!rolledBackToVanilla(failure)) return "NeoForge's GameData.postRegisterEvents threw " + failure;
			return "NeoForge's GameData.postRegisterEvents failed for " + failure.getSuppressed().length + " registr(ies) and "
					+ "rolled every registry back to vanilla (RegistryManager.revertToVanilla)" + causes;
		}
		if (failure.getClass() == RuntimeException.class && failure.getMessage() == null && failure.getSuppressed().length > 0) {
			return "NeoForge's mod loading failed in \"" + task + "\" (" + failure.getSuppressed().length + " error(s))" + causes;
		}
		return "NeoForge's mod loading failed in \"" + task + "\": " + failure;
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

	private static Throwable nativeStop(ClassLoader cl, String task, Throwable cause) {
		try {
			Class<?> loader = Class.forName("net.neoforged.fml.ModLoader", true, cl);
			Method waitForTask = loader.getMethod("waitForTask", String.class, Runnable.class, CompletableFuture.class);
			bindModList(cl, loader);
			Runnable noProgress = () -> { };
			waitForTask.invoke(null, task, noProgress, CompletableFuture.failedFuture(cause));
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
	static boolean isModLoadingException(ClassLoader cl, Throwable t) {
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

	/** Failures recorded for a task before the step that ends it, by task: see {@link #pending}. */
	private static final java.util.Map<String, List<Pending>> PENDING = new java.util.LinkedHashMap<>();

	/** One mod's failure waiting for its task to end; {@code modId} may be null when no mod owns it. */
	public record Pending(String modId, Throwable failure) {
	}

	/**
	 * Records {@code failure}, {@code modId}'s, for {@code task} without stopping yet: natively one mod failing a parallel
	 * task does not stop the others' turns, and {@code waitForFuture} reports every failure together when the task ends. The
	 * kernel runs construction in steps (every constructor, then every {@code @EventBusSubscriber}, then
	 * {@code FMLConstructModEvent}); what the earlier steps record here joins the last step's stop ({@link #takePending}),
	 * or {@link #stopPending} stops on it alone.
	 */
	public static synchronized void pending(String task, String modId, Throwable failure) {
		PENDING.computeIfAbsent(task, t -> new java.util.ArrayList<>()).add(new Pending(modId, failure));
	}

	/** The mods {@code task} already has a failure of, which natively take no further part in it. */
	public static synchronized java.util.Set<String> pendingMods(String task) {
		java.util.Set<String> ids = new java.util.LinkedHashSet<>();
		for (Pending p : PENDING.getOrDefault(task, List.of())) if (p.modId() != null) ids.add(p.modId());
		return ids;
	}

	/** Takes what {@link #pending} recorded for {@code task}, in the order it was recorded. */
	public static synchronized List<Throwable> takePending(String task) {
		List<Throwable> failures = new java.util.ArrayList<>();
		for (Pending p : PENDING.getOrDefault(task, List.of())) failures.add(p.failure());
		PENDING.remove(task);
		return failures;
	}

	/** Stops the boot on what {@link #pending} recorded for {@code task}, if anything; returns otherwise. */
	public static void stopPending(ClassLoader cl, String task) {
		List<Throwable> failures = takePending(task);
		if (!failures.isEmpty()) throw propagate(stopAll(cl, task, failures));
	}

	/** Whether {@code t} is the exception this boot stops on. */
	public static boolean isStop(Throwable t) {
		return t != null && t == stopping;
	}

	/** Whether this boot stopped on a mod-loading failure. */
	public static boolean stopped() {
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
	public static <T extends Throwable> RuntimeException propagate(Throwable failure) throws T {
		throw (T) failure;
	}
}
