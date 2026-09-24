package net.forbric.kernel.runtime.transfer;

/**
 * Energy arithmetic shared by every bridge direction. One Forge Energy unit (FE, MinecraftForge and NeoForge) is one
 * Team Reborn Energy unit (E): the three APIs describe the same quantity and no exchange rate is applied anywhere.
 *
 * <p>The only unit difference is width. Forge and NeoForge move {@code int} amounts, Reborn moves {@code long}. A long
 * request is clamped to what the int side can express BEFORE anything moves; the int side then reports what it
 * really moved, and that exact amount is what the long side sees. The unrepresentable remainder is never moved, so
 * it stays in its source: nothing is rounded after a mutation, and no energy is created or destroyed. A long AMOUNT
 * read through an int API saturates at {@link Integer#MAX_VALUE}; that is a read, never a transfer.
 *
 * <p>Pure: no game or API type, so the unit tests exercise it directly.
 */
public final class EnergyUnits {
	private EnergyUnits() { }

	/** The largest part of a long request an int API can be asked for; the rest stays with the caller. */
	public static int request(long maximum) {
		if (maximum < 0) throw new IllegalArgumentException("Negative energy amount: " + maximum);
		return (int) Math.min(maximum, Integer.MAX_VALUE);
	}

	/** A provider's answer to a request of {@code maximum}: it must lie in [0, maximum], or the operation is rejected. */
	public static long moved(long moved, long maximum) {
		if (moved < 0 || moved > maximum) throw new IllegalStateException("Energy provider returned an invalid amount: " + moved + "/" + maximum);
		return moved;
	}

	/** A long amount or capacity read through an int API. */
	public static int saturated(long amount) {
		return (int) Math.min(Math.max(amount, 0), Integer.MAX_VALUE);
	}

	/** An amount or capacity a provider reports; a negative one is a broken provider, not a quantity. */
	public static long reported(long amount) {
		if (amount < 0) throw new IllegalStateException("Energy provider reported a negative amount/capacity: " + amount);
		return amount;
	}
}
