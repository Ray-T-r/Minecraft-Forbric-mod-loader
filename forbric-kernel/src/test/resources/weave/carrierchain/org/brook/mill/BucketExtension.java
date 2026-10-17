package org.brook.mill;

/** The carrier's extension of the bucket: the vanilla constant, scaled by a factor of its own. */
public interface BucketExtension {
	default Bucket self() {
		return (Bucket) this;
	}

	default double dipped() {
		return self().depth() + 0.25 * self().factor();
	}
}
