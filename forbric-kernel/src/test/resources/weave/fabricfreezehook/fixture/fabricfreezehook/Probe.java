package fixture.fabricfreezehook;

import java.lang.reflect.Method;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;

/**
 * Boots the fake server the way the kernel does: the native bootstrap, then the window the Fabric main registers in,
 * with Fabric's registry freeze point around the freeze that closes it — each hook called only where it exists, as
 * {@code KernelLifecycle.fabricFreezePoint} calls it.
 */
public class Probe {
	public String run() throws ReflectiveOperationException {
		Bootstrap.bootStrap();
		Client.create();
		Trace.add("kernel:open");
		BuiltInRegistries.WRITABLE_REGISTRY.unfreeze();
		Create.onInitialize();
		hook("forbric$fabricFreezeHead", "hook:head");
		Trace.add("kernel:freeze");
		BuiltInRegistries.WRITABLE_REGISTRY.freeze();
		hook("forbric$fabricFreezeTail", "hook:tail");
		return String.join(",", Trace.LINES);
	}

	private static void hook(String name, String step) throws ReflectiveOperationException {
		Method hook;
		try {
			hook = BuiltInRegistries.class.getMethod(name);
		} catch (NoSuchMethodException absent) {
			return;
		}
		Trace.add(step);
		hook.invoke(null);
	}
}
