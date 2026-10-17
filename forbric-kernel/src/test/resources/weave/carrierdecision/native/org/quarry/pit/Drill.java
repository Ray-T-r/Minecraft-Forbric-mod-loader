package org.quarry.pit;

import fixture.carrierdecision.Trace;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/** As the guest's own loader has it: every decision is the drill's own. */
public class Drill {
	public Bit bit;
	public final Map<String, String> items = new HashMap<>();
	public Map<String, String> frozen;

	public Drill(Bit bit) {
		this.bit = bit;
	}

	public boolean keepsBoring(Bit held) {
		if (held != null && Bit.sameEdge(held, this.bit)) {
			Trace.add("keep");
			return true;
		}
		Trace.add("reset");
		return false;
	}

	public boolean takes(Stack stack, Holder holder) {
		if (holder.value().fits(stack)) {
			Trace.add("took");
			return true;
		}
		Trace.add("left");
		return false;
	}

	public int price(Stack stack, Holder holder) {
		Recipe recipe = holder.value();
		boolean ok = recipe.fits(stack);
		if (stack.name.equals("gold")) ok = true;
		Trace.add("priced " + ok);
		return ok ? 1 : -1;
	}

	public void feed(Stack stack, Object source) {
		if (!this.canFeed(stack)) return;
		Trace.add("fed " + stack.name);
	}

	public boolean canFeed(Stack stack) {
		return !stack.name.equals("stone");
	}

	public boolean empty() {
		if (items.isEmpty()) return false;
		Map<String, String> copy = Bags.copy(items);
		items.clear();
		Trace.add("emptied " + new TreeSet<>(copy.keySet()));
		return true;
	}

	public Map<String, String> freeze() {
		Map<String, String> loaded = new HashMap<>();
		loaded.put("quarry", "1");
		Map<String, String> storage = Map.copyOf(loaded);
		this.frozen = storage;
		Trace.add("frozen " + new TreeSet<>(storage.keySet()));
		return storage;
	}

	public void shine(Bit held) {
		if (Bit.sameEdge(held, this.bit)) Trace.add("shine");
		Trace.add("shone");
	}
}
