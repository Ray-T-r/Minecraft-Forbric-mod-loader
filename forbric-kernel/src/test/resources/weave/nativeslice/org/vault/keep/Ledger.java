package org.vault.keep;

import fixture.nativeslice.Trace;
import java.util.HashMap;
import java.util.Map;

/**
 * As merged: a removed entry is taken out first, before anything is created, on the same map with the same key under the
 * same conditions; prune takes out a stale entry instead (another condition).
 */
public class Ledger {
	public final Map<Key, Entry> entries = new HashMap<>();
	public final Map<Key, String> pending = new HashMap<>();

	public Entry fetch(Key key, Mode mode) {
		Entry entry = this.entries.get(key);
		if (entry != null && entry.isRemoved()) {
			this.entries.remove(key);
			entry = null;
		}
		if (entry == null) {
			String note = this.pending.remove(key);
			if (note != null) {
				Entry promoted = this.promote(key, note);
				if (promoted != null) return promoted;
			}
		}
		if (entry == null && mode == Mode.IMMEDIATE) {
			entry = this.create(key);
			if (entry != null) this.entries.put(key, entry);
		}
		return entry;
	}

	public void prune(Key key) {
		Entry entry = this.entries.get(key);
		if (entry != null && entry.isStale()) {
			this.entries.remove(key);
			return;
		}
		if (entry == null) this.create(key);
	}

	Entry promote(Key key, String note) {
		Trace.add("promote " + key);
		return new Entry(key.name() + ":" + note, false, false);
	}

	Entry create(Key key) {
		Trace.add("create " + key);
		return new Entry(key.name(), false, false);
	}
}
