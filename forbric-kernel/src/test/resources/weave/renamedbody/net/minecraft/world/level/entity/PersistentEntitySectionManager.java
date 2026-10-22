package net.minecraft.world.level.entity;

/**
 * Hand-written stand-in with the SHAPE of one carrier-renames.txt row, no game code: NeoForge's addEntity posts its join
 * event and calls vanilla's body, which it renamed addEntityWithoutEvent and gave the same descriptor.
 */
public class PersistentEntitySectionManager {
	public final StringBuilder trace = new StringBuilder();

	public boolean addEntity(EntityAccess entity, boolean worldGenSpawned) {
		trace.append("event;");
		return addEntityWithoutEvent(entity, worldGenSpawned);
	}

	/** Vanilla's body: true exactly when it got past the refusal, and so made the ticking call. */
	private boolean addEntityWithoutEvent(EntityAccess entity, boolean worldGenSpawned) {
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
