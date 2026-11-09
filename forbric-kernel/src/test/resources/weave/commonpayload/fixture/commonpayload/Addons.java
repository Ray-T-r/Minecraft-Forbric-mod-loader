package fixture.commonpayload;

import java.util.IdentityHashMap;
import java.util.Map;

/** Which fabric-api addon each listener has: the fixture's stand-in for NetworkHandlerExtensions.getAddon. */
public final class Addons {
	private static final Map<Object, Object> ADDONS = new IdentityHashMap<>();

	private Addons() {
	}

	public static void set(Object listener, Object addon) {
		ADDONS.put(listener, addon);
	}

	public static Object of(Object listener) {
		return ADDONS.get(listener);
	}
}
