package org.quarry.stone;

/** As the guest's loader has it: the shatter is made here, once, when the spot was cleared. */
public class Mason {
	public final Yard yard;
	public final boolean careful;

	public Mason(boolean careful, boolean yields) {
		this.careful = careful;
		this.yard = new Yard(yields);
	}

	public boolean chip(Point at) {
		Slab slab = this.yard.slabAt(at);
		Relic relic = this.yard.relicAt(at);
		Slab shaped = slab.shapedBy(this.yard, at);
		boolean gone = this.yard.clear(at);
		if (gone) shaped.shatter(this.yard, at, shaped);
		if (careful) return true;
		boolean keep = shaped.keepable(at);
		return gone && keep;
	}

	public void split(Point at) {
		Slab slab = this.yard.slabAt(at);
		if (this.yard.clear(at)) slab.shatter(this.yard, at, slab);
		if (careful) return;
	}

	public boolean lay(Point at) {
		if (!this.yard.clear(at)) return false;
		Slab slab = this.yard.slabAt(at);
		slab.settle(this.yard, at);
		slab.seal(at);
		return true;
	}
}
