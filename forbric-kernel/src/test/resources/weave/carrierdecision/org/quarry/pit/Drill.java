package org.quarry.pit;

import fixture.carrierdecision.Trace;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import org.quarry.carrier.Hooks;

/**
 * As merged: the carrier decides in the drill's place. {@code keepsBoring} asks the bit whether it dulls (the opposite
 * question); {@code takes} and {@code price} ask the stack whether it accepts the holder; {@code feed} asks the carrier's
 * hook; {@code empty} builds its own map where the drill copied; {@code freeze} keeps the loaded map itself where the drill
 * copied it; {@code shine} asks the bit too, but skips another block there.
 */
public class Drill {
	public Bit bit;
	public final Map<String, String> items = new HashMap<>();
	public Map<String, String> frozen;

	public Drill(Bit bit) {
		this.bit = bit;
	}

	public boolean keepsBoring(Bit held) {
		if (held != null && !this.bit.dulls(held)) {
			Trace.add("keep");
			return true;
		}
		Trace.add("reset");
		return false;
	}

	public boolean takes(Stack stack, Holder holder) {
		if (stack.accepts(holder)) {
			Trace.add("took");
			return true;
		}
		Trace.add("left");
		return false;
	}

	public int price(Stack stack, Holder holder) {
		Recipe recipe = holder.value();
		boolean ok = stack.accepts(holder);
		if (stack.name.equals("gold")) ok = true;
		Trace.add("priced " + ok);
		return ok ? 1 : -1;
	}

	public void feed(Stack stack, Object source) {
		if (!Hooks.mayFeed(this, stack, source)) return;
		Trace.add("fed " + stack.name);
	}

	public boolean canFeed(Stack stack) {
		return !stack.name.equals("stone");
	}

	public boolean empty() {
		if (items.isEmpty()) return false;
		Map<String, String> copy = new HashMap<>(items.size());
		for (Map.Entry<String, String> entry : items.entrySet()) copy.put(entry.getKey(), entry.getValue());
		for (String key : copy.keySet()) items.remove(key);
		Trace.add("emptied " + new TreeSet<>(copy.keySet()));
		return true;
	}

	public Map<String, String> freeze() {
		Map<String, String> loaded = new HashMap<>();
		loaded.put("quarry", "1");
		Map<String, String> storage = loaded;
		Hooks.capture(loaded);
		this.frozen = storage;
		Trace.add("frozen " + new TreeSet<>(storage.keySet()));
		return storage;
	}

	public void shine(Bit held) {
		if (!this.bit.dulls(held)) Trace.add("glint");
		Trace.add("shone");
	}
}
