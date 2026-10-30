package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.ExpectCaught;

/** The composition shim over the real staged roots, and every call site it re-creates. */
class ForgeCapabilityCompositionTransformerTest {
	private static final Path MERGED = TestFixtures.stagedBase();
	private static final Path NEO = TestFixtures.genuineBase();
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
		// The funnels exist for a body that lost MinecraftForge's ForgeCaps statement: NeoForge's own BlockEntity is
		// exactly that body (what the merge kept before it spliced both sides' edits).
		ClassNode lost = parse(new ForgeCapabilityCompositionTransformer().transform(
				ForgeCapabilityCompositionTransformer.BLOCK_ENTITY.replace('/', '.'), neoBytesOf(ForgeCapabilityCompositionTransformer.BLOCK_ENTITY), null));
		MethodNode save = find(lost, "saveAdditional", "(" + ForgeCapabilityCompositionTransformer.VALUE_OUTPUT + ")V");
		MethodNode load = find(lost, "loadAdditional", "(" + ForgeCapabilityCompositionTransformer.VALUE_INPUT + ")V");
		assertTrue(reads(save, "forbric$forgeCaps") && !calls(save, "forbric$caps"), "saving must not create a provider — Forge's lazy serializeCaps answers the parked data");
		assertTrue(calls(load, "forbric$caps") && !reads(load, "forbric$forgeCaps"), "loading creates it, parking the tag for replay");
	}

	/**
	 * Each lifecycle site carries MinecraftForge's hook exactly once: re-created where the merged body lost it, and
	 * left alone where the merged body kept Forge's own statement (the edit-splicing merge keeps the {@code ForgeCaps}
	 * saves of both roots, BlockEntity's load and BlockEntity.setRemoved's {@code invalidateCaps}) — a funnel on top of
	 * the native statement serializes twice and replays twice, and a second invalidation runs twice.
	 */
	@Test
	void everyLostCallSiteIsReCreatedOnceAtForgesOwnBoundary() throws Exception {
		String save = "(" + ForgeCapabilityCompositionTransformer.VALUE_OUTPUT + ")V", load = "(" + ForgeCapabilityCompositionTransformer.VALUE_INPUT + ")V";
		ClassNode be = parse(shim(ForgeCapabilityCompositionTransformer.BLOCK_ENTITY));
		ClassNode entity = parse(shim(ForgeCapabilityCompositionTransformer.ENTITY));
		ClassNode beBase = parse(bytesOf(ForgeCapabilityCompositionTransformer.BLOCK_ENTITY));
		ClassNode entityBase = parse(bytesOf(ForgeCapabilityCompositionTransformer.ENTITY));
		assertInvokedOnce(beBase, be, "setRemoved", "()V", "invalidateCapabilities", "invalidateCaps");
		assertInvokedOnce(entityBase, entity, "remove", "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", "setRemoved", "invalidateCaps");
		assertInvokedOnce(entityBase, entity, "revive", "()V", "unsetRemoved", "reviveCaps");
		// Negative control: NeoForge's own bodies carry none of MinecraftForge's lifecycle calls, so each is re-created.
		ClassNode beNeo = parse(new ForgeCapabilityCompositionTransformer().transform(
				ForgeCapabilityCompositionTransformer.BLOCK_ENTITY.replace('/', '.'), neoBytesOf(ForgeCapabilityCompositionTransformer.BLOCK_ENTITY), null));
		assertEquals(0, count(find(parse(neoBytesOf(ForgeCapabilityCompositionTransformer.BLOCK_ENTITY)), "setRemoved", "()V"), "invalidateCaps"),
				"premise: NeoForge's BlockEntity.setRemoved does not invalidate MinecraftForge's capabilities");
		assertFollows(find(beNeo, "setRemoved", "()V"), "invalidateCapabilities", "invalidateCaps");
		assertPersistedOnce(beBase, be, "saveAdditional", save, "saveBlockEntity");
		assertPersistedOnce(beBase, be, "loadAdditional", load, "load");
		assertPersistedOnce(entityBase, entity, "saveWithoutId", save, "saveEntity");
		assertPersistedOnce(entityBase, entity, "load", load, "load");
		// Negative control: on NeoForge's own bodies, which carry no ForgeCaps statement, the funnels go in.
		for (String root : List.of(ForgeCapabilityCompositionTransformer.BLOCK_ENTITY, ForgeCapabilityCompositionTransformer.ENTITY)) {
			ClassNode neo = parse(neoBytesOf(root));
			ClassNode funnelled = parse(new ForgeCapabilityCompositionTransformer().transform(root.replace('/', '.'), neoBytesOf(root), null));
			boolean isEntity = ForgeCapabilityCompositionTransformer.ENTITY.equals(root);
			for (String[] site : isEntity ? new String[][] { { "saveWithoutId", save, "saveEntity" }, { "load", load, "load" } }
					: new String[][] { { "saveAdditional", save, "saveBlockEntity" }, { "loadAdditional", load, "load" } }) {
				assertFalse(ForgeCapabilityCompositionTransformer.persistsForgeCaps(find(neo, site[0], site[1])), "premise: " + root + "." + site[0] + " lost ForgeCaps");
				assertEquals(1, runtimeCount(find(funnelled, site[0], site[1]), site[2]), root + "." + site[0] + " gets the funnel");
			}
		}
		for (ClassNode node : List.of(be, entity, parse(new ForgeCapabilityCompositionTransformer().transform(
				ForgeCapabilityCompositionTransformer.BLOCK_ENTITY.replace('/', '.'), neoBytesOf(ForgeCapabilityCompositionTransformer.BLOCK_ENTITY), null)))) {
			for (String name : List.of("saveAdditional", "saveWithoutId")) {
				MethodNode m = find(node, name, save);
				if (m == null) continue;
				int returns = 0;
				for (AbstractInsnNode insn : m.instructions) if (insn.getOpcode() == Opcodes.RETURN) returns++;
				assertEquals(1, returns, "the RETURN count is unchanged by the funnel");
				new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
			}
		}
	}

	/**
	 * Exactly one {@code inserted} call in {@code name}: the base's own, left where the merge put it, or the kernel's,
	 * right after {@code anchor} — never both.
	 */
	private static void assertInvokedOnce(ClassNode base, ClassNode shimmed, String name, String desc, String anchor, String inserted) {
		if (count(find(base, name, desc), inserted) == 0) {
			assertFollows(find(shimmed, name, desc), anchor, inserted);
			return;
		}
		assertEquals(1, count(find(base, name, desc), inserted), base.name + "." + name + " calls " + inserted + " once itself");
		assertEquals(1, count(find(shimmed, name, desc), inserted),
				base.name + "." + name + " keeps MinecraftForge's own " + inserted + " — a second one would run it twice");
	}

	/** Exactly one ForgeCaps persistence in {@code name}: the base's own statement, or the kernel's funnel — never both. */
	private static void assertPersistedOnce(ClassNode base, ClassNode shimmed, String name, String desc, String helper) {
		boolean nativeStatement = ForgeCapabilityCompositionTransformer.persistsForgeCaps(find(base, name, desc));
		int funnels = runtimeCount(find(shimmed, name, desc), helper);
		assertEquals(nativeStatement ? 0 : 1, funnels, base.name + "." + name + (nativeStatement
				? " keeps MinecraftForge's own ForgeCaps statement — a funnel on top persists twice"
				: " lost the ForgeCaps statement — the funnel must re-create it"));
	}

	private static int runtimeCount(MethodNode method, String name) {
		int n = 0;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(ForgeCapabilityCompositionTransformer.RUNTIME) && name.equals(call.name)) n++;
		}
		return n;
	}

	/**
	 * ServerLevel's constructor gathers MinecraftForge's capabilities once, at its end, as MinecraftForge's own
	 * constructor does. Where the kernel restores the call it goes right after NeoForge's LevelAttachmentsSavedData.init.
	 * Where the merged base carries MinecraftForge's own call (a merge that keeps both sides' edits puts MinecraftForge's
	 * end-of-constructor statement first at the shared end), the kernel leaves it, and only NeoForge's own
	 * end-of-constructor statements may follow it: no vanilla code runs on an ungathered level.
	 */
	@Test
	void serverLevelGathersAfterAttachmentsAndLevelChunkWritesForgesProvider() throws Exception {
		byte[] staged = bytesOf(ForgeCapabilityCompositionTransformer.SERVER_LEVEL);
		boolean nativeCall = ForgeCapabilityCompositionTransformer.carriesNatively(parse(staged));
		ClassNode server = parse(shim(ForgeCapabilityCompositionTransformer.SERVER_LEVEL));
		int inits = 0;
		for (MethodNode m : server.methods) {
			if (!"<init>".equals(m.name)) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof MethodInsnNode call && "initCapabilities".equals(call.name)) {
					inits++;
					AbstractInsnNode load = realPrevious(insn), anchor = realPrevious(load);
					assertTrue(load instanceof VarInsnNode v && v.var == 0);
					if (!nativeCall) {
						assertTrue(anchor instanceof MethodInsnNode a && "init".equals(a.name) && a.owner.endsWith("LevelAttachmentsSavedData"),
								"initCapabilities must follow LevelAttachmentsSavedData.init, Forge's own slot at the constructor's end");
						continue;
					}
					for (AbstractInsnNode after = insn.getNext(); after != null && after.getOpcode() != Opcodes.RETURN; after = after.getNext()) {
						if (after instanceof MethodInsnNode later) assertTrue(later.owner.startsWith("net/neoforged/"),
								"only NeoForge's own end-of-constructor statements follow MinecraftForge's gather, found " + later.owner + "." + later.name);
					}
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
	void theCompatStubsStandDownBehindTheCompositionWhetherOrNotDispatchIsOn() throws Exception {
		for (boolean dispatch : new boolean[] { true, false }) {
			ForgeCapabilityCompositionTransformer composition = new ForgeCapabilityCompositionTransformer(null, false, dispatch);
			for (String root : ROOTS) {
				byte[] composed = composition.transform(root.replace('/', '.'), bytesOf(root), null);
				ClassNode both = parse(new ForbricMergedBaseCompatTransformer().transform(root.replace('/', '.'), composed, null));
				for (String name : List.of("invalidateCaps", "reviveCaps")) {
					List<MethodNode> declared = both.methods.stream().filter(m -> name.equals(m.name) && "()V".equals(m.desc)).toList();
					assertEquals(1, declared.size(), root + " must declare " + name + " exactly once after both transformers");
					assertTrue(declared.getFirst().instructions.size() > 1 && calls(declared.getFirst(), "KernelForgeCapabilities"),
							"the shim's delegate must survive; a bare-return stub would silently drop every LazyOptional invalidation");
				}
			}
		}
	}

	/**
	 * {@code -Dforbric.forgeCapabilities=off} used to skip the roots entirely. The rebuilt merged base lists each root
	 * in required-ancestor-compositions.tsv, and the loader refuses to define one that no composition proves — so off
	 * stopped the game at the first Entity. Off now means: the roots are composed exactly as with dispatch on, except
	 * that the accessor builds the inert provider, and none of the dispatch call sites is inserted anywhere.
	 */
	@Test
	void withDispatchOffTheRootsAreComposedWithTheInertFactoryAndNothingElseIsTouched() throws Exception {
		System.setProperty(ForgeCapabilityCompositionTransformer.PROPERTY, "off");
		ForgeCapabilityCompositionTransformer fromSwitch = new ForgeCapabilityCompositionTransformer(null, true);
		assertFalse(fromSwitch.dispatches(), "the two-argument constructor reads the switch");
		assertFalse(fromSwitch.transferFallback(), "the transfer fallback edits a dispatching getCapability: never expected while off");
		ForgeCapabilityCompositionTransformer off = new ForgeCapabilityCompositionTransformer(null, false, false);
		ForgeCapabilityCompositionTransformer on = new ForgeCapabilityCompositionTransformer(null, false, true);
		for (String root : ROOTS) {
			ClassNode before = parse(bytesOf(root));
			byte[] once = off.transform(root.replace('/', '.'), bytesOf(root), null);
			ClassNode inert = parse(once);
			ClassNode dispatching = parse(on.transform(root.replace('/', '.'), bytesOf(root), null));
			assertEquals(dispatching.interfaces, inert.interfaces, root + ": the same provider interface");
			assertEquals(fields(dispatching), fields(inert), root + ": the same composed state field");
			// Every method the composition adds is there, and only the accessor's factory differs.
			for (MethodNode added : dispatching.methods) {
				if (find(before, added.name, added.desc) != null) continue;
				MethodNode twin = find(inert, added.name, added.desc);
				assertNotNull(twin, root + " lacks " + added.name + added.desc + " with dispatch off");
				assertEquals(added.access, twin.access, added.name);
				if (ForgeCapabilityCompositionTransformer.ACCESSOR.equals(added.name)) {
					assertEquals(List.of("KernelForgeCapabilities." + ForgeCapabilityCompositionTransformer.INERT_FACTORY),
							runtimeCalls(twin), root + ": the accessor must build the inert provider");
					assertFalse(runtimeCalls(added).contains("KernelForgeCapabilities." + ForgeCapabilityCompositionTransformer.INERT_FACTORY));
				} else {
					assertEquals(tokens(added), tokens(twin), root + "." + added.name + added.desc + " must be the very same delegate");
				}
			}
			// No dispatch call site: every method the base already had is byte-for-byte what it was.
			for (MethodNode existing : before.methods) {
				assertEquals(tokens(existing), tokens(find(inert, existing.name, existing.desc)),
						root + "." + existing.name + existing.desc + " must not gain a capability call site with dispatch off");
			}
			assertSame(once, off.transform(root.replace('/', '.'), once, null), root + ": a second pass changes nothing");
		}
		// Look-alikes: the classes only dispatch needs are left exactly as they are. The premise that keeps this from
		// being vacuous: with dispatch on, each one is rewired — unless the merged base already carries
		// MinecraftForge's own code there, in which case dispatch on leaves it alone too — and at least one is.
		int rewiredOn = 0;
		for (String target : ForgeCapabilityCompositionTransformer.TARGETS) {
			if (ROOTS.contains(target)) continue;
			byte[] bytes = bytesOf(target);
			assertSame(bytes, off.transform(target.replace('/', '.'), bytes, null), target + " is a dispatch site, not a required root");
			if (on.transform(target.replace('/', '.'), bytes, null) != bytes) rewiredOn++;
			else assertTrue(ForgeCapabilityCompositionTransformer.carriesNatively(parse(bytes)),
					"premise: with dispatch on " + target + " is rewired, or its merged body carries Forge's own code natively");
		}
		if (rewiredOn == 0) {
			// Every dispatch site of this base carries MinecraftForge's own code. The premise then comes from the base's
			// ServerLevel with its own gather taken out: dispatch on rewires it, and off must leave it exactly as it is.
			byte[] stripped = withoutInitCapabilities(bytesOf(ForgeCapabilityCompositionTransformer.SERVER_LEVEL));
			String server = ForgeCapabilityCompositionTransformer.SERVER_LEVEL.replace('/', '.');
			assertNotSame(stripped, on.transform(server, stripped, null), "premise: dispatch on rewires a ServerLevel that lost its gather");
			assertSame(stripped, off.transform(server, stripped, null), "off leaves that ServerLevel alone");
			rewiredOn++;
		}
		assertTrue(rewiredOn > 0, "premise: with dispatch on, some dispatch site is rewired — else off leaving them alone proves nothing");
		List<AnchorSet.Anchor> anchors = off.anchors().anchors();
		assertEquals(ROOTS.stream().map(r -> r.replace('/', '.')).sorted().toList(), anchors.stream().map(AnchorSet.Anchor::binaryName).sorted().toList(),
				"off still owes the three roots; it owes nothing else");
		assertTrue(anchors.stream().allMatch(a -> a.severity() == AnchorSet.Severity.REQUIRED));
	}

	/**
	 * A dispatch site whose merged body already carries MinecraftForge's own code is handed back untouched, so a
	 * REQUIRED anchor there would put "its anchor is gone" in every boot log for a class that works. With a reader
	 * over the base the books stay clean on the staged base: each site is REQUIRED exactly when the base lacks the
	 * code and the transformer then edits it; without a reader every site stays REQUIRED.
	 */
	@Test
	void aSiteTheBaseCarriesNativelyIsAHedgeAndTheBooksStayClean() throws Exception {
		java.util.function.Function<String, byte[]> reader = path -> {
			try {
				return bytesOf(path.substring(0, path.length() - ".class".length()));
			} catch (Exception | AssertionError absent) {
				return null;
			}
		};
		ForgeCapabilityCompositionTransformer reading = new ForgeCapabilityCompositionTransformer(reader, false, true);
		java.util.Map<String, AnchorSet.Severity> severity = new java.util.HashMap<>();
		for (AnchorSet.Anchor anchor : reading.anchors().anchors()) severity.put(anchor.binaryName(), anchor.severity());
		assertEquals(ForgeCapabilityCompositionTransformer.TARGETS.size(), severity.size());
		for (String target : ForgeCapabilityCompositionTransformer.TARGETS) {
			boolean carried = !ROOTS.contains(target) && ForgeCapabilityCompositionTransformer.carriesNatively(parse(bytesOf(target)));
			assertEquals(carried ? AnchorSet.Severity.HEDGE : AnchorSet.Severity.REQUIRED, severity.get(target.replace('/', '.')), target);
		}
		for (AnchorSet.Anchor anchor : new ForgeCapabilityCompositionTransformer(null, false, true).anchors().anchors()) {
			assertEquals(AnchorSet.Severity.REQUIRED, anchor.severity(), anchor.binaryName() + ": nothing to read, nothing to hedge");
		}
		TransformChain chain = new TransformChain();
		chain.register(TransformPhase.COREMOD, reading);
		TransformContext context = new TransformContext(net.fabricmc.api.EnvType.CLIENT, false, "intermediary");
		for (String target : ForgeCapabilityCompositionTransformer.TARGETS) chain.applyBeforeMixin(target.replace('/', '.'), bytesOf(target), context);
		AnchorLedger.Report report = chain.ledger().report();
		assertTrue(report.clean(), () -> "a REQUIRED capability site was handed back untouched: " + report.misses());
	}

	/** A class shaped like a root but not named by the composition is not composed, whatever the switch says. */
	@Test
	void aClassThatIsNotARootIsNeverComposed() throws Exception {
		ClassNode lookalike = parse(bytesOf(ForgeCapabilityCompositionTransformer.ENTITY));
		lookalike.name = "fixture/stateful/HolderRoot";
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
		lookalike.accept(writer);
		byte[] bytes = writer.toByteArray();
		for (boolean dispatch : new boolean[] { true, false }) {
			assertSame(bytes, new ForgeCapabilityCompositionTransformer(null, false, dispatch).transform("fixture.stateful.HolderRoot", bytes, null));
		}
	}

	private static List<String> fields(ClassNode node) {
		List<String> out = new ArrayList<>();
		for (org.objectweb.asm.tree.FieldNode f : node.fields) out.add(f.access + " " + f.name + f.desc);
		return out;
	}

	private static List<String> runtimeCalls(MethodNode method) {
		List<String> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(ForgeCapabilityCompositionTransformer.RUNTIME)) {
				out.add("KernelForgeCapabilities." + call.name);
			}
		}
		return out;
	}

	private static List<String> tokens(MethodNode method) {
		assertNotNull(method);
		org.objectweb.asm.util.Textifier text = new org.objectweb.asm.util.Textifier();
		method.accept(new org.objectweb.asm.util.TraceMethodVisitor(text));
		List<String> out = new ArrayList<>();
		for (Object line : text.getText()) out.add(String.valueOf(line).trim());
		return out;
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
			byte[] lostShape = bytesOf(owner);
			if (ForgeCapabilityCompositionTransformer.carriesNatively(parse(lostShape))) {
				// The edit-splicing merge keeps MinecraftForge's initializer in the constructor. A replay on top would
				// create the handlers twice; the repair stands down and the class is handed back untouched.
				assertSame(lostShape, new ForgeCapabilityCompositionTransformer().transform(owner.replace('/', '.'), lostShape, null),
						owner + " keeps " + field + "'s constructor initializer: the replay must stand down");
				// The replay itself is then proved on the body that lost it: the same class, that statement taken out.
				lostShape = withoutConstructorInitializer(parse(lostShape), field);
			}
			ClassNode before = parse(lostShape);
			for (MethodNode ctor : before.methods) {
				if ("<init>".equals(ctor.name)) assertFalse(assigns(ctor, owner, field), "premise: " + owner + ".<init> lost " + field);
			}
			ClassNode after = parse(new ForgeCapabilityCompositionTransformer().transform(owner.replace('/', '.'), lostShape, null));
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
	 * {@code reviveCaps()} but in no constructor must be in the table — a carrier bump that adds one shows here. And
	 * every table entry must be either lost in this base or initialized by the base's own constructor (the edit-splicing
	 * merge keeps all three, so the census is empty there): an entry that is neither names a field this base no longer
	 * has in this shape, and the repair would be silently aimed at nothing.
	 */
	@Test
	void theCensusOfLostInitializersMatchesTheTable() throws Exception {
		TestFixtures.requireFile(Fixture.STAGED, MERGED, "staged merged base absent");
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
		for (var lost : found.entrySet()) {
			assertEquals(lost.getValue(), ForgeCapabilityCompositionTransformer.LOST_INITIALIZERS.get(lost.getKey()),
					"fields assigned in reviveCaps() but in no constructor — the table must name every one: " + found);
		}
		for (var entry : ForgeCapabilityCompositionTransformer.LOST_INITIALIZERS.entrySet()) {
			if (found.containsKey(entry.getKey())) continue;   // lost here: the replay covers it
			ClassNode node = parse(bytesOf(entry.getKey()));
			MethodNode revive = find(node, "reviveCaps", "()V");
			assertTrue(revive != null && assigns(revive, node.name, entry.getValue()),
					entry + ": reviveCaps() no longer re-creates the field the table names");
			assertTrue(ForgeCapabilityCompositionTransformer.carriesNatively(node),
					entry + " is neither lost nor initialized by a constructor in this base");
		}
	}

	/**
	 * {@code node} with the constructor statement that initializes {@code field} taken out — the exact straight-line
	 * sequence {@code reviveCaps()} re-creates it with, which is what a merge that dropped the initializer left.
	 */
	private static byte[] withoutConstructorInitializer(ClassNode node, String field) {
		MethodNode revive = find(node, "reviveCaps", "()V");
		assertNotNull(revive, node.name + " has no reviveCaps()");
		List<String> sequence = new ArrayList<>();
		boolean started = false;
		for (AbstractInsnNode insn : revive.instructions) {
			if (insn.getOpcode() < 0) continue;
			if (!started) {
				started = insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL && "reviveCaps".equals(call.name);
				continue;
			}
			sequence.add(text(insn));
			if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && field.equals(f.name)) break;
		}
		int removed = 0;
		for (MethodNode ctor : node.methods) {
			if (!"<init>".equals(ctor.name)) continue;
			List<AbstractInsnNode> code = new ArrayList<>();
			for (AbstractInsnNode insn : ctor.instructions) if (insn.getOpcode() >= 0) code.add(insn);
			for (int i = 0; i + sequence.size() <= code.size(); i++) {
				boolean match = true;
				for (int j = 0; j < sequence.size() && match; j++) match = sequence.get(j).equals(text(code.get(i + j)));
				if (!match) continue;
				for (int j = 0; j < sequence.size(); j++) ctor.instructions.remove(code.get(i + j));
				removed++;
				break;
			}
		}
		assertTrue(removed > 0, node.name + ": no constructor carries reviveCaps()'s own initializer for " + field);
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static String text(AbstractInsnNode insn) {
		org.objectweb.asm.util.Textifier text = new org.objectweb.asm.util.Textifier();
		insn.accept(new org.objectweb.asm.util.TraceMethodVisitor(text));
		StringBuilder out = new StringBuilder();
		for (Object line : text.getText()) out.append(line);
		return out.toString().trim();
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

	// Not proved here: the recomputer does attempt Entity and declines only because the staged base it is given
	// lacks NeoForge's AttachmentHolder. Before the resolver caught AssertionError it was interrupted instead.
	@ExpectCaught("cannot prove a frame repair")
	@Test
	void theFrameRecomputerLeavesTheComposedRootsAlone() throws Exception {
		MergedBaseFrameRecomputer recomputer = new MergedBaseFrameRecomputer(path -> {
			try {
				return bytesOf(path.replace(".class", ""));
			} catch (Exception | AssertionError notInTheBase) {
				// bytesOf fails an assertion for a class the base lacks; to the recomputer that is "no bytes".
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

	/** The class with every constructor's {@code this.initCapabilities()} taken out, as a merge that lost it leaves it. */
	/**
	 * A dispatch-site repair that finds MinecraftForge's own code already there says so, and one that lands does not.
	 * Standing down silently left gate-m29 able to assert only the repair's own line, which a base that keeps both
	 * sides' edits never prints, so the gate read a working class as a missing repair. The ServerLevel case is built
	 * both ways from the staged class (its own gather present, and taken out), so it holds on either kind of base.
	 */
	@Test
	void aRepairThatFindsTheBasesOwnCodeSaysItStandsByAndOneThatLandsDoesNot() throws Exception {
		ForgeCapabilityCompositionTransformer on = new ForgeCapabilityCompositionTransformer(null, false, true);
		String server = ForgeCapabilityCompositionTransformer.SERVER_LEVEL;
		byte[] stripped = withoutInitCapabilities(bytesOf(server));
		byte[] carrying = on.transform(server.replace('/', '.'), stripped, null);
		assertTrue(ForgeCapabilityCompositionTransformer.carriesNatively(parse(carrying)), "premise: the repair restores the gather");
		try {
			ForgeCapabilityCompositionTransformer.forgetStandingBy();
			on.transform(server.replace('/', '.'), stripped, null);
			assertFalse(ForgeCapabilityCompositionTransformer.stoodBy().contains("ServerLevel.<init> -> initCapabilities"),
					"a ServerLevel that lost its gather is repaired, not stood by");

			ForgeCapabilityCompositionTransformer.forgetStandingBy();
			assertSame(carrying, on.transform(server.replace('/', '.'), carrying, null), "the gather is there: nothing to do");
			assertTrue(ForgeCapabilityCompositionTransformer.stoodBy().contains("ServerLevel.<init> -> initCapabilities"),
					"and the repair says it stands by");

			for (Map.Entry<String, String> lost : ForgeCapabilityCompositionTransformer.LOST_INITIALIZERS.entrySet()) {
				ClassNode node = parse(bytesOf(lost.getKey()));
				ForgeCapabilityCompositionTransformer.forgetStandingBy();
				boolean replayed = ForgeCapabilityCompositionTransformer.replayLostInitializer(node, lost.getValue());
				String label = lost.getKey().replace('/', '.') + ".<init> assigns " + lost.getValue();
				assertEquals(!replayed, ForgeCapabilityCompositionTransformer.stoodBy().contains(label),
						label + ": stands by exactly when it does not replay");
			}
		} finally {
			ForgeCapabilityCompositionTransformer.forgetStandingBy();
		}
	}

	private static byte[] withoutInitCapabilities(byte[] bytes) {
		ClassNode node = parse(bytes);
		for (MethodNode m : node.methods) {
			if (!"<init>".equals(m.name)) continue;
			for (AbstractInsnNode insn : m.instructions.toArray()) {
				if (!(insn instanceof MethodInsnNode call) || !"initCapabilities".equals(call.name)) continue;
				m.instructions.remove(realPrevious(insn));
				m.instructions.remove(insn);
			}
		}
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
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
		return entry(MERGED, internal);
	}

	/** NeoForge's own patched class: the body the merge kept whole before it spliced both sides' edits. */
	private static byte[] neoBytesOf(String internal) throws Exception {
		return entry(NEO, internal);
	}

	private static byte[] entry(Path jar, String internal) throws Exception {
		TestFixtures.requireFile(Fixture.STAGED, jar, "staged jar absent: " + jar);
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(internal + ".class");
			assertNotNull(entry, internal);
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}
}
