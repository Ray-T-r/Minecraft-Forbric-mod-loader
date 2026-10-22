package org.quarry.stone;

/**
 * As merged: the carrier hands the clearing to helpers of its own. {@code crumble} shatters exactly when it cleared the spot and
 * answers that; it is called on two exclusive paths. {@code shiver} answers true whether or not it shattered anything.
 * {@code lay} asks the yard first and then runs the native body whole, renamed {@code layUnheard}: its boolean answer says
 * whether it settled the slab too, but the native settle call is still there, in the body.
 */
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
		if (careful) {
			crumble(at, shaped, false);
			return true;
		}
		boolean keep = shaped.keepable(at);
		boolean gone = crumble(at, shaped, keep);
		return gone && keep;
	}

	private boolean crumble(Point at, Slab slab, boolean keep) {
		boolean gone = this.yard.clear(at);
		if (gone) slab.shatter(this.yard, at, slab);
		return gone;
	}

	public void split(Point at) {
		Slab slab = this.yard.slabAt(at);
		if (careful) {
			shiver(at, slab);
			return;
		}
		shiver(at, slab);
	}

	private boolean shiver(Point at, Slab slab) {
		if (this.yard.clear(at)) slab.shatter(this.yard, at, slab);
		return true;
	}

	public boolean lay(Point at) {
		if (this.yard.refuses(at)) return false;
		return layUnheard(at);
	}

	private boolean layUnheard(Point at) {
		if (!this.yard.clear(at)) return false;
		Slab slab = this.yard.slabAt(at);
		slab.settle(this.yard, at);
		slab.seal(at);
		return true;
	}
}
