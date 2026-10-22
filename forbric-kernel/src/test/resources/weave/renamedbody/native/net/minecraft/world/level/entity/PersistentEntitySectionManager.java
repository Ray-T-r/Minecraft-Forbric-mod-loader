package net.minecraft.world.level.entity;

/** As a Fabric mod's game has it: addEntity carries the body itself. */
public class PersistentEntitySectionManager {
	public final StringBuilder trace = new StringBuilder();

	public boolean addEntity(EntityAccess entity, boolean worldGenSpawned) {
		if (worldGenSpawned && entity != null) return false;
		entity = new Ticket(entity).entity;
		trace.append("body;");
		if (Visibility.TICKING.isTicking()) trace.append("ticking;");
		return true;
	}

	/** A nested class with a private constructor, as vanilla's section callback is: the body cannot be inlined elsewhere. */
	private static final class Ticket {
		private final EntityAccess entity;

		private Ticket(EntityAccess entity) {
			this.entity = entity;
		}
	}
}
