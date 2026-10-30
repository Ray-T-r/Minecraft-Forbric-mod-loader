/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.transform.DuplicateLambdaPruneInjector;

/**
 * The shapes a lambda had in the game a Fabric mod was compiled against that the class in front of Mixin no longer gives
 * it: the evidence {@link MixinHandlerShim}, {@link MixinOverloadPin}, {@link MixinFit} and
 * {@link InsertedLambdaArgumentShim} need before they treat a handler that does not fit a live lambda as one written for
 * it all the same.
 *
 * <p>Mixin's {@code method = "lambda$addSkyPass$0"} carries no descriptor, so a handler written for vanilla's lambda binds
 * whatever this base declares under that name. Where a carrier patch changed the lambda's captures, the handler takes the
 * other parameters and Mixin rejects it — "Invalid descriptor", and with a required mixin the whole class goes. The repairs
 * that hand such a handler what it asked for must know the mod really was written for a shape the name used to have, or
 * they would wrap every handler Mixin accepts by its own looser rules (two earlier versions did, eighteen handlers on one
 * pack). This class is where that knowledge comes from, and it has two sources.
 *
 * <ul>
 *   <li><b>The native reference.</b> {@code NativeGameReferences}' FABRIC class is vanilla's, the game a Fabric mod is
 *       compiled against. When it declares {@code N} with descriptor {@code Dv}, and the current class declares {@code N}
 *       but never with {@code Dv}, the mod's {@code N} meant {@code Dv}. Three look-alikes are left out on purpose. If the
 *       current class declares {@code Dv} under another lambda of the same enclosing method, a patch inserted a lambda and
 *       javac renumbered the rest: vanilla's lambda is still there under another name, and the one now called {@code N}
 *       is a different lambda, which nothing may be mapped onto. If every current {@code N} takes {@code Dv}'s parameters
 *       in another order, the lambda's captures were reordered, not reshaped: {@code lambda-permutations.txt} is the
 *       evidence for that, proven by local variable names, and MixinHandlerShim asks it first. And if every current
 *       {@code N} takes {@code Dv}'s parameters in their order with others inserted, a carrier patch added captures:
 *       that is InsertedLambdaArgumentShim's case, which it proves from the same reference itself and maps by an
 *       order-preserving embedding that keeps repeated types apart — Screenshot.grab's {@code lambda$grab$3} gains a
 *       {@code ScreenshotEvent} between the file and the consumer on both carriers. Left in, the type-only wrap would
 *       claim it first.</li>
 *   <li><b>The duplicate-lambda pruner's record.</b> On the merged base a name can be carried twice, one body per
 *       carrier; {@code DuplicateLambdaPruneInjector} drops the orphaned one before Mixin looks and remembers its
 *       descriptor. That is where this evidence came from first, and on the merged base it still counts: the record knows
 *       MinecraftForge's own shapes, which the FABRIC reference does not.</li>
 * </ul>
 *
 * <p>The first source is the one that survives the switch to NeoForge's own base, where nothing is carried twice and the
 * pruner has nothing to record: there {@code LevelRenderer}'s only {@code lambda$addSkyPass$0} is NeoForge's
 * {@code (SkyRenderState, Matrix4fc, GpuBufferSlice)}, vanilla's {@code (GpuBufferSlice, SkyRenderState)} is in the
 * reference, and Apoli's sky-skip is shimmed exactly as on the merged base. On the merged base it decides nothing the
 * record did not: LambdaShapeEvidenceStagedTest replays every lambda selector of every guest mixin in the local corpus
 * through the four consumers with the reference on and off, the pruner on and off, and the decisions are the same.
 * {@code -Dforbric.lambdaShapeEvidence=off} leaves the reference out; the record still counts.
 */
public final class LambdaShapeEvidence {
	/** {@code off}: only the duplicate-lambda pruner's record is evidence, as before the native reference was. */
	static final String PROPERTY = "forbric.lambdaShapeEvidence";

	private LambdaShapeEvidence() {
	}

	/**
	 * One shape the mod may have been written for. {@code dropped}: the pruner removed a body of that shape from this very
	 * class, which is what the merged base's diagnostics say; otherwise it is known from the native reference alone.
	 */
	public record Shape(String descriptor, boolean dropped) {
	}

	static boolean referenceEnabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/**
	 * The shapes of lambda {@code name} that {@code current} — the class as Mixin will see it — no longer has: the pruner's
	 * record for it first, then the native reference's. Distinct by descriptor; empty for anything else.
	 *
	 * <p>Every bare selector of every handler that does not bind as compiled asks this, so what it costs for the names it
	 * can say nothing about is checked first, before the reference is read (which re-reads, re-hashes and re-parses the
	 * class each time): a name that is not a lambda's, or a lambda the class does not declare. The pruner records lambdas
	 * only, so the first check changes no answer the record gave.
	 */
	public static List<Shape> vanished(ClassNode current, String name) {
		return vanished(current, name, owner -> NativeGameReferences.reference(Ecosystem.FABRIC, owner));
	}

	/** {@link #vanished(ClassNode, String)} with {@code fabricReference} reading the class a Fabric mod was compiled against. */
	static List<Shape> vanished(ClassNode current, String name, Function<String, ClassNode> fabricReference) {
		if (current == null || name == null || !name.startsWith("lambda$")) return List.of();
		Map<String, Shape> shapes = new LinkedHashMap<>();
		for (String dropped : DuplicateLambdaPruneInjector.droppedDescriptors(current.name, name)) {
			shapes.putIfAbsent(dropped, new Shape(dropped, true));
		}
		if (referenceEnabled() && declares(current, name)) {
			for (String reshaped : reshaped(current, fabricReference.apply(current.name), name)) {
				shapes.putIfAbsent(reshaped, new Shape(reshaped, false));
			}
		}
		return List.copyOf(shapes.values());
	}

	private static boolean declares(ClassNode current, String name) {
		if (current.methods == null) return false;
		for (MethodNode m : current.methods) if (m.name.equals(name)) return true;
		return false;
	}

	/** {@link #vanished}'s descriptors. */
	public static List<String> descriptors(ClassNode current, String name) {
		List<String> out = new ArrayList<>();
		for (Shape shape : vanished(current, name)) out.add(shape.descriptor());
		return out;
	}

	/**
	 * The descriptors {@code reference} declares lambda {@code name} with that {@code current} does not, though it declares
	 * {@code name}: not renumbered (the descriptor under another lambda of the enclosing method), and not only reordered
	 * or only widened (every current descriptor of the name a permutation of it, or it with parameters inserted). Empty
	 * when either class is missing.
	 */
	static List<String> reshaped(ClassNode current, ClassNode reference, String name) {
		if (current == null || reference == null || current.methods == null || reference.methods == null
				|| !name.startsWith("lambda$")) return List.of();
		List<String> live = new ArrayList<>();
		for (MethodNode m : current.methods) if (m.name.equals(name)) live.add(m.desc);
		if (live.isEmpty()) return List.of();
		String enclosing = name.substring(0, name.lastIndexOf('$') + 1);
		List<String> out = new ArrayList<>();
		for (MethodNode written : reference.methods) {
			if (!written.name.equals(name) || live.contains(written.desc) || out.contains(written.desc)) continue;
			boolean renumbered = false;
			for (MethodNode m : current.methods) renumbered |= m.name.startsWith(enclosing) && m.desc.equals(written.desc);
			if (renumbered) continue;
			if (live.stream().allMatch(desc -> reordering(desc, written.desc) || insertion(desc, written.desc))) continue;
			out.add(written.desc);
		}
		return out;
	}

	/** Same return type, and the same parameter types in another order. */
	static boolean reordering(String one, String other) {
		if (one.equals(other) || !Type.getReturnType(one).equals(Type.getReturnType(other))) return false;
		String[] a = sortedParameters(one), b = sortedParameters(other);
		return Arrays.equals(a, b);
	}

	/** Same return type, and {@code narrower}'s parameter types in their order among more of {@code wider}'s. */
	static boolean insertion(String wider, String narrower) {
		if (!Type.getReturnType(wider).equals(Type.getReturnType(narrower))) return false;
		Type[] w = Type.getArgumentTypes(wider), n = Type.getArgumentTypes(narrower);
		if (w.length <= n.length) return false;
		int at = 0;
		for (Type type : w) if (at < n.length && type.equals(n[at])) at++;
		return at == n.length;
	}

	private static String[] sortedParameters(String desc) {
		Type[] types = Type.getArgumentTypes(desc);
		String[] out = new String[types.length];
		for (int i = 0; i < types.length; i++) out[i] = types[i].getDescriptor();
		Arrays.sort(out);
		return out;
	}
}
