package org.atelier.scene;

import fixture.nativecallinline.Trace;

public class Actor {
	public Item held = new Item("apple");

	public Token discard(Item item, boolean silent, boolean named) {
		Trace.add("actor-discard:" + item.name);
		return new Token(item);
	}
}
