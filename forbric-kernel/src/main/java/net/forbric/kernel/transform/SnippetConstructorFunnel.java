/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.transform;

import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Routes {@code RenderPipeline$Builder.buildSnippet} through the vanilla-shaped 11-argument
 * {@code RenderPipeline$Snippet} constructor, carrying NeoForge's stencil test in a kernel scope.
 *
 * <p>NeoForge added a twelfth record component ({@code Optional<StencilTest>}) to {@code Snippet}, kept the
 * vanilla 11-arg constructor as a delegating stub ({@code aload 0..11; invokestatic Optional.empty;
 * invokespecial <init>(12 args)}), and made {@code buildSnippet} construct through the 12-arg one, passing the
 * Builder's {@code stencilTest} field. fabric-rendering-v1's {@code RenderPipelineBuilderMixin} wraps the
 * construction with {@code @WrapOperation(at = @At(NEW, target = Snippet))} and an 11-arg handler: MixinExtras
 * derives the expected signature from the call site, finds 12, and rejects the mixin whole ("has an invalid
 * signature") — every GUI-pipeline snippet a Fabric mod builds loses its {@code usePipelineDrawModeForGui}
 * flag, and the mixin's {@code withSnippet} TAIL inject goes with it.
 *
 * <p>Two edits, both-or-nothing, both stack-neutral: in {@code buildSnippet}, the two instructions feeding the
 * twelfth argument ({@code aload_0; getfield stencilTest}) move in front of {@code new Snippet} as a call to
 * {@code KernelSnippets.scope(Optional)}, the constructor descriptor becomes the 11-arg one, and
 * {@code KernelSnippets.clear()} follows the call; in the 11-arg constructor the one {@code Optional.empty()}
 * becomes {@code KernelSnippets.take()} (same descriptor). Fabric's wrap now sees an 11-arg construction and
 * its handler matches; its {@code @Unique} initializer on the record's canonical constructor runs as on Fabric;
 * and NeoForge's stencil test arrives through the scope for every snippetised pipeline — a narrow-stub call-site
 * rewrite would have dropped {@code withStencilTest} for all of them. The Builder edit reads the Snippet's bytes
 * through the resolver first, so the two halves can never apply alone.
 *
 * <p>{@code -Dforbric.snippetFunnel=off}: both halves stand down together.
 */
public final class SnippetConstructorFunnel implements ClassTransformer {
	public static final String PROPERTY = "forbric.snippetFunnel";

	static final String BUILDER = "com.mojang.blaze3d.pipeline.RenderPipeline$Builder";
	static final String SNIPPET = "com.mojang.blaze3d.pipeline.RenderPipeline$Snippet";
	static final String BUILDER_INTERNAL = BUILDER.replace('.', '/');
	static final String SNIPPET_INTERNAL = SNIPPET.replace('.', '/');
	static final String OPTIONAL = "java/util/Optional";
	static final String OPTIONAL_DESC = "L" + OPTIONAL + ";";
	static final String STENCIL_FIELD = "stencilTest";
	static final String BUILD_SNIPPET = "buildSnippet";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelSnippets";

	private final Function<String, byte[]> resolver;
	private int routed;

	/** @param resolver resource path ({@code a/b/C.class}) → bytes, as the class loader would serve them */
	public SnippetConstructorFunnel(Function<String, byte[]> resolver) {
		this.resolver = resolver;
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public String name() {
		return "forbric:snippet-constructor-funnel";
	}

	@Override
	public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("switched off by -D" + PROPERTY);
		String cost = "fabric-rendering-v1's GUI pipeline snippets are rejected by Mixin (constructor arity), and "
				+ "every Fabric-built pipeline loses its usePipelineDrawModeForGui flag";
		return AnchorSet.of(new AnchorSet.Anchor(BUILDER, AnchorSet.Severity.REQUIRED, cost),
				new AnchorSet.Anchor(SNIPPET, AnchorSet.Severity.REQUIRED, cost));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!enabled() || classBytes == null || classBytes.length == 0) return classBytes;
		if (SNIPPET.equals(className)) return redirectStub(classBytes);
		if (BUILDER.equals(className)) return funnelBuilder(classBytes);
		return classBytes;
	}

	/** The 11-arg stub, its {@code Optional.empty}/{@code take} call and the 12-arg descriptor it delegates to. */
	record Stub(MethodNode ctor, MethodInsnNode supply, String delegateDesc, boolean alreadyRouted) {
	}

	/**
	 * Recognises {@code aload 0..N; invokestatic Optional.empty (or KernelSnippets.take); invokespecial
	 * <init>(N+1 args); return} on a constructor whose N+1-arg delegate takes the same N types first and an
	 * {@code Optional} last.
	 */
	static Stub stubOf(ClassNode snippet) {
		for (MethodNode ctor : snippet.methods) {
			if (!"<init>".equals(ctor.name)) continue;
			Type[] args = Type.getArgumentTypes(ctor.desc);
			int expectSlot = 0;
			MethodInsnNode supply = null;
			MethodInsnNode delegate = null;
			boolean ok = true;
			int slot = 0;    // next local slot an aload/iload must read
			int loads = 0;
			for (AbstractInsnNode insn = ctor.instructions.getFirst(); insn != null && ok; insn = insn.getNext()) {
				int op = insn.getOpcode();
				if (op < 0) continue;
				if (insn instanceof VarInsnNode var && supply == null && delegate == null) {
					if (var.var != slot) { ok = false; break; }
					slot += loads == 0 ? 1 : args[loads - 1].getSize();
					loads++;
					continue;
				}
				if (insn instanceof MethodInsnNode call) {
					if (supply == null && delegate == null && call.getOpcode() == Opcodes.INVOKESTATIC
							&& "()" .concat(OPTIONAL_DESC).equals(call.desc)
							&& ((OPTIONAL.equals(call.owner) && "empty".equals(call.name))
									|| (RUNTIME.equals(call.owner) && "take".equals(call.name)))) {
						supply = call;
						continue;
					}
					if (supply != null && delegate == null && call.getOpcode() == Opcodes.INVOKESPECIAL
							&& "<init>".equals(call.name) && snippet.name.equals(call.owner)) {
						delegate = call;
						continue;
					}
					ok = false;
					break;
				}
				if (op == Opcodes.RETURN && delegate != null) continue;
				ok = false;
			}
			if (!ok || supply == null || delegate == null || loads != args.length + 1) continue;
			Type[] wide = Type.getArgumentTypes(delegate.desc);
			if (wide.length != args.length + 1 || !OPTIONAL_DESC.equals(wide[wide.length - 1].getDescriptor())) continue;
			boolean prefix = true;
			for (int i = 0; i < args.length; i++) if (!args[i].equals(wide[i])) prefix = false;
			if (!prefix) continue;
			return new Stub(ctor, supply, delegate.desc, RUNTIME.equals(supply.owner));
		}
		return null;
	}

	private byte[] redirectStub(byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		Stub stub = stubOf(node);
		if (stub == null) {
			ForbricLog.warn("[Forbric/SnippetFunnel] %s has no delegating 11-arg constructor of the recognised shape — "
					+ "fabric-rendering-v1's snippet wrap stays rejected", SNIPPET);
			return classBytes;
		}
		if (stub.alreadyRouted()) return classBytes;
		stub.supply().owner = RUNTIME;
		stub.supply().name = "take";
		ForbricLog.info("[Forbric/SnippetFunnel] Snippet's %d-arg constructor takes its stencil test from the kernel "
				+ "scope instead of Optional.empty()", Type.getArgumentTypes(stub.ctor().desc).length);
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private byte[] funnelBuilder(byte[] classBytes) {
		byte[] snippetBytes = resolver.apply(SNIPPET_INTERNAL + ".class");
		if (snippetBytes == null) {
			ForbricLog.warn("[Forbric/SnippetFunnel] cannot read %s — the Builder is left as it is", SNIPPET);
			return classBytes;
		}
		ClassNode snippet = new ClassNode();
		new ClassReader(snippetBytes).accept(snippet, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		Stub stub = stubOf(snippet);
		if (stub == null) {
			ForbricLog.warn("[Forbric/SnippetFunnel] %s has no delegating constructor of the recognised shape — the "
					+ "Builder is left as it is", SNIPPET);
			return classBytes;
		}

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		MethodNode build = null;
		for (MethodNode m : node.methods) if (BUILD_SNIPPET.equals(m.name) && m.desc.endsWith(")L" + SNIPPET_INTERNAL + ";")) build = m;
		if (build == null) {
			ForbricLog.warn("[Forbric/SnippetFunnel] %s has no %s — the seam has drifted", BUILDER, BUILD_SNIPPET);
			return classBytes;
		}
		MethodInsnNode call = null;
		TypeInsnNode construction = null;
		int calls = 0;
		for (AbstractInsnNode insn = build.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode c && RUNTIME.equals(c.owner)) return classBytes;    // already routed
			if (insn instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW && SNIPPET_INTERNAL.equals(t.desc)) construction = t;
			if (insn instanceof MethodInsnNode c && c.getOpcode() == Opcodes.INVOKESPECIAL && "<init>".equals(c.name)
					&& SNIPPET_INTERNAL.equals(c.owner)) {
				calls++;
				call = c;
			}
		}
		if (calls != 1 || construction == null || !stub.delegateDesc().equals(call.desc)) {
			ForbricLog.warn("[Forbric/SnippetFunnel] %s.%s constructs Snippet %d time(s) (descriptor %s) — not the one "
					+ "%d-arg construction the funnel expects; left as it is", BUILDER, BUILD_SNIPPET, calls,
					call == null ? "none" : call.desc, Type.getArgumentTypes(stub.delegateDesc()).length);
			return classBytes;
		}
		AbstractInsnNode feed = previousReal(call);
		AbstractInsnNode self = previousReal(feed);
		if (!(feed instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD || !STENCIL_FIELD.equals(field.name)
				|| !BUILDER_INTERNAL.equals(field.owner) || !OPTIONAL_DESC.equals(field.desc)
				|| !(self instanceof VarInsnNode var) || var.getOpcode() != Opcodes.ALOAD || var.var != 0) {
			ForbricLog.warn("[Forbric/SnippetFunnel] %s.%s does not pass its own %s field as the last constructor "
					+ "argument — left as it is", BUILDER, BUILD_SNIPPET, STENCIL_FIELD);
			return classBytes;
		}

		// scope(this.stencilTest) before `new Snippet`; the two feeding instructions go; the call narrows;
		// clear() after it. Straight-line, no new locals, no frames: the method is one expression.
		InsnList prologue = new InsnList();
		prologue.add(new VarInsnNode(Opcodes.ALOAD, 0));
		prologue.add(new FieldInsnNode(Opcodes.GETFIELD, BUILDER_INTERNAL, STENCIL_FIELD, OPTIONAL_DESC));
		prologue.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "scope", "(" + OPTIONAL_DESC + ")V", false));
		build.instructions.insertBefore(construction, prologue);
		build.instructions.remove(feed);
		build.instructions.remove(self);
		call.desc = stub.ctor().desc;
		build.instructions.insert(call, new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "clear", "()V", false));
		build.maxStack = Math.max(build.maxStack, 2);
		routed++;
		ForbricLog.info("[Forbric/SnippetFunnel] routed 1 RenderPipeline$Builder.buildSnippet call site through the "
				+ "vanilla-shaped %d-arg Snippet constructor; NeoForge's stencil test carried by scope — "
				+ "fabric-rendering-v1's @WrapOperation(NEW Snippet) matches that constructor",
				Type.getArgumentTypes(stub.ctor().desc).length);
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static AbstractInsnNode previousReal(AbstractInsnNode cursor) {
		AbstractInsnNode prev = cursor == null ? null : cursor.getPrevious();
		while (prev != null && prev.getOpcode() < 0) prev = prev.getPrevious();
		return prev;
	}

	/** Call sites routed, for the boot summary. */
	public int routedSites() {
		return routed;
	}
}
