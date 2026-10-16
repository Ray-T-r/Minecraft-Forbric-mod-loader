package org.vault.keep;

public class Entry {
	public final String name;
	private final boolean removed, stale;

	public Entry(String name, boolean removed, boolean stale) {
		this.name = name;
		this.removed = removed;
		this.stale = stale;
	}

	public boolean isRemoved() {
		return removed;
	}

	public boolean isStale() {
		return stale;
	}
}
