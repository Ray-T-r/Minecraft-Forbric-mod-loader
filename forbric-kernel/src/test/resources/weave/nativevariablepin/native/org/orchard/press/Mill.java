package org.orchard.press;

/** As the guest's loader has it: ripeness first, then sweetness, both in scope at the pour. */
public class Mill {
	public String crush(String fruit) {
		boolean ripe = fruit.startsWith("r");
		String juice = "juice-" + fruit;
		boolean sweet = juice.length() > 12;
		Cup.pour(juice, sweet);
		return juice + (ripe ? "!" : "");
	}
}
