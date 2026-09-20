package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** The composition shim over the real staged roots, and every call site it re-creates. */
class ForgeCapabilityCompositionTransformerTest {
	private static final Path MERGED = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run",
			"merged-base", "patched-mc-merged-26.2.jar").normalize();
	private static final List<String> ROOTS = List.of(ForgeCapabilityCompositionTransformer.ENTITY,
			ForgeCapabilityCompositionTransformer.BLOCK_ENTITY, ForgeCapabilityCompositionTransformer.LEVEL);

	@AfterEach
	void clearSwitch() {
		System.clearProperty(ForgeCapabilityCompositionTransformer.PROPERTY);
	}

	@Test
	void everyRootGetsTheFieldTheAccessorTheInterfaceAndEveryDelegate() throws Exception {
		for (String root : ROOTS) {
			ClassNode before = parse(bytesOf(root));
			assertFalse(has(before, "getCapability", ForgeCapabilityCompositionTransformer.GET_CAPABILITY_DESC),
					root + " already declares Forge's getCapability — the shim is redundant now, re-derive");
			ClassNode after = parse(shim(root));
			assertEquals(1, after.interfaces.stream().filter(ForgeCapabilityCompositionTransformer.PROVIDER_IMPL::equals).count());
			assertTrue(after.fields.stream().anyMatch(f -> ForgeCapabilityCompositionTransformer.FIELD.equals(f.name)
					&& ForgeCapabilityCompositionTransformer.AS_FIELD_DESC.equals(f.desc) && (f.access & Opcodes.ACC_FINAL) == 0),
					"a private, non-final AsField field, written lazily outside <init>");
			List<String> expected = new ArrayList<>(List.of(
					"forbric$caps" + ForgeCapabilityCompositionTransformer.ACCESSOR_DESC,
					"getCapability" + ForgeCapabilityCompositionTransformer.GET_CAPABILITY_DESC,
					"invalidateCaps()V", "reviveCaps()V", "gatherCapabilities()V",
					"getCapabilities()" + ForgeCapabilityCompositionTransformer.DISPATCHER,
					"serializeCaps(" + ForgeCapabilityCompositionTransformer.HOLDER_LOOKUP + ")" + ForgeCapabilityCompositionTransformer.COMPOUND_TAG,
					"deserializeCaps(" + ForgeCapabilityCompositionTransformer.HOLDER_LOOKUP + ForgeCapabilityCompositionTransformer.COMPOUND_TAG + ")V"));
			if (ForgeCapabilityCompositionTransformer.BLOCK_ENTITY.equals(root)) {
				expected.add("serializeCaps(" + ForgeCapabilityCompositionTransformer.VALUE_OUTPUT + ")" + ForgeCapabilityCompositionTransformer.COMPOUND_TAG);
			}
			for (String signature : expected) {
				String name = signature.substring(0, signature.indexOf('('));
				String desc = signature.substring(signature.indexOf('('));
				MethodNode method = find(after, name, desc);
				assertNotNull(method, root + " lacks " + signature);
				assertTrue((method.access & Opcodes.ACC_PUBLIC) != 0, signature + " must be public: mod subclasses override and call super");
				assertTrue((method.access & Opcodes.ACC_FINAL) == 0, signature + " must not be final");
				for (AbstractInsnNode insn : method.instructions) {
					assertFalse(insn instanceof JumpInsnNode || insn instanceof FrameNode || insn instanceof TableSwitchInsnNode
							|| insn instanceof LookupSwitchInsnNode, signature + " must be branch-free: the frame recomputer never runs here");
				}
				new Analyzer<>(new BasicVerifier()).analyze(after.name, method);
			}
		}
	}

	@Test
	void theAccessorCreatesOnceAndSavesReadWithoutCreating() throws Exception {
		ClassNode be = parse(shim(ForgeCapabilityCompositionTransformer.BLOCK_ENTITY));
		MethodNode accessor = find(be, "forbric$caps", ForgeCapabilityCompositionTransformer.ACCESSOR_DESC);
		List<String> ops = new ArrayList<>();
		for (AbstractInsnNode insn : accessor.instructions) {
			if (insn instanceof FieldInsnNode f) ops.add((f.getOpcode() == Opcodes.GETFIELD ? "GET " : "PUT ") + f.name);
			else if (insn instanceof MethodInsnNode m) ops.add("CALL " + m.owner + "." + m.name);
			else if (insn.getOpcode() == Opcodes.DUP_X1) ops.add("DUP_X1");
		}
		assertEquals(List.of("GET forbric$forgeCaps", "CALL net/forbric/kernel/runtime/KernelForgeCapabilities.blockEntity", "DUP_X1", "PUT forbric$forgeCaps"), ops);
		MethodNode save = find(be, "saveAdditional", "(" + ForgeCapabilityCompositionTransformer.VALUE_OUTPUT + ")V");
		MethodNode load = find(be, "loadAdditional", "(" + ForgeCapabilityCompositionTransformer.VALUE_INPUT + ")V");
		assertTrue(reads(save, "forbric$forgeCaps") && !calls(save, "forbric$caps"), "saving must not create a provider — Forge's lazy serializeCaps answers the parked data");
		assertTrue(calls(load, "forbric$caps") && !reads(load, "forbric$forgeCaps"), "loading creates it, parking the tag for replay");
	}

	@Test
	void everyLostCallSiteIsReCreatedOnceAtForgesOwnBoundary() throws Exception {
		ClassNode be = parse(shim(ForgeCapabilityCompositionTransformer.BLOCK_ENTITY));
		assertFollows(find(be, "setRemoved", "()V"), "invalidateCapabilities", "invalidateCaps");
		assertEquals(1, count(find(be, "saveAdditional", "(" + ForgeCapabilityCompositionTransformer.VALUE_OUTPUT + ")V"), "saveBlockEntity"));
		assertEquals(1, count(find(be, "loadAdditional", "(" + ForgeCapabilityCompositionTransformer.VALUE_INPUT + ")V"), "load"));
		ClassNode entity = parse(shim(ForgeCapabilityCompositionTransformer.ENTITY));
		assertFollows(find(entity, "remove", "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V"), "setRemoved", "invalidateCaps");
		assertFollows(find(entity, "revive", "()V"), "unsetRemoved", "reviveCaps");
		assertEquals(1, count(find(entity, "saveWithoutId", "(" + ForgeCapabilityCompositionTransformer.VALUE_OUTPUT + ")V"), "saveEntity"));
		assertEquals(1, count(find(entity, "load", "(" + ForgeCapabilityCompositionTransformer.VALUE_INPUT + ")V"), "load"));
		for (MethodNode m : List.of(find(be, "saveAdditional", "(" + ForgeCapabilityCompositionTransformer.VALUE_OUTPUT + ")V"),
				find(entity, "saveWithoutId", "(" + ForgeCapabilityCompositionTransformer.VALUE_OUTPUT + ")V"))) {
			int returns = 0;
			for (AbstractInsnNode insn : m.instructions) if (insn.getOpcode() == Opcodes.RETURN) returns++;
			assertEquals(1, returns, "the RETURN count is unchanged by the funnel");
			new Analyzer<>(new BasicVerifier()).analyze(entity.name, m);
		}
	}

	@Test
	void serverLevelGathersAfterAttachmentsAndLevelChunkWritesForgesProvider() throws Exception {
		ClassNode server = parse(shim(ForgeCapabilityCompositionTransformer.SERVER_LEVEL));
		int inits = 0;
		for (MethodNode m : server.methods) {
			if (!"<init>".equals(m.name)) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof MethodInsnNode call && "initCapabilities".equals(call.name)) {
					inits++;
					AbstractInsnNode load = realPrevious(insn), anchor = realPrevious(load);
					assertTrue(load instanceof VarInsnNode v && v.var == 0);
					assertTrue(anchor instanceof MethodInsnNode a && "init".equals(a.name) && a.owner.endsWith("LevelAttachmentsSavedData"),
							"initCapabilities must follow LevelAttachmentsSavedData.init, Forge's own slot at the constructor's end");
				}
			}
		}
		assertEquals(1, inits);
		ClassNode chunk = parse(shim(ForgeCapabilityCompositionTransformer.LEVEL_CHUNK));
		int writes = 0;
		MethodNode main = null;
		for (MethodNode m : chunk.methods) {
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && "capProvider".equals(f.name)) {
					writes++;
					main = m;
					AbstractInsnNode ctor = realPrevious(insn);
					assertTrue(ctor instanceof MethodInsnNode c && "<init>".equals(c.name)
							&& ForgeCapabilityCompositionTransformer.LEVEL_CHUNKS_PROVIDER.equals(c.owner)
							&& "(Lnet/minecraft/world/level/chunk/LevelChunk;)V".equals(c.desc), "Forge's own one-arg AsField$LevelChunks constructor");
				}
			}
		}
		assertEquals(1, writes, "capProvider is final: exactly one write, inside <init>");
		assertEquals("<init>", main.name);
		assertTrue(main.desc.startsWith("(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/UpgradeData;"));
		AbstractInsnNode last = null;
		for (AbstractInsnNode insn : main.instructions) if (insn.getOpcode() == Opcodes.RETURN) last = realPrevious(insn);
		assertTrue(last instanceof MethodInsnNode init && "initInternal".equals(init.name), "initInternal right before RETURN, as Forge's constructor");
		new Analyzer<>(new BasicVerifier()).analyze(chunk.name, main);
		for (MethodNode m : chunk.methods) {
			if ("<init>".equals(m.name) && m.desc.contains("ProtoChunk")) {
				assertEquals(0, count(m, "initInternal"), "the ProtoChunk constructor delegates via this(...) and is left alone");
			}
		}
	}

	@Test
	void theCompatStubsStandDownWhenTheShimRanFirstAndReturnWhenItIsOff() throws Exception {
		for (String root : ROOTS) {
			byte[] composed = shim(root);
			ClassNode both = parse(new ForbricMergedBaseCompatTransformer().transform(root.replace('/', '.'), composed, null));
			for (String name : List.of("invalidateCaps", "reviveCaps")) {
				List<MethodNode> declared = both.methods.stream().filter(m -> name.equals(m.name) && "()V".equals(m.desc)).toList();
				assertEquals(1, declared.size(), root + " must declare " + name + " exactly once after both transformers");
				assertTrue(declared.getFirst().instructions.size() > 1 && calls(declared.getFirst(), "KernelForgeCapabilities"),
						"the shim's delegate must survive; a bare-return stub would silently drop every LazyOptional invalidation");
			}
		}
		System.setProperty(ForgeCapabilityCompositionTransformer.PROPERTY, "off");
		byte[] bytes = bytesOf(ForgeCapabilityCompositionTransformer.ENTITY);
		assertSame(bytes, new ForgeCapabilityCompositionTransformer().transform(ForgeCapabilityCompositionTransformer.ENTITY.replace('/', '.'), bytes, null));
		assertTrue(new ForgeCapabilityCompositionTransformer().anchors().anchors().isEmpty(), "off is a request, not a missed anchor");
	}

	/**
	 * E7. The merge kept MinecraftForge's {@code LivingEntity.handlers}, its readers and its {@code reviveCaps()}
	 * re-creation, but NeoForge won the constructor whole and the field initializer went with it — so the array
	 * was null from construction on. Nothing read it until E1 made {@code Entity.remove} call {@code invalidateCaps}
	 * again; then the first mob death on gate-m9 crashed the integrated server ("this.handlers" is null).
	 */
	@Test
	void theLostConstructorInitializersAreReplayedRightAfterSuper() throws Exception {
		for (var lost : ForgeCapabilityCompositionTransformer.LOST_INITIALIZERS.entrySet()) {
			String owner = lost.getKey();
			String field = lost.getValue();
			ClassNode before = parse(bytesOf(owner));
			for (MethodNode ctor : before.methods) {
				if ("<init>".equals(ctor.name)) assertFalse(assigns(ctor, owner, field), "premise: " + owner + ".<init> lost " + field);
			}
			ClassNode after = parse(shim(owner));
			int repaired = 0;
			for (MethodNode ctor : after.methods) {
				if (!"<init>".equals(ctor.name)) continue;
				MethodInsnNode superCall = null;
				for (AbstractInsnNode insn = ctor.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL && "<init>".equals(call.name)
							&& (after.superName.equals(call.owner) || after.name.equals(call.owner))) {
						superCall = call;
						break;
					}
				}
				assertNotNull(superCall, owner + " constructor without a super/this call");
				if (after.name.equals(superCall.owner)) continue;    // this(...) delegates
				// The very next real instruction is `aload 0`, and the first PUTFIELD after it is ours: Forge's own
				// initializer ordering, before every other field the constructor assigns.
				AbstractInsnNode next = superCall.getNext();
				while (next != null && next.getOpcode() < 0) next = next.getNext();
				assertTrue(next instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == 0, owner + ": replay must begin with aload 0");
				FieldInsnNode firstPut = null;
				for (AbstractInsnNode insn = next; insn != null; insn = insn.getNext()) {
					if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD) { firstPut = f; break; }
				}
				assertNotNull(firstPut, owner);
				assertEquals(field, firstPut.name, owner + ": the first field assigned after super() must be " + field);
				new Analyzer<>(new BasicVerifier()).analyze(after.name, ctor);
				repaired++;
			}
			assertTrue(repaired >= 1, owner + ": at least one constructor repaired");
		}
	}

	/**
	 * The census that pins {@code LOST_INITIALIZERS}: across the WHOLE merged base, every field assigned in a
	 * {@code reviveCaps()} but in no constructor. If the merge tool ever fixes one, or a carrier bump adds one, this
	 * is where it shows.
	 */
	@Test
	void theCensusOfLostInitializersMatchesTheTable() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED), "staged merged base absent");
		java.util.Map<String, String> found = new java.util.TreeMap<>();
		try (ZipFile zip = new ZipFile(MERGED.toFile())) {
			var entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (!entry.getName().endsWith(".class")) continue;
				byte[] bytes;
				try (InputStream in = zip.getInputStream(entry)) { bytes = in.readAllBytes(); }
				if (indexOf(bytes, "reviveCaps".getBytes(StandardCharsets.US_ASCII)) < 0) continue;
				ClassNode node = parse(bytes);
				MethodNode revive = find(node, "reviveCaps", "()V");
				if (revive == null) continue;
				for (AbstractInsnNode insn = revive.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (!(insn instanceof FieldInsnNode f) || f.getOpcode() != Opcodes.PUTFIELD || !node.name.equals(f.owner)) continue;
					boolean inCtor = false;
					for (MethodNode m : node.methods) if ("<init>".equals(m.name) && assigns(m, node.name, f.name)) inCtor = true;
					if (!inCtor) found.put(node.name, f.name);
				}
			}
		}
		assertEquals(new java.util.TreeMap<>(ForgeCapabilityCompositionTransformer.LOST_INITIALIZERS), found,
				"fields assigned in reviveCaps() but in no constructor — the table must name exactly these");
	}

	/** A reviveCaps whose assignment sits behind a branch is not an initializer; the repair must stand down whole. */
	@Test
	void aBranchyReviveCapsIsNotCopied() {
		org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
		String name = "test/Branchy";
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PRIVATE, "handlers", "Ljava/lang/Object;", null, null).visitEnd();
		var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		ctor.visitCode();
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(0, 0);
		ctor.visitEnd();
		var revive = cw.visitMethod(Opcodes.ACC_PUBLIC, "reviveCaps", "()V", null, null);
		revive.visitCode();
		revive.visitVarInsn(Opcodes.ALOAD, 0);
		revive.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "reviveCaps", "()V", false);
		var skip = new org.objectweb.asm.Label();
		revive.visitVarInsn(Opcodes.ALOAD, 0);
		revive.visitFieldInsn(Opcodes.GETFIELD, name, "handlers", "Ljava/lang/Object;");
		revive.visitJumpInsn(Opcodes.IFNONNULL, skip);
		revive.visitVarInsn(Opcodes.ALOAD, 0);
		revive.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
		revive.visitInsn(Opcodes.DUP);
		revive.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		revive.visitFieldInsn(Opcodes.PUTFIELD, name, "handlers", "Ljava/lang/Object;");
		revive.visitLabel(skip);
		revive.visitInsn(Opcodes.RETURN);
		revive.visitMaxs(0, 0);
		revive.visitEnd();
		cw.visitEnd();
		ClassNode node = parse(cw.toByteArray());
		assertFalse(ForgeCapabilityCompositionTransformer.replayLostInitializer(node, "handlers"));
		assertFalse(assigns(find(node, "<init>", "()V"), name, "handlers"), "nothing may be copied through a branch");
	}

	private static boolean assigns(MethodNode method, String owner, String field) {
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && owner.equals(f.owner) && field.equals(f.name)) return true;
		}
		return false;
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0; i <= haystack.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
			return i;
		}
		return -1;
	}

	@Test
	void aSecondPassChangesNothingFurther() throws Exception {
		for (String target : ForgeCapabilityCompositionTransformer.TARGETS) {
			byte[] once = shim(target);
			assertSame(once, new ForgeCapabilityCompositionTransformer().transform(target.replace('/', '.'), once, null), target);
		}
	}

	@Test
	void theFrameRecomputerLeavesTheComposedRootsAlone() throws Exception {
		MergedBaseFrameRecomputer recomputer = new MergedBaseFrameRecomputer(path -> {
			try {
				return bytesOf(path.replace(".class", ""));
			} catch (Exception e) {
				return null;
			}
		});
		for (String root : ROOTS) {
			byte[] once = shim(root);
			byte[] after = recomputer.transform(root.replace('/', '.'), once, null);
			assertSame(once, after, root + ": the shim adds no frames, so nothing may be recomputed");
		}
	}

	private static void assertFollows(MethodNode method, String anchor, String inserted) {
		int found = 0;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && inserted.equals(call.name)) {
				found++;
				AbstractInsnNode load = realPrevious(insn), previous = realPrevious(load);
				assertTrue(load instanceof VarInsnNode v && v.var == 0);
				assertTrue(previous instanceof MethodInsnNode a && anchor.equals(a.name), inserted + " must immediately follow " + anchor);
			}
		}
		assertEquals(1, found, method.name + " -> " + inserted);
	}

	private static int count(MethodNode method, String name) {
		int n = 0;
		for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode call && name.equals(call.name)) n++;
		return n;
	}

	private static boolean calls(MethodNode method, String needle) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && (call.name.equals(needle) || call.owner.endsWith(needle))) return true;
		}
		return false;
	}

	private static boolean reads(MethodNode method, String field) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD && field.equals(f.name)) return true;
		}
		return false;
	}

	private static AbstractInsnNode realPrevious(AbstractInsnNode insn) {
		AbstractInsnNode p = insn.getPrevious();
		while (p != null && p.getOpcode() < 0) p = p.getPrevious();
		return p;
	}

	private static boolean has(ClassNode node, String name, String desc) {
		return find(node, name, desc) != null;
	}

	private static MethodNode find(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
		return null;
	}

	private static byte[] shim(String internal) throws Exception {
		return new ForgeCapabilityCompositionTransformer().transform(internal.replace('/', '.'), bytesOf(internal), null);
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] bytesOf(String internal) throws Exception {
		assumeTrue(Files.isRegularFile(MERGED), "staged merged base absent");
		try (ZipFile zip = new ZipFile(MERGED.toFile())) {
			ZipEntry entry = zip.getEntry(internal + ".class");
			assertNotNull(entry, internal);
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}
}
