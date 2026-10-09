package net.minecraft.client.renderer;

import java.util.List;

/**
 * Fixture stand-in for a merged renderer: {@code submit}'s guard was folded into an {@code if} block, so the two locals
 * its body declares end at the join the tail sits on. {@code label} declares its local before the guard, so it is in
 * scope at the tail on every path, as on vanilla.
 */
public class LecternRenderer {
	public void submit(Object book, List<String> out, int light) {
		if (book != null) {
			int lines = out.size() + light;
			String title = "book:" + book;
			out.add(title + "/" + lines);
		}
	}

	public void label(Object book, List<String> out) {
		String tag = "tag:" + book;
		if (book != null) {
			out.add(tag);
		}
	}
}
