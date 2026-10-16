package org.quarry.yard;

import java.util.function.Consumer;

public class Bundle {
	public final String name;

	public Bundle(String name) {
		this.name = name;
	}

	public void forEach(Slot slot, Consumer<String> consumer) {
		consumer.accept(name + "/" + slot.name().toLowerCase());
	}
}
