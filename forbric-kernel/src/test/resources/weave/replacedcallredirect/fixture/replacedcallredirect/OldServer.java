package fixture.replacedcallredirect;

/** Whether the guest targets an old server, as ViaFabricPlus' target version would say. */
public final class OldServer {
	private OldServer() {
	}

	public static boolean on() {
		return Boolean.getBoolean("fixture.replacedcallredirect.oldServer");
	}
}
