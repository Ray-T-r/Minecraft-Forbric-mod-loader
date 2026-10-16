package org.quarry.yard;

import java.util.function.Consumer;

/** As the mod's loader has it: the bundle is named before it is handed on. */
public class Crate {
	public Bundle stored = new Bundle("hay");

	public Bundle contents() {
		return stored == null ? new Bundle("empty") : stored;
	}

	public void each(Slot slot, Consumer<String> consumer) {
		Bundle bundle = this.stored == null ? new Bundle("empty") : this.stored;
		bundle.forEach(slot, consumer);
		consumer.accept("tail");
	}
}
