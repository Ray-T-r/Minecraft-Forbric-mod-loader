package fixture.fabricfreezehook;

/**
 * Stands in for {@code Minecraft.getInstance()}: null until the client exists, which on a Fabric client is before its
 * entrypoints run and long after the bootstrap.
 */
public final class Client {
	public static Client instance;

	private Client() {
	}

	/** {@code Minecraft.<init>}, which sets the instance before the kernel's client entrypoint window opens. */
	public static void create() {
		Trace.add("client:new");
		instance = new Client();
	}
}
