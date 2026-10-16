package org.quarry.yard;

import java.util.function.Consumer;

/** As merged: the carrier hands its own lookup's result straight to the call. */
public class Crate {
	public Bundle stored = new Bundle("hay");

	public Bundle contents() {
		return stored == null ? new Bundle("empty") : stored;
	}

	public void each(Slot slot, Consumer<String> consumer) {
		this.contents().forEach(slot, consumer);
		consumer.accept("tail");
	}
}
