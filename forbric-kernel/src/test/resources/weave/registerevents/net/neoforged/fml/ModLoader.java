package net.neoforged.fml;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import fixture.registerevents.Trace;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;

/**
 * Stand-in: NeoForge's own fan-out, which walks ModList and lets the first failure end the dispatch, and waitForTask, which
 * turns a failed init task into the ModLoadingException the boot ends on (one issue per suppressed failure).
 */
public class ModLoader {
	/** As NeoForge's: set by gatherAndInitializeMods, emptied by cancelLoading. */
	private static ModList modList;

	public static void postEventWrapContainerInModOrder(Event event) {
		Trace.add("native-dispatch");
		for (EventPriority phase : EventPriority.values()) {
			for (ModContainer container : ModList.get().getSortedMods()) {
				ModLoadingContext.get().setActiveContainer(container);
				container.acceptEvent(phase, event);
				ModLoadingContext.get().setActiveContainer(null);
			}
		}
	}

	public static void postEvent(Event event) {
		Trace.add("native-post");
		for (EventPriority phase : EventPriority.values()) {
			for (ModContainer container : ModList.get().getSortedMods()) container.acceptEvent(phase, event);
		}
	}

	public static void waitForTask(String name, Runnable periodicTask, CompletableFuture<?> future) {
		try {
			future.get();
		} catch (ExecutionException failed) {
			StringBuilder issues = new StringBuilder("Loading errors encountered:");
			for (Throwable issue : failed.getCause().getSuppressed()) issues.append(" - ").append(issue.getMessage());
			Trace.add("waitForTask:" + name + (modList == ModList.get() ? "" : "(no mod list)"));
			throw new ModLoadingException(issues.toString(), failed.getCause());
		} catch (InterruptedException interrupted) {
			throw new IllegalStateException(interrupted);
		}
	}
}
