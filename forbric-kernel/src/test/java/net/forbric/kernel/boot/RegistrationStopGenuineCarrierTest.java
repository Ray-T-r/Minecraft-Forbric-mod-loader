/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * {@link RegistrationStop} on NeoForge 26.2.0.93's own {@code ModLoader} from the staged carrier: what
 * {@code GameData.postRegisterEvents} throws after its roll-back (a {@code RuntimeException} carrying each registry's
 * failure as a suppressed exception) becomes the {@code ModLoadingException} native NeoForge stops the boot with — built by
 * NeoForge's {@code ModLoader.waitForTask}, so one issue per failure: a mod listener's own issue as
 * {@code ModContainer.acceptEvent} made it, and {@code fml.modloadingissue.uncaughterror} for "Registry initialization" with
 * the cause for anything else — and NeoForge's own mod list is the one it empties (its {@code cancelLoading}).
 */
class RegistrationStopGenuineCarrierTest {
	private static final Pattern ARTIFACT_PATH = Pattern.compile("\"artifact\"\\s*:\\s*\\{[^}]*?\"path\"\\s*:\\s*\"([^\"]+)\"");

	private URLClassLoader genuine;

	@AfterEach
	void forget() throws Exception {
		Field stopping = RegistrationStop.class.getDeclaredField("stopping");
		stopping.setAccessible(true);
		stopping.set(null, null);
		if (genuine != null) genuine.close();
	}

	/** NeoForge's carrier and patched game over Minecraft's libraries; nothing is transformed, nothing of the game runs. */
	private static URLClassLoader genuine() throws Exception {
		Path game = TestFixtures.stagedBase(), neo = TestFixtures.neoCarrier();
		TestFixtures.requireFiles(Fixture.STAGED, "the genuine NeoForge base or its carrier is absent", game, neo);
		List<URL> urls = new ArrayList<>(List.of(neo.toUri().toURL(), game.toUri().toURL()));
		Path root = TestFixtures.minecraftDir().resolve("libraries");
		Path version = root.resolveSibling("versions/26.2/26.2.json");
		TestFixtures.require(Fixture.MC_LIBRARIES, Files.isRegularFile(version), "no local Minecraft 26.2 version JSON");
		Matcher artifact = ARTIFACT_PATH.matcher(Files.readString(version));
		while (artifact.find()) {
			Path jar = root.resolve(artifact.group(1));
			if (Files.isRegularFile(jar)) urls.add(jar.toUri().toURL());
		}
		return new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
	}

	@Test
	void whatPostRegisterEventsThrowsBecomesNeoForgesOwnModLoadingError() throws Exception {
		genuine = genuine();
		ClassLoader cl = genuine;
		Class<?> modList = Class.forName("net.neoforged.fml.ModList", true, cl);
		Object list = modList.getMethod("of", List.class, List.class).invoke(null, List.of(), List.of());
		Class<?> issueClass = Class.forName("net.neoforged.fml.ModLoadingIssue", true, cl);
		Class<?> exceptionClass = Class.forName("net.neoforged.fml.ModLoadingException", true, cl);
		Class<?> modLoader = Class.forName("net.neoforged.fml.ModLoader", true, cl);
		modLoader.getMethod("clearLoadingIssues").invoke(null);

		// What ModContainer.acceptEvent throws for a mod's failing listener (javap of 26.2.0.93, minus the mod's IModInfo).
		IllegalStateException listener = new IllegalStateException("a mod's RegisterEvent listener failed");
		Object issue = issueClass.getMethod("error", String.class, Object[].class)
				.invoke(null, "fml.modloadingissue.errorduringevent", new Object[] {"net.neoforged.neoforge.registries.RegisterEvent"});
		issue = issueClass.getMethod("withCause", Throwable.class).invoke(issue, listener);
		Throwable fromListener = (Throwable) exceptionClass.getConstructor(issueClass).newInstance(issue);
		// Anything else inside the per-registry try: a guest handler woven after the dispatch.
		IllegalStateException guest = new IllegalStateException("a guest handler failed after the dispatch");
		RuntimeException aggregate = new RuntimeException();
		aggregate.addSuppressed(fromListener);
		aggregate.addSuppressed(guest);

		Throwable stop = RegistrationStop.stop(cl, aggregate);

		assertSame(exceptionClass, stop.getClass(), "NeoForge's own ModLoadingException, from the game's loader: " + stop);
		List<?> issues = (List<?>) exceptionClass.getMethod("getIssues").invoke(stop);
		assertEquals(2, issues.size(), issues.toString());
		assertEquals("fml.modloadingissue.errorduringevent", key(issues.get(0)), "the listener's own issue, unchanged");
		assertSame(listener, cause(issues.get(0)));
		assertEquals("fml.modloadingissue.uncaughterror", key(issues.get(1)), issues.toString());
		assertEquals(List.of(RegistrationStop.TASK), issueClass.getMethod("translationArgs").invoke(issues.get(1)));
		assertSame(guest, cause(issues.get(1)));
		Field kept = modLoader.getDeclaredField("modList");
		kept.setAccessible(true);
		assertSame(list, kept.get(null), "NeoForge's ModLoader empties the mod list the kernel published, as natively");
		assertTrue(RegistrationStop.isStop(stop) && RegistrationStop.stopped());
		assertEquals(2, ((List<?>) modLoader.getMethod("getLoadingIssues").invoke(null)).size(),
				"the issues are NeoForge's loading issues, as after a native failure");
		modLoader.getMethod("clearLoadingIssues").invoke(null);
	}

	private static String key(Object issue) throws Exception {
		return (String) issue.getClass().getMethod("translationKey").invoke(issue);
	}

	private static Throwable cause(Object issue) throws Exception {
		return (Throwable) issue.getClass().getMethod("cause").invoke(issue);
	}
}
