/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.installer.kernel;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Reads Unicode arguments after JVM startup, bypassing Windows launcher's native-codepage conversion. */
public final class DevLaunch {
	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws Exception {
		if (args.length != 1) throw new IllegalArgumentException("usage: DevLaunch <utf8-launch.json>");
		Map<String, Object> configuration = (Map<String, Object>) Json.parse(
				Files.readString(Path.of(args[0]), StandardCharsets.UTF_8));
		((Map<String, Object>) configuration.get("properties")).forEach(
				(key, value) -> System.setProperty(key, (String) value));
		String[] gameArguments = ((List<String>) configuration.get("arguments")).toArray(String[]::new);
		Class<?> main = Class.forName((String) configuration.get("mainClass"));
		try {
			main.getMethod("main", String[].class).invoke(null, (Object) gameArguments);
		} catch (InvocationTargetException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof Exception error) throw error;
			if (cause instanceof Error error) throw error;
			throw new RuntimeException(cause);
		}
	}
}
