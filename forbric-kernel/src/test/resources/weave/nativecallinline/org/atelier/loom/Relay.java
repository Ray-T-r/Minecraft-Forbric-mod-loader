package org.atelier.loom;

import fixture.nativecallinline.Trace;
import org.atelier.scene.Actor;
import org.atelier.scene.Item;
import org.atelier.scene.Token;

/** The carrier's hook around a discard: it makes the discard itself, then posts. */
public final class Relay {
	private Relay() {
	}

	public static Token toss(Actor actor, Item item, boolean silent, boolean named) {
		Trace.add("relay-begin");
		Token token = actor.discard(item, silent, named);
		Trace.add("relay-end");
		return token;
	}
}
