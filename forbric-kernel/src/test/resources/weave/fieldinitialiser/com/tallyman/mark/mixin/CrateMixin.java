package com.tallyman.mark.mixin;

import org.harbor.shelf.Crate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import com.tallyman.mark.Marked;
import com.tallyman.mark.Marks;

/** Synthetic guest mixin: a field with an initialiser, read through the guest's own interface. */
@Mixin(Crate.class)
public class CrateMixin implements Marked {
	@Unique
	private String tallyman$tag = Marks.UNTAGGED;

	@Override
	public String tallyman$tag() {
		return tallyman$tag;
	}
}
