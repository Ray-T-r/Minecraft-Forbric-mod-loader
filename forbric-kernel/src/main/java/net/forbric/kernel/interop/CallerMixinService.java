/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/** A caller-owned platform view; Mixin's registered service and every other caller keep the real name. */
public final class CallerMixinService {
    private CallerMixinService() { }
    public static Object getService(Class<?> caller, String platformName) {
        try {
            Class<?> provider = Class.forName("org.spongepowered.asm.service.MixinService", false, caller.getClassLoader());
            var accessor = provider.getMethod("getService"); Object service = accessor.invoke(null);
            Class<?> contract = accessor.getReturnType();
            if (!"Forbric".equals(contract.getMethod("getName").invoke(service))) return service;
            return Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, (proxy, method, arguments) -> {
                if (method.getName().equals("getName") && method.getParameterCount() == 0) return platformName;
                try { return method.invoke(service, arguments); }
                catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot create the caller's Mixin platform view", failure); }
    }
}
