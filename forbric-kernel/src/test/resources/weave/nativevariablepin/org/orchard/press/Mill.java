package org.orchard.press;

/** As merged: the carrier asks its own sweetness question first, and keeps ripeness for later. */
public class Mill {
	public String crush(String fruit) {
		String juice = "juice-" + fruit;
		boolean sweet = Taster.sweet(juice);
		Cup.pour(juice, sweet);
		boolean ripe = fruit.startsWith("r");
		return juice + (ripe ? "!" : "");
	}
}
