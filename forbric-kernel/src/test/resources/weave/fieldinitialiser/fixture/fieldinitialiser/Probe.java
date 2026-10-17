package fixture.fieldinitialiser;

import org.harbor.shelf.Crate;

import com.tallyman.mark.Marked;

/** Builds a crate without children and one with two, and reads the guest's tag on each crate and copy. */
public class Probe {
	public String probe() {
		Crate lone = new Crate("lone");
		Crate stack = Crate.withChildren("stack", "a", "b");
		StringBuilder out = new StringBuilder("lone=" + ((Marked) (Object) lone).tallyman$tag());
		out.append(" stack=").append(((Marked) (Object) stack).tallyman$tag());
		for (Crate child : stack.children()) out.append(' ').append(child.name()).append('=').append(((Marked) (Object) child).tallyman$tag());
		return out.toString();
	}
}
