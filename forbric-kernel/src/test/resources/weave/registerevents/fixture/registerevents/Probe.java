package fixture.registerevents;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;

/**
 * Boots the registration step the way the kernel's window does: NeoForge's baseline container and three NeoForge mods
 * published (one of them "thrower", whose listener fails on registry b), then the kernel's
 * {@code KernelLifecycle.dispatchRegisterEvents} with the Fabric mains stood in by one trace line. Returns the trace.
 */
public class Probe {
	public String run() throws Exception {
		ModContainer baseline = new ModContainer("neoforge");
		List<ModContainer> mods = List.of(new ModContainer("first"), new ModContainer("thrower"), new ModContainer("last"));
		ModList.get().mods.addAll(mods);

		Class<?> lifecycle = Class.forName("net.forbric.kernel.boot.KernelLifecycle");
		Field field = lifecycle.getDeclaredField("baselineContainer");
		field.setAccessible(true);
		field.set(null, baseline);
		Class<?> loader = Class.forName("net.forbric.kernel.boot.KernelModLoader");
		Class<?> identity = Class.forName("net.forbric.kernel.boot.KernelModLoader$NeoIdentity");
		Constructor<?> make = identity.getDeclaredConstructor(Object.class, Object.class);
		make.setAccessible(true);
		Map<String, Object> published = new LinkedHashMap<>();
		for (ModContainer mod : mods) published.put(mod.getModId(), make.newInstance(null, mod));
		Field neo = loader.getDeclaredField("publishedNeo");
		neo.setAccessible(true);
		neo.set(null, Map.copyOf(published));

		Method dispatch = lifecycle.getDeclaredMethod("dispatchRegisterEvents", ClassLoader.class, Runnable.class);
		dispatch.setAccessible(true);
		Runnable fabricMains = () -> Trace.add("kernel:fabric-mains");
		int posted = (int) dispatch.invoke(null, Probe.class.getClassLoader(), fabricMains);
		return "posted=" + posted + " " + String.join(",", Trace.LINES);
	}
}
