package fixture.nativecallinline;

import org.atelier.scene.Backstage;
import org.atelier.scene.Brush;
import org.atelier.scene.Item;
import org.atelier.scene.Panel;
import org.atelier.scene.Stage;
import org.atelier.scene.World;

/** One frame painted over a backdrop, two drops (one the guest cancels), one tick over two walkers, and a subclass frame. */
public class Probe {
	public String probe() {
		Stage stage = new Stage();
		stage.push(new Panel("back"));
		stage.render(new Brush(), 3, 4);
		Trace.add("|");
		stage.drop(false);
		stage.held = new Item("pear");
		stage.drop(false);
		Trace.add("|");
		stage.tick(new World());
		Trace.add("|");
		new Backstage().render(new Brush(), 5, 6);
		return String.join(",", Trace.LINES);
	}
}
