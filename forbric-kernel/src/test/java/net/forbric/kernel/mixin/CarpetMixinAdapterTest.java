package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.ZipFile;
import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Reads the exact released Carpet handlers and real carrier bytecode; none of the injector bodies is a mock. */
@ResourceLock("system-properties")
@ResourceLock("ModCatalog")
class CarpetMixinAdapterTest {
	static final List<String> NAMES=List.of("Level_fillUpdatesMixin","ServerGamePacketListenerImpl_scarpetEventsMixin",
			"ServerPlayerGameMode_scarpetEventsMixin","LiquidBlock_renewableBlackstoneMixin","LiquidBlock_renewableDeepslateMixin");
	static final Path CARPET=Path.of("build/compat-inputs/carpet/mods/fabric-carpet-26.2+v260616.jar");
	static final String PACKETS="net/minecraft/server/network/ServerGamePacketListenerImpl";
	static final String SWAP_HOST="handlePlayerAction(Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket;)V";
	static final String BREAK_HOST="destroyBlock("+MixinPlayerWorldCallbackAdapter.POS+")Z";
	static ClassNode mixin(String name)throws Exception {
		return from(Fixture.THIRD_PARTY,CARPET,"carpet/mixins/"+name);
	}
	static Path jarOf(String name) {
		Path root=TestFixtures.stagedRoot();
		return root.resolve(name.startsWith("net/minecraftforge/")?"forge-runtime/forge-runtime.jar":name.startsWith("net/neoforged/")?"neoforge-runtime/neoforge-runtime.jar":"merged-base/patched-mc-merged-26.2.jar");
	}
	static ClassNode target(String name) {
		try{return from(Fixture.STAGED,jarOf(name),name);}
		catch(org.opentest4j.TestAbortedException e){throw e;}
		catch(Exception e){throw new AssertionError(e);}
	}
	/** MixinFit's resolver over the staged jars ("net/…/Foo.class"); null for a class none of them carries. */
	static byte[] staged(String path) {
		if(!path.endsWith(".class"))return null;
		String name=path.substring(0,path.length()-".class".length());
		try(ZipFile zip=new ZipFile(jarOf(name).toFile())){var e=zip.getEntry(path);return e==null?null:zip.getInputStream(e).readAllBytes();}
		catch(Exception e){throw new AssertionError(e);}
	}
	static ClassNode from(Fixture kind,Path jar,String name)throws Exception {
		TestFixtures.require(kind,Files.isRegularFile(jar),"real fixture required: "+jar);
		try(ZipFile zip=new ZipFile(jar.toFile())){ClassNode c=new ClassNode();new ClassReader(zip.getInputStream(zip.getEntry(name+".class"))).accept(c,0);return c;}
	}
	static byte[] bytes(ClassNode c){ClassWriter w=new ClassWriter(0);c.accept(w);return w.toByteArray();}
	static int adapt(ClassNode c){return MixinPlayerWorldCallbackAdapter.adapt(c,CarpetMixinAdapterTest::target)+MixinFluidReactionAdapter.adapt(c,CarpetMixinAdapterTest::target);}
	static MethodNode method(ClassNode c,String n){return MixinPlayerWorldCallbackAdapter.named(c,n);}
	static void verify(ClassNode c)throws Exception {for(MethodNode m:c.methods)if((m.access&Opcodes.ACC_ABSTRACT)==0)new Analyzer<>(new BasicVerifier()).analyze(c.name,m);}
	static void withAdapters(String value,Runnable body){String old=System.setProperty(MixinPlayerWorldCallbackAdapter.PROPERTY,value);
		try{body.run();}finally{if(old==null)System.clearProperty(MixinPlayerWorldCallbackAdapter.PROPERTY);else System.setProperty(MixinPlayerWorldCallbackAdapter.PROPERTY,old);}}

	@Test void fillHooksMoveTogetherToTheActualNotificationBody()throws Exception {
		ClassNode c=mixin(NAMES.get(0));assertEquals(2,adapt(c));
		for(String name:List.of("addFillUpdatesInt","updateNeighborsMaybe"))assertEquals(List.of(MixinPlayerWorldCallbackAdapter.LIVE_FILL),MixinFit.value(MixinFit.injectorOf(method(c,name)),"method"));
		verify(c);assertEquals(0,adapt(c));
	}
	@Test void handSwapRunsBeforeTheNativeEventReadsTheHands()throws Exception {
		ClassNode c=mixin(NAMES.get(1));assertEquals(1,adapt(c));
		AnnotationNode inject=MixinFit.injectorOf(method(c,"onHandSwap")),a=MixinFit.atNodes(inject).getFirst();
		assertEquals(MixinPlayerWorldCallbackAdapter.SWAP_EVENT,MixinFit.value(a,"target"));assertEquals(0,MixinFit.value(a,"ordinal"));
		assertEquals(true,MixinFit.value(inject,"cancellable"));verify(c);assertEquals(0,adapt(c));
	}
	@Test void breakRunsAfterPlayerWillDestroyOnTheLocalsVanillaCaptured()throws Exception {
		// The premise of the indexes: NeoForge's destroyBlock keeps vanilla's captured blockEntity, block and
		// adjustedState (vanilla slots 2, 3, 4) in slots 4, 5 and 6.
		MethodNode host=MixinPlayerWorldCallbackAdapter.selector(target(MixinPlayerWorldCallbackAdapter.GAME_MODE),BREAK_HOST);
		assertEquals(List.of("blockEntity","block","adjustedState"),List.of(4,5,6).stream().map(slot->host.localVariables.stream()
				.filter(v->v.index==slot).findFirst().orElseThrow().name).toList());
		ClassNode c=mixin(NAMES.get(2));assertEquals(1,adapt(c));MethodNode handler=method(c,"onBlockBroken");AnnotationNode inject=MixinFit.injectorOf(handler);
		assertNull(MixinFit.value(inject,"locals"));assertEquals(true,MixinFit.value(inject,"cancellable"));
		assertEquals(MixinPlayerWorldCallbackAdapter.DROPS,MixinFit.value(MixinFit.atNodes(inject).getFirst(),"target"));
		assertNull(handler.invisibleParameterAnnotations[0]);assertNull(handler.invisibleParameterAnnotations[1]);
		assertEquals(List.of(4,5,6),List.of(2,3,4).stream().map(p->MixinFit.value(handler.invisibleParameterAnnotations[p].getFirst(),"index")).toList());
		assertEquals(1,c.methods.stream().filter(m->m.name.startsWith("onBlockBroken")).count(),"the authored handler itself, no wrapper");
		verify(c);assertEquals(0,adapt(c));
	}
	@Test void blackstoneRunsOnlyAfterTheNativeRegistryDeclinesAnInteraction()throws Exception {
		ClassNode c=mixin(NAMES.get(3));assertEquals(2,adapt(c));
		for(String name:List.of("onPlace","neighborChanged")){
			MethodNode m=method(c,"forbric$unhandledFluidReaction$"+name);int nativeCall=-1,callback=-1,guard=-1;
			for(var i:m.instructions){int index=m.instructions.indexOf(i);if(i instanceof MethodInsnNode call){if(call.name.equals("call"))nativeCall=index;if(call.name.endsWith("$forbricOriginal"))callback=index;}if(i.getOpcode()==Opcodes.IFNE)guard=index;}
			assertTrue(nativeCall<guard&&guard<callback);assertEquals(1,MixinFit.value(MixinFit.injectorOf(m),"require"));
		}
		verify(c);assertEquals(0,adapt(c));
	}
	@Test void deepslateUsesTheSelectedWaterInteractionAndPreservesTheOriginalRuleBody()throws Exception {
		ClassNode c=mixin(NAMES.get(4));assertEquals(1,adapt(c));
		assertEquals(new HashSet<>(MixinFluidReactionAdapter.REGISTRIES),new HashSet<>(MixinFit.mixinTargets(c)),"placement asks MinecraftForge's, a neighbour change NeoForge's");
		MethodNode original=method(c,"receiveFluidToDeepslate$forbricOriginal"),outer=method(c,"forbric$flowingFluidReaction");
		assertTrue((original.access&Opcodes.ACC_STATIC)!=0);assertNull(MixinFit.injectorOf(original));
		assertEquals(5,MixinFit.value(outer.invisibleParameterAnnotations[3].getFirst(),"index"));
		assertEquals(List.of("interact(L"+MixinPlayerWorldCallbackAdapter.LEVEL+";"+MixinPlayerWorldCallbackAdapter.POS+MixinPlayerWorldCallbackAdapter.POS+"L"+MixinFluidReactionAdapter.FLUID_STATE+";)V"),
				MixinFit.atNodes(MixinFit.injectorOf(outer)).stream().map(a->MixinFit.value(a,"target")).toList(),"the one interact call in either registry");assertNotNull(method(c,"forbric$fluidReactionFizz").desc);
		verify(c);assertEquals(0,adapt(c));
	}
	/**
	 * What Mixin is handed in the game: LiquidBlock as merged (placement asks MinecraftForge's registry, a neighbour change
	 * NeoForge's) and both registries as FluidInteractionsInjector leaves them; and, with the repair off, MinecraftForge's
	 * neutered, so placement falls back to NeoForge's and the deepslate rule lives there alone.
	 */
	@Test void fluidAdaptersBindToTheHostsTheFluidRepairLeaves()throws Exception {
		String neo=MixinFluidReactionAdapter.REGISTRIES.getFirst(),forge=MixinFluidReactionAdapter.REGISTRIES.get(1);
		java.util.function.Function<String,ClassNode> repaired=name->{ClassNode t=target(name);
			if(!MixinFluidReactionAdapter.REGISTRIES.contains(name))return t;
			byte[] out=new net.forbric.kernel.transform.FluidInteractionsInjector().transform(name.replace('/','.'),bytes(t),null);
			ClassNode c=new ClassNode();new ClassReader(out).accept(c,0);return c;};
		ClassNode liquid=repaired.apply(MixinFluidReactionAdapter.LIQUID);
		Map<String,String> asks=Map.of("onPlace",forge,"neighborChanged",neo);
		for(var host:asks.entrySet())assertEquals(1,MixinPlayerWorldCallbackAdapter.count(method(liquid,host.getKey()),
				"L"+host.getValue()+";canInteract"+MixinFluidReactionAdapter.INTERACT),"premise: "+host.getKey()+" asks "+host.getValue());
		ClassNode blackstone=mixin(NAMES.get(3));assertEquals(2,MixinFluidReactionAdapter.adapt(blackstone,repaired));
		for(var host:asks.entrySet()){MethodNode m=method(blackstone,"forbric$unhandledFluidReaction$"+host.getKey());
			assertEquals("L"+host.getValue()+";canInteract"+MixinFluidReactionAdapter.INTERACT,MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(m)).getFirst(),"target"));
			for(var i:m.instructions)assertFalse(i instanceof MethodInsnNode c&&c.name.equals("canInteract"),
					host.getKey()+": no fallback to the other registry — NeoForge's own placement runs no mod's rule");}
		ClassNode deepslate=mixin(NAMES.get(4));assertEquals(1,MixinFluidReactionAdapter.adapt(deepslate,repaired));
		assertEquals(Set.of(neo,forge),new HashSet<>(MixinFit.mixinTargets(deepslate)));
		verify(blackstone);verify(deepslate);
		String old=System.setProperty(net.forbric.kernel.transform.FluidInteractionsInjector.PROPERTY,"off");
		try{
			ClassNode off=mixin(NAMES.get(3));assertEquals(2,MixinFluidReactionAdapter.adapt(off,CarpetMixinAdapterTest::target));
			assertTrue(Arrays.stream(method(off,"forbric$unhandledFluidReaction$onPlace").instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode c
					&&c.name.equals("canInteract")&&c.owner.equals(neo)),"repair off: MinecraftForge's is neutered, so placement falls back to NeoForge's");
			ClassNode offDeepslate=mixin(NAMES.get(4));assertEquals(1,MixinFluidReactionAdapter.adapt(offDeepslate,CarpetMixinAdapterTest::target));
			assertEquals(Set.of(neo),new HashSet<>(MixinFit.mixinTargets(offDeepslate)),"no interact call to inject at in a neutered registry");
			verify(off);verify(offDeepslate);
		}finally{if(old==null)System.clearProperty(net.forbric.kernel.transform.FluidInteractionsInjector.PROPERTY);
			else System.setProperty(net.forbric.kernel.transform.FluidInteractionsInjector.PROPERTY,old);}
	}
	@Test void reshapedNativeFluidLocalRefusesTheWholeRetarget()throws Exception {
		ClassNode c=mixin(NAMES.get(4));byte[] before=bytes(c);
		assertEquals(0,MixinFluidReactionAdapter.adapt(c,name->{ClassNode t=target(name);if(MixinFluidReactionAdapter.REGISTRIES.contains(name))for(var m:t.methods)for(var i:m.instructions)if(i instanceof VarInsnNode v&&v.getOpcode()==Opcodes.ASTORE&&v.var==5)v.var=9;return t;}));
		assertArrayEquals(before,bytes(c));
	}

	/** The staged classes, with one merged method edited before the adapters read it. */
	static Function<String,ClassNode> reshaped(String owner,String selector,Consumer<MethodNode> edit) {
		return name->{ClassNode t=target(name);if(name.equals(owner))edit.accept(Objects.requireNonNull(MixinPlayerWorldCallbackAdapter.selector(t,selector),selector));return t;};
	}
	/** Two calls of one invoke kind trade owner, name and descriptor: the same instructions, in the other order. */
	static void exchange(MethodNode m,String a,String b) {
		MethodInsnNode x=Objects.requireNonNull(MixinPlayerWorldCallbackAdapter.first(m,a),a),y=Objects.requireNonNull(MixinPlayerWorldCallbackAdapter.first(m,b),b);
		assertEquals(x.getOpcode(),y.getOpcode(),"premise: same invoke kind");
		String owner=x.owner,name=x.name,desc=x.desc;x.owner=y.owner;x.name=y.name;x.desc=y.desc;y.owner=owner;y.name=name;y.desc=desc;
	}
	static JumpInsnNode jumpBefore(MethodNode m,String member) {
		AbstractInsnNode i=MixinPlayerWorldCallbackAdapter.first(m,member);while(!(i instanceof JumpInsnNode))i=i.getPrevious();return (JumpInsnNode)i;
	}
	/** Each merged body moved out of the order or meaning a retarget relies on: nothing of the mixin is touched. */
	@Test void reshapedHostsRefuseTheWholeRetarget()throws Exception {
		String level=MixinPlayerWorldCallbackAdapter.LEVEL,mode=MixinPlayerWorldCallbackAdapter.GAME_MODE,player="L"+MixinPlayerWorldCallbackAdapter.PLAYER+";";
		String onPlace="onPlace("+MixinPlayerWorldCallbackAdapter.STATE+"L"+level+";"+MixinPlayerWorldCallbackAdapter.POS+MixinPlayerWorldCallbackAdapter.STATE+"Z)V";
		String update="L"+level+";updateNeighborsAt("+MixinPlayerWorldCallbackAdapter.POS+"L"+MixinPlayerWorldCallbackAdapter.BLOCK+";)V";
		record Case(int mixin,String why,Function<String,ClassNode> targets) { }
		List<Case> cases=List.of(
			new Case(0,"16 is no longer the flags bit",reshaped(level,MixinPlayerWorldCallbackAdapter.LIVE_FILL,m->{for(var i:m.instructions)
				if(i instanceof IntInsnNode c&&c.operand==16)((VarInsnNode)MixinPlayerWorldCallbackAdapter.previous(c)).var=6;})),
			new Case(0,"neighbour update outside flags & 1",reshaped(level,MixinPlayerWorldCallbackAdapter.LIVE_FILL,m->jumpBefore(m,update).setOpcode(Opcodes.IFNE))),
			new Case(1,"a hand is read before the event",reshaped(PACKETS,SWAP_HOST,m->{
				AbstractInsnNode self=MixinPlayerWorldCallbackAdapter.previous(MixinPlayerWorldCallbackAdapter.previous(MixinPlayerWorldCallbackAdapter.first(m,MixinPlayerWorldCallbackAdapter.SWAP_EVENT)));
				InsnList read=new InsnList();read.add(new VarInsnNode(Opcodes.ALOAD,0));read.add(new FieldInsnNode(Opcodes.GETFIELD,PACKETS,"player",player));
				read.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,MixinPlayerWorldCallbackAdapter.PLAYER,"getMainHandItem","()"+MixinPlayerWorldCallbackAdapter.STACK,false));
				read.add(new InsnNode(Opcodes.POP));m.instructions.insertBefore(self,read);})),
			new Case(1,"the veto after the hand writes",reshaped(PACKETS,SWAP_HOST,m->exchange(m,MixinPlayerWorldCallbackAdapter.SWAP_VETO,player+"stopUsingItem()V"))),
			new Case(2,"NeoForge's break event after the callback",reshaped(mode,BREAK_HOST,m->exchange(m,MixinPlayerWorldCallbackAdapter.BREAK_EVENT,
				"Lnet/neoforged/neoforge/event/EventHooks;onPlayerDestroyItem(Lnet/minecraft/world/entity/player/Player;"+MixinPlayerWorldCallbackAdapter.STACK+"Lnet/minecraft/world/InteractionHand;)V"))),
			new Case(2,"durability before the callback",reshaped(mode,BREAK_HOST,m->exchange(m,MixinPlayerWorldCallbackAdapter.MINE,player+"canUseGameMasterBlocks()Z"))),
			new Case(2,"adjustedState's slot stored twice",reshaped(mode,BREAK_HOST,m->{for(var i:m.instructions)
				if(i instanceof VarInsnNode v&&v.getOpcode()==Opcodes.ASTORE&&v.var==7)v.var=6;})),
			new Case(3,"placement's answer no longer means handled",reshaped(MixinFluidReactionAdapter.LIQUID,onPlace,m->{
				JumpInsnNode j=(JumpInsnNode)MixinPlayerWorldCallbackAdapter.next(MixinPlayerWorldCallbackAdapter.first(m,"L"+MixinFluidReactionAdapter.REGISTRIES.get(1)+";canInteract"+MixinFluidReactionAdapter.INTERACT));
				j.setOpcode(Opcodes.IFEQ);})));
		for(Case k:cases){
			assertTrue(adapt(mixin(NAMES.get(k.mixin())))>0,"premise, as staged: "+k.why());
			ClassNode c=mixin(NAMES.get(k.mixin()));byte[] before=bytes(c);
			assertEquals(0,MixinPlayerWorldCallbackAdapter.adapt(c,k.targets())+MixinFluidReactionAdapter.adapt(c,k.targets()),k.why());
			assertArrayEquals(before,bytes(c),k.why());
		}
	}

	/** The preflight census reads a mixin before Mixin loads it; it must judge what the adapters will hand Mixin. */
	@Test void censusJudgesTheMixinsAsTheAdaptersHandThemToMixin()throws Exception {
		for(String name:List.of(NAMES.get(0),NAMES.get(2))){
			byte[] raw=bytes(mixin(name));
			assertEquals(MixinFit.Verdict.PARTIAL,MixinFit.evaluate(raw,CarpetMixinAdapterTest::staged).verdict(),"premise, as compiled: "+name);
			MixinFit.Result loaded=MixinFit.evaluate(MixinPlayerWorldCallbackAdapter.asLoaded(raw,CarpetMixinAdapterTest::staged),CarpetMixinAdapterTest::staged);
			assertEquals(MixinFit.Verdict.FIT,loaded.verdict(),name+": "+loaded.reason());
		}
		for(String name:NAMES){byte[] raw=bytes(mixin(name));assertNotSame(raw,MixinPlayerWorldCallbackAdapter.asLoaded(raw,CarpetMixinAdapterTest::staged),name);}
		withAdapters("off",()->{for(String name:NAMES)try{byte[] raw=bytes(mixin(name));assertSame(raw,MixinPlayerWorldCallbackAdapter.asLoaded(raw,CarpetMixinAdapterTest::staged));}catch(Exception e){throw new AssertionError(e);}});
	}
	/** End to end through the census over carpet.mixins.json: no stale suspicion with the adapters, the old ones without. */
	@Test void censusReportsNoRepairedAnchorAsMissing()throws Exception {
		TestFixtures.require(Fixture.THIRD_PARTY,Files.isRegularFile(CARPET),"real fixture required: "+CARPET);
		byte[] config;try(ZipFile zip=new ZipFile(CARPET.toFile())){config=zip.getInputStream(zip.getEntry("carpet.mixins.json")).readAllBytes();}
		Function<String,byte[]> resource=path->{if(!path.startsWith("carpet/"))return path.startsWith("net/")?staged(path):null;
			try(ZipFile zip=new ZipFile(CARPET.toFile())){var e=zip.getEntry(path);return e==null?null:zip.getInputStream(e).readAllBytes();}catch(Exception e){throw new AssertionError(e);}};
		List<ModCatalog.Entry> previous=ModCatalog.everything();
		Map<String,Map<String,CompatibilityFinding.Confidence>> seen=new HashMap<>();
		try{
			MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned("carpet.mixins.json","carpet",Ecosystem.FABRIC)));
			ModCatalog.publish(List.of(new ModCatalog.Entry(Ecosystem.FABRIC,"carpet","Carpet","26.2+v260616","",List.of(),CARPET.toString(),"","")));
			for(String state:List.of("on","off"))withAdapters(state,()->{
				CompatibilityFindings.reset();MixinCompatibility.reset();
				KernelGuestMixinAdapter.unfitMixins("carpet.mixins.json",config,resource);
				Map<String,CompatibilityFinding.Confidence> rows=new HashMap<>();
				for(String name:NAMES)CompatibilityFindings.all().stream().filter(f->f.id().equals(MixinCompatibility.id("carpet.mixins.json","carpet.mixins."+name)))
						.forEach(f->rows.put(name,f.confidence()));
				seen.put(state,rows);});
		}finally{CompatibilityFindings.reset();MixinCompatibility.reset();MixinConfigOwners.reset();ModCatalog.publish(previous);}
		assertEquals(Map.of(),seen.get("on"),"with the adapters no repaired Carpet mixin is suspected");
		assertEquals(CompatibilityFinding.Confidence.SUSPECTED,seen.get("off").get(NAMES.get(2)),"negative control: "+seen.get("off"));
		assertEquals(CompatibilityFinding.Confidence.SUSPECTED,seen.get("off").get(NAMES.get(0)),"negative control: "+seen.get("off"));
	}
	@Test void vanillaAndDisabledRepairLeaveAllReleasedHandlersUntouched()throws Exception {
		Path vanilla=TestFixtures.vanillaJar();
		for(String name:NAMES){ClassNode c=mixin(name);byte[] before=bytes(c);java.util.function.Function<String,ClassNode> resolver=n->{try{return n.startsWith("net/minecraft/")?from(Fixture.MC_LIBRARIES,vanilla,n):null;}catch(Exception e){throw new AssertionError(e);}};
			assertEquals(0,MixinPlayerWorldCallbackAdapter.adapt(c,resolver)+MixinFluidReactionAdapter.adapt(c,resolver));assertArrayEquals(before,bytes(c));}
		withAdapters("off",()->{for(String name:NAMES)try{assertEquals(0,adapt(mixin(name)));}catch(Exception e){throw new AssertionError(e);}});
	}
}
