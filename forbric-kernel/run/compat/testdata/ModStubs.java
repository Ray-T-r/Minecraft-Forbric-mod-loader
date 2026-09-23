/** Compiled into the test's "mod" jar: a mod class, and a Mixin-style class declaring the field name it adds. */
public class ModStubs {
	public static class ModHolder { Object held; }
	public static class AddedMixin { Object injected; }
}
