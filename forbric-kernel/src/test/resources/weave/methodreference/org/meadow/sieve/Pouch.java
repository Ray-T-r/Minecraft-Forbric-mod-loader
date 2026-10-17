package org.meadow.sieve;

public final class Pouch implements PouchExtension {
	private final Kind kind;

	public Pouch(Kind kind) {
		this.kind = kind;
	}

	public Kind kind() {
		return kind;
	}

	public boolean isEmptyPouch() {
		return this.kind == Kind.EMPTY;
	}
}
