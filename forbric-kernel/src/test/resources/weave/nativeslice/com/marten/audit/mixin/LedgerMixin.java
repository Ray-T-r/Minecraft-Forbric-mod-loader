package com.marten.audit.mixin;

import fixture.nativeslice.Trace;
import java.util.Map;
import org.vault.keep.Ledger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;

/** A guest that audits every entry taken out of the ledger after an entry could have been created. */
@Mixin(Ledger.class)
public abstract class LedgerMixin {
	@Redirect(method = "fetch", at = @At(value = "INVOKE", target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;"),
			slice = @Slice(from = @At(value = "INVOKE", target = "Lorg/vault/keep/Ledger;create(Lorg/vault/keep/Key;)Lorg/vault/keep/Entry;")))
	private Object audit$unload(Map<Object, Object> map, Object key) {
		Trace.add("unload " + key);
		return map.remove(key);
	}

	@Redirect(method = "prune", at = @At(value = "INVOKE", target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;"),
			slice = @Slice(from = @At(value = "INVOKE", target = "Lorg/vault/keep/Ledger;create(Lorg/vault/keep/Key;)Lorg/vault/keep/Entry;")))
	private Object audit$prune(Map<Object, Object> map, Object key) {
		Trace.add("prune " + key);
		return map.remove(key);
	}
}
