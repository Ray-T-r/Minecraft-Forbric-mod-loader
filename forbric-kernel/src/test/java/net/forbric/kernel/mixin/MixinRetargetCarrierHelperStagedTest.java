package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.net.URL;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import net.forbric.kernel.classloading.ForbricClassLoader;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * The carrier-helper rules over the REAL mixins of the sweep90 pack and the REAL merged base: the mods that paid for
 * them, and fabric-rendering-v1's HudMixin, whose four R3 moves out of the same dispatcher must not change.
 */
class MixinRetargetCarrierHelperStagedTest {
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar").normalize();
	private static final Path NEO_RUNTIME = TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar").normalize();
	private static final Path SWEEP = Path.of(System.getProperty("user.dir"), "build", "compat-inputs", "sweep90", "mods").normalize();
	private static final String G = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V";

	/** This suite pins the legacy rule independently; execution-path proofs have their own positive/negative tests. */
	@org.junit.jupiter.api.BeforeEach
	void legacyRuleScope() { System.setProperty(MixinExecutionPathRetarget.PROPERTY, "off"); }

	@AfterEach
	void reset() {
		System.clearProperty(MixinExecutionPathRetarget.PROPERTY);
		System.clearProperty(MixinRetarget.SPLIT_PROPERTY);
		MixinRetarget.reset();
		MixinStubRebind.forget();
	}

	/** Better Mount HUD's hunger-bar redirect: PARTIAL on the dispatcher, FIT on extractFoodLevel. */
	@Test
	void betterMountHudsFoodRedirectMovesToExtractFoodLevel() throws Exception {
		Function<String, byte[]> resolver = mergedResolver();
		String entry = "me/lortseam/bettermounthud/mixin/HudMixin";
		byte[] mixin = fromJar(SWEEP.resolve("bettermounthud-1.3.1.jar"), entry + ".class");
		MixinStubRebind.noteEcosystem(entry, Ecosystem.FABRIC);
		MixinFit.Result before = MixinFit.evaluate(mixin, resolver);
		assertEquals(MixinFit.Verdict.PARTIAL, before.verdict(), "premise: " + before.unresolved());

		MixinRetarget.Plan plan = MixinRetarget.plan(MixinFit.parse(mixin), resolver);
		assertEquals(1, plan.rewrites().size(), plan.describe());
		assertEquals("bettermounthud$alwaysRenderFood", plan.rewrites().get(0).handler());
		assertEquals("extractPlayerHealth", plan.rewrites().get(0).from());
		assertEquals("extractFoodLevel" + G, plan.rewrites().get(0).to());
		// What still does not run is the other hook, the XP redirect in Hud.extractHotbarAndDecorations, which nothing in
		// the merged game calls; the food redirect's own anchors all resolve in live code.
		MixinFit.Result after = MixinFit.evaluate(MixinRetarget.rewritten(mixin, plan), resolver);
		assertEquals(MixinFit.Verdict.PARTIAL, after.verdict(), "after: " + after.unresolved());
		assertEquals(1, after.unresolved().size(), "after: " + after.unresolved());
		assertTrue(after.unresolved().get(0).startsWith("@Inject target Hud.extractHotbarAndDecorations never runs"), "after: " + after.unresolved());
		System.setProperty(MixinFit.LIVENESS_PROPERTY, "off");
		try {
			MixinFit.Result bound = MixinFit.evaluate(MixinRetarget.rewritten(mixin, plan), resolver);
			assertEquals(MixinFit.Verdict.FIT, bound.verdict(), "resolution alone: " + bound.unresolved());
		} finally {
			System.clearProperty(MixinFit.LIVENESS_PROPERTY);
		}
	}

	/** Highlighter's MinecraftForge build: its AFTER-itemDecorations mark follows the call into renderSlotContents. */
	@Test
	void highlightersSlotMarkFollowsTheDecorationsIntoRenderSlotContents() throws Exception {
		Function<String, byte[]> resolver = mergedResolver();
		String entry = "com/anthonyhilyard/highlighter/forge/mixin/AbstractContainerScreenMixin";
		byte[] mixin = fromJar(SWEEP.resolve("Highlighter-26.2-forge-1.2.2.jar"), entry + ".class");
		MixinStubRebind.noteEcosystem(entry, Ecosystem.FORGE);
		MixinFit.Result before = MixinFit.evaluate(mixin, resolver);
		assertEquals(MixinFit.Verdict.PARTIAL, before.verdict(), "premise: " + before.unresolved());

		MixinRetarget.Plan plan = MixinRetarget.plan(MixinFit.parse(mixin), resolver);
		assertEquals(1, plan.rewrites().size(), plan.describe());
		assertEquals(MixinRetarget.Element.AT_TARGET, plan.rewrites().get(0).element());
		assertEquals("Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;renderSlotContents("
				+ "Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/item/ItemStack;"
				+ "Lnet/minecraft/world/inventory/Slot;Ljava/lang/String;)V", plan.rewrites().get(0).to());
		MixinFit.Result after = MixinFit.evaluate(MixinRetarget.rewritten(mixin, plan), resolver);
		assertEquals(MixinFit.Verdict.FIT, after.verdict(), "after: " + after.unresolved());
	}

	/** The released callback stays after the original set, before the helper's later fluid/event effects. */
    @Test
    void puzzleslibsFogColourStaysAtTheSourceSetInsideClientHooks() throws Exception {
        Function<String,byte[]> merged=mergedResolver();TestFixtures.requireFiles(Fixture.STAGED,"native carrier helper",NEO_RUNTIME);
        Function<String,byte[]> resolver=path->{byte[] bytes=merged.apply(path);if(bytes!=null)return bytes;try{return readFromJar(NEO_RUNTIME,path);}catch(Exception unavailable){return null;}};
        Function<String,ClassNode> classes=owner->{byte[] bytes=resolver.apply(owner+".class");return bytes==null?null:expanded(bytes);};
        String entry="fuzs/puzzleslib/fabric/mixin/client/FogRendererFabricMixin",owner="net/minecraft/client/renderer/fog/FogRenderer";
        byte[] bytes=fromJar(SWEEP.resolve("PuzzlesLib-v26.2.4-mc26.2.x-Fabric.jar"),entry+".class");MixinStubRebind.noteEcosystem(entry,Ecosystem.FABRIC);
        ClassNode original=expanded(bytes);MethodNode callback=original.methods.stream().filter(m->m.name.equals("computeFogColor")&&MixinFit.injectorOf(m)!=null).findFirst().orElseThrow();
        AnnotationNode injector=MixinFit.injectorOf(callback),at=MixinFit.atNodes(injector).getFirst();String member=MixinFit.asString(MixinFit.value(at,"target"));
        ClassNode source=NativeCallTestEvidence.staged().apply(Ecosystem.FABRIC,owner),current=classes.apply(owner);assertNotNull(source);
        MethodNode nativeHost=source.methods.stream().filter(m->m.name.equals("computeFogColor")).findFirst().orElseThrow();
        assertTrue((callback.access&Opcodes.ACC_STATIC)!=0,"the released callback observes arguments without a host receiver");assertTrue((nativeHost.access&Opcodes.ACC_STATIC)==0,"the actual native host is an instance method");
        assertEquals(MixinFit.Verdict.PARTIAL,MixinFit.evaluate(bytes,resolver).verdict());
        assertTrue(NativeCallTestEvidence.plan(original,resolver).rewrites().stream().noneMatch(r->r.handler().equals(callback.name)),"AFTER the entire carrier helper would move this callback beyond fluid and event writes");
        var seam=NativeCallbackSeam.derive(source,current,nativeHost,member,classes);assertNotNull(seam);assertEquals(seam.sourcePrefix(),seam.currentPrefix());assertEquals(6,seam.hostParameters().length);
        try(var loader=new ForbricClassLoader(new URL[0],getClass().getClassLoader())){
            ClassNode adapted=expanded(bytes);assertEquals(1,MixinAbsorbedCallbackTransport.adapt(adapted,classes,n->n.equals(owner)?source:null,loader));
            MethodNode retained=adapted.methods.stream().filter(m->m.name.contains("$forbricsourcecallback")&&m.desc.equals(callback.desc)).findFirst().orElseThrow();
            assertNull(MixinFit.injectorOf(retained));assertEquals(MixinInstructionFingerprint.hash(callback),MixinInstructionFingerprint.hash(retained),"the complete released event callback body remains unchanged");
            for(MethodNode lambda:original.methods)if(lambda.name.startsWith("lambda$computeFogColor$"))assertEquals(MixinInstructionFingerprint.hash(lambda),MixinInstructionFingerprint.hash(NativeCallChanges.method(adapted,lambda.name+lambda.desc)),"the released mutable-float lambda closure stays intact");
            MethodNode wrapper=adapted.methods.stream().filter(m->m.name.endsWith("$scope")).findFirst().orElseThrow();AnnotationNode wrap=MixinFit.injectorOf(wrapper);
            assertEquals(List.of(seam.method()),MixinFit.stringList(MixinFit.value(wrap,"method")));assertEquals("L"+seam.helper()+";"+seam.helperMethod(),MixinFit.value(MixinFit.atNodes(wrap).getFirst(),"target"));
            ClassNode helper=expanded(MixinAbsorbedCallbackTransport.transform(loader,seam.helper().replace('/','.'),resolver.apply(seam.helper()+".class")));MethodNode body=NativeCallChanges.method(helper,seam.helperMethod());
            MethodInsnNode set=Arrays.stream(body.instructions.toArray()).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).filter(c->NativeCallChanges.member(c).equals(member)).findFirst().orElseThrow();
            AbstractInsnNode receiver=nextReal(set),fire=nextReal(receiver);assertTrue(receiver instanceof VarInsnNode load&&load.getOpcode()==Opcodes.ALOAD);assertTrue(fire instanceof MethodInsnNode call&&call.owner.equals("net/forbric/api/CallbackSeams$Token")&&call.name.equals("fire"),"the callback fires immediately after the original set, before any later SDK call");
            List<String> nativeCalls=callMembers(NativeCallChanges.method(classes.apply(seam.helper()),seam.helperMethod()));assertEquals(nativeCalls,callMembers(body).stream().filter(c->!c.startsWith("Lnet/forbric/api/CallbackSeams")).toList(),"every native preparation/fluid/event call retains its order");
            MethodNode cleaned=new MethodNode(body.access,body.name,body.desc,null,null);body.accept(cleaned);AbstractInsnNode[] real=Arrays.stream(cleaned.instructions.toArray()).filter(i->i.getOpcode()>=0).toArray(AbstractInsnNode[]::new);
            for(int i=0;i<4;i++)cleaned.instructions.remove(real[i]);
            MethodInsnNode copiedSet=Arrays.stream(cleaned.instructions.toArray()).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).filter(c->NativeCallChanges.member(c).equals(member)).findFirst().orElseThrow();AbstractInsnNode token=nextReal(copiedSet),invoke=nextReal(token);cleaned.instructions.remove(token);cleaned.instructions.remove(invoke);
            assertEquals(seam.helperHash(),MixinInstructionFingerprint.hash(cleaned),"removing only the certified transport yields the exact actual helper body");
        }
        ClassNode mutated=expanded(resolver.apply(seam.helper()+".class"));MethodNode wrong=NativeCallChanges.method(mutated,seam.helperMethod());MethodInsnNode set=Arrays.stream(wrong.instructions.toArray()).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).filter(c->NativeCallChanges.member(c).equals(member)).findFirst().orElseThrow();
        for(AbstractInsnNode i=set.getPrevious();i!=null;i=i.getPrevious())if(i instanceof VarInsnNode value&&value.getOpcode()==Opcodes.FLOAD){value.var++;break;}
        try(var loader=new ForbricClassLoader(new URL[0],getClass().getClassLoader())){assertEquals(0,MixinAbsorbedCallbackTransport.adapt(expanded(bytes),n->n.equals(seam.helper())?mutated:classes.apply(n),n->n.equals(owner)?source:null,loader),"changed native setter operands cannot authorize this actual callback");}
        ClassNode cancellable=expanded(bytes);AnnotationNode changed=MixinFit.injectorOf(cancellable.methods.stream().filter(m->m.name.equals(callback.name)).findFirst().orElseThrow());changed.values=new ArrayList<>(changed.values);changed.values.addAll(List.of("cancellable",true));
        try(var loader=new ForbricClassLoader(new URL[0],getClass().getClassLoader())){assertEquals(0,MixinAbsorbedCallbackTransport.adapt(cancellable,classes,n->n.equals(owner)?source:null,loader),"cancellation cannot be moved into a helper");}
    }
    private static ClassNode expanded(byte[] bytes){ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,ClassReader.EXPAND_FRAMES);return node;}
    private static AbstractInsnNode nextReal(AbstractInsnNode instruction){instruction=instruction.getNext();while(instruction!=null&&instruction.getOpcode()<0)instruction=instruction.getNext();return instruction;}
    private static List<String> callMembers(MethodNode method){return Arrays.stream(method.instructions.toArray()).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).map(NativeCallChanges::member).toList();}

	/** fabric-rendering-v1's HudMixin: each anchor has one home, so R3 moves it and R4 never runs. */
	@Test
	void fabricRenderingsHudMixinPlanIsTheSameWithTheSplitRuleOnOrOff() throws Exception {
		Function<String, byte[]> resolver = mergedResolver();
		String entry = "net/fabricmc/fabric/mixin/client/rendering/HudMixin";
		byte[] mixin = nested(SWEEP.resolve("fabric-api-0.161.0+26.2.jar"), "fabric-rendering-v1", entry + ".class");
		MixinStubRebind.noteEcosystem(entry, Ecosystem.FABRIC);
		String on = MixinRetarget.plan(MixinFit.parse(mixin), resolver).describe();
		System.setProperty(MixinRetarget.SPLIT_PROPERTY, "off");
		String off = MixinRetarget.plan(MixinFit.parse(mixin), resolver).describe();
		assertFalse(on.isEmpty(), "premise: R3 moves this mixin's anchors out of extractPlayerHealth");
		assertEquals(off, on);
	}

	// ---------------------------------------------------------------------------------------------------------------

	static Function<String, byte[]> mergedResolver() {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		return name -> {
			try {
				return readFromJar(MERGED_BASE, name);
			} catch (Exception e) {
				return null;
			}
		};
	}

	static byte[] fromJar(Path jar, String entry) throws Exception {
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(jar), jar + " absent (symlink build/compat-inputs from the main checkout)");
		// Every caller names one exact release, so a jar that is here without the mixin has drifted.
		byte[] bytes = readFromJar(jar, entry);
		assertNotNull(bytes, entry + " absent from " + jar.getFileName());
		return bytes;
	}

	static byte[] nested(Path outer, String modulePrefix, String entry) throws Exception {
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(outer), outer + " absent (symlink build/compat-inputs from the main checkout)");
		try (ZipFile zip = new ZipFile(outer.toFile())) {
			for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
				ZipEntry nested = e.nextElement();
				if (!nested.getName().startsWith("META-INF/jars/" + modulePrefix)) continue;
				Path tmp = Files.createTempFile("forbric-nested", ".jar");
				try (InputStream in = zip.getInputStream(nested)) {
					Files.write(tmp, in.readAllBytes());
				}
				try {
					byte[] bytes = readFromJar(tmp, entry);
					if (bytes != null) return bytes;
				} finally {
					Files.deleteIfExists(tmp);
				}
			}
		}
		return fail(entry + " absent from the nested " + modulePrefix + " of " + outer.getFileName());
	}

	private static byte[] readFromJar(Path jar, String entry) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			if (found == null) return null;
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}
}
