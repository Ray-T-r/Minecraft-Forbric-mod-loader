/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.Map;
import net.forbric.kernel.util.ForbricLog;

/** A lifecycle registry usable by any mod; closing resources never touches a lazy singleton factory. */
public final class ManagedWorkerResources {
	private record Resource(Runnable drain, Runnable stop) { }
	private static final Map<Object, Resource> RESOURCES = new IdentityHashMap<>();
	private ManagedWorkerResources() { }
	public static synchronized void register(Object owner, Runnable drain, Runnable stop) {
		if (owner == null || drain == null || stop == null) throw new NullPointerException("worker resource contract");
		RESOURCES.put(owner, new Resource(drain, stop));
	}
	/** Entry used by the structural injector; both methods were proved before the class was defined. */
	public static void register(Object owner, String drain, String stop) {
		register(owner, () -> invoke(owner, drain), () -> invoke(owner, stop));
	}
	public static synchronized void unregister(Object owner) { RESOURCES.remove(owner); }
	public static void close(ClassLoader loader) {
		ArrayList<Map.Entry<Object, Resource>> closing = new ArrayList<>();
		synchronized (ManagedWorkerResources.class) {
			for (var entry : RESOURCES.entrySet()) if (entry.getKey().getClass().getClassLoader() == loader) closing.add(Map.entry(entry.getKey(), entry.getValue()));
			for (var entry : closing) RESOURCES.remove(entry.getKey());
		}
		for (var entry : closing) {
			try { entry.getValue().drain().run(); }
			catch (Throwable failure) { ForbricLog.warn("[Forbric/Shutdown] could not drain registered worker resource " + entry.getKey().getClass().getName(), failure); }
			finally {
				try { entry.getValue().stop().run(); }
				catch (Throwable failure) { ForbricLog.warn("[Forbric/Shutdown] could not stop registered worker resource " + entry.getKey().getClass().getName(), failure); }
			}
		}
	}
	private static void invoke(Object owner, String method) {
		try { owner.getClass().getMethod(method).invoke(owner); }
		catch (ReflectiveOperationException failure) { throw new IllegalStateException("Registered worker callback failed", failure); }
	}
}
