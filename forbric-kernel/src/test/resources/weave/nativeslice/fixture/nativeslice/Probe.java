package fixture.nativeslice;

import org.vault.keep.Entry;
import org.vault.keep.Key;
import org.vault.keep.Ledger;
import org.vault.keep.Mode;

/** Fetches and prunes a removed entry, a live one, a stale one, a pending one and a missing one. */
public class Probe {
	public String probe() {
		StringBuilder out = new StringBuilder();
		String[][] cases = {{"fetch", "gone", "CHECK"}, {"fetch", "live", "CHECK"}, {"fetch", "late", "CHECK"}, {"fetch", "fresh", "IMMEDIATE"},
				{"prune", "gone", ""}, {"prune", "stale", ""}, {"prune", "fresh", ""}};
		for (String[] c : cases) {
			Ledger ledger = new Ledger();
			ledger.entries.put(new Key("gone"), new Entry("gone", true, false));
			ledger.entries.put(new Key("live"), new Entry("live", false, false));
			ledger.entries.put(new Key("stale"), new Entry("stale", false, true));
			ledger.pending.put(new Key("late"), "note");
			Trace.LINES.clear();
			String result;
			if (c[0].equals("fetch")) {
				Entry entry = ledger.fetch(new Key(c[1]), Mode.valueOf(c[2]));
				result = entry == null ? "null" : entry.name;
			} else {
				ledger.prune(new Key(c[1]));
				result = ledger.entries.containsKey(new Key(c[1])) ? "kept" : "dropped";
			}
			out.append(out.isEmpty() ? "" : " | ").append(c[0]).append(' ').append(c[1]).append(": ").append(String.join(", ", Trace.LINES))
					.append(Trace.LINES.isEmpty() ? "" : ", ").append("-> ").append(result);
		}
		return out.toString();
	}
}
