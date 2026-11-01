package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The Operation a Fabric damage wrap gets at NeoForge's call: the player it was handed stands for the entity again. */
class KernelItemDamageTest {
	private URLClassLoader loader;
	private Class<?> operation;
	private Method relay, call;

	@BeforeEach void load() throws Exception {
		String extras = System.getProperty("forbric.mixinExtrasForTests", "");
		Path runtime = Path.of(System.getProperty("forbric.test.runtimeClasses", "build/classes/java/runtime"));
		assertFalse(extras.isEmpty(), "Gradle hands every test task MixinExtras, from nestedMods, as forbric.mixinExtrasForTests");
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(runtime), "game-side classes required");
		loader = new URLClassLoader(new URL[] { runtime.toUri().toURL(), Path.of(extras).toUri().toURL() }, ClassLoader.getPlatformClassLoader());
		operation = loader.loadClass("com.llamalad7.mixinextras.injector.wrapoperation.Operation");
		relay = loader.loadClass("net.forbric.kernel.runtime.KernelItemDamage").getDeclaredMethod("relay", operation, Object.class, Object.class);
		call = operation.getMethod("call", Object[].class);
	}

	@AfterEach void close() throws Exception {
		if (loader != null) loader.close();
	}

	private Object neoForge(List<Object[]> seen) {
		return Proxy.newProxyInstance(loader, new Class<?>[] { operation }, (proxy, method, args) -> {
			if (!method.getName().equals("call")) return method.invoke(this, args);
			seen.add((Object[]) args[0]);
			return null;
		});
	}

	@Test void theValueTheHandlerWasHandedStandsForTheEntity() throws Exception {
		List<Object[]> seen = new ArrayList<>();
		Object player = "player", zombie = "zombie";
		Object asPlayer = relay.invoke(null, neoForge(seen), player, player);
		Object[] args = { "stack", 3, "level", player, "consumer" };
		call.invoke(asPlayer, (Object) args);
		assertEquals(List.of("stack", 3, "level", player, "consumer"), Arrays.asList(seen.getFirst()));
		// A mob: vanilla handed the wrap null, NeoForge's call wants the mob.
		Object asMob = relay.invoke(null, neoForge(seen), zombie, null);
		Object[] mob = { "stack", 1, "level", null, "consumer" };
		call.invoke(asMob, (Object) mob);
		assertEquals(List.of("stack", 1, "level", zombie, "consumer"), Arrays.asList(seen.get(1)), "the mob itself reaches NeoForge's call");
		assertNull(mob[3], "the handler's own array is not written");
		// The amount the handler computed is the one passed on.
		call.invoke(asMob, (Object) new Object[] { "stack", 0, "level", null, "consumer" });
		assertEquals(0, seen.get(2)[1]);
	}

	@Test void aPlayerTheHandlerChoseIsPassedAsItIs() throws Exception {
		List<Object[]> seen = new ArrayList<>();
		Object relayed = relay.invoke(null, neoForge(seen), "zombie", null);
		call.invoke(relayed, (Object) new Object[] { "stack", 1, "level", "otherPlayer", "consumer" });
		assertEquals("otherPlayer", seen.getFirst()[3]);
	}
}
