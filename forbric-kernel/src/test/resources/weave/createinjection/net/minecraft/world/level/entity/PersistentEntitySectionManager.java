package net.minecraft.world.level.entity;

import fixture.createinjection.Trail;
import net.neoforged.neoforge.common.CommonHooks;

/**
 * A stand-in for the merged base's section manager, reduced to Callback.onMove. Vanilla's onMove has one long local, the
 * new section; NeoForge keeps the old key in a second one for its event, so at updateStatus two longs are live.
 */
public class PersistentEntitySectionManager {
	/** An entity that was in section {@code from} has moved; its callback is told. */
	public void move(EntityAccess entity, long from) {
		new Callback(entity, from).onMove();
	}

	class Callback {
		private final EntityAccess entity;
		private long currentSectionKey;

		Callback(EntityAccess entity, long currentSectionKey) {
			this.entity = entity;
			this.currentSectionKey = currentSectionKey;
		}

		public void onMove() {
			long newSectionPos = entity.sectionKey();
			if (newSectionPos != this.currentSectionKey) {
				Visibility previousStatus = Visibility.TRACKED;
				long oldSectionKey = this.currentSectionKey;
				this.currentSectionKey = newSectionPos;
				this.updateStatus(previousStatus, Visibility.TICKING);
				CommonHooks.onEntityEnterSection(entity, oldSectionKey, newSectionPos);
			}
		}

        /** Native counterpart: only the computed destination key is a long local at the original callback point. */
        public void nativeMove() {
            long newSectionPos=entity.sectionKey();
            if(newSectionPos!=this.currentSectionKey) {
                this.currentSectionKey=newSectionPos;
                this.updateStatus(Visibility.TRACKED,Visibility.TICKING);
            }
        }

		private void updateStatus(Visibility from, Visibility to) {
			Trail.add("status " + from + "->" + to);
		}
	}
}
