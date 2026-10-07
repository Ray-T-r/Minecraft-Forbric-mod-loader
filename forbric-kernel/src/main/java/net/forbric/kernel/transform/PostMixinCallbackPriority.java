/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.*;import org.objectweb.asm.tree.*;
/** Old fallback seams stand down only when the final woven source callback and its helpers are proved present. */
public final class PostMixinCallbackPriority implements ClassTransformer {
 @Override public AnchorSet anchors(){return AnchorSet.scanned("final source callback ownership of previously inserted fallback seams");}
 @Override public byte[] transform(String name,byte[]bytes,TransformContext context){
  if(bytes==null)return null;ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);
  int changed=HopperFabricStorageInjector.standDownMigratedCallbacks(node);
  net.forbric.kernel.mixin.MixinNullableCompositeCallback.certify(node);
  net.forbric.kernel.mixin.MixinDecodeScopeAdapter.certify(node);if(changed==0)return bytes;
  ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);
  ForbricLog.info("[Forbric/Mixin] %s stands down %d older fallback(s); the final source callback, arguments and helper bodies own their original operation",name,changed);
  return writer.toByteArray();
 }
}
