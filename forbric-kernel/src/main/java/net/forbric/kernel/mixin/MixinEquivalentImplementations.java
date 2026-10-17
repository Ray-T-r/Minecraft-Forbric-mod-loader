/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import org.objectweb.asm.tree.*;

/**
 * Replacement proofs for an injector that found nothing to attach to: the final class already does what the injector
 * would have done. Each proof is structural — read off the handler's data flow and the defined bodies — and none names a
 * mixin, a handler or a body hash: {@link MixinNativeEquivalence} (the method already makes the handler's one operation)
 * and {@link MixinDelegationEquivalence} (the method already hands the call an equal computation of the same value). A
 * mixin whose whole job a kernel repair does is resolved by {@link SupersededMixins} instead.
 */
final class MixinEquivalentImplementations {
 private MixinEquivalentImplementations() { }
 static boolean needsFingerprint(String mixin,MethodNode handler){return MixinNativeEquivalence.needsFingerprint(handler);}
 static String proof(String mixin,String name,String desc,String fingerprint,ClassNode target){
  String nativeProof=MixinNativeEquivalence.proof(mixin,name,desc,fingerprint,target);
  if(nativeProof!=null)return nativeProof;
  return MixinDelegationEquivalence.proof(mixin,name,desc,target,NativeGameReferences::current);
 }
}
