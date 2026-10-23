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

package net.forbric.kernel.mixin;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.platform.container.ContainerHandleURI;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.service.IAdviceProvider;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IFeatureValidator;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.IMixinInternal;
import org.spongepowered.asm.service.IMixinService;
import org.spongepowered.asm.service.ITransformer;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.util.ReEntranceLock;

import net.fabricmc.api.EnvType;

import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * The kernel IS the Mixin service — the sovereign successor to Fabric Loader's {@code MixinServiceKnot} and to
 * ModLauncher's service, with no loader beneath it.
 *
 * <p>Mixin discovers this through {@code META-INF/services/org.spongepowered.asm.service.IMixinService} on the
 * BOOT classpath, so this class (and the whole {@code net.forbric.kernel.mixin} package) is parent-loaded, while
 * every class it hands Mixin comes from the one {@link ForbricClassLoader}. Bytecode is always served
 * <em>pre</em>-mixin: serving woven bytes would make the weaver re-weave its own output.
 *
 * <p>The two config-rewriting mechanisms below are carried over from the old weld's substrate patches (0007/0008),
 * where they were proven necessary. They exist because guest mixins are written against <i>vanilla</i> bytecode
 * while the merged base is vanilla+Forge+NeoForge byte-merged, so an anchor a mixin expects may have moved. They
 * are first-class kernel logic here rather than a patch on someone else's loader — and both are off unless the
 * corresponding system property names a config, so the default is strict, unrelaxed Mixin behaviour.
 */
public final class ForbricMixinService
		implements IMixinService, IClassProvider, IClassBytecodeProvider, ITransformerProvider, IClassTracker {
	/**
	 * Report every misfitting injection instead of silently skipping it. Read once: Mixin reads configs during
	 * bootstrap, and a mid-run flip would give an inconsistent picture.
	 */
	private static final boolean DIAGNOSTICS = Boolean.getBoolean("forbric.mixinDiagnostics");

	private static volatile ForbricClassLoader gameLoader;
	private static volatile EnvType envType = EnvType.SERVER;
	private static volatile IMixinTransformer transformer;

	private final ReEntranceLock lock = new ReEntranceLock(1);

	/** The side Mixin prepares configs for: which of a config's {@code client}/{@code server} arrays is applied. */
	public static EnvType side() {
		return envType;
	}

	/** Points the service at the transforming loader + side. Must be called before {@code MixinBootstrap.init()}. */
	public static void bind(ForbricClassLoader loader, EnvType side) {
		gameLoader = loader;
		ADAPTER_CLASS_CACHE.clear();
		envType = side;
		NativeGameReferences.bind(loader);
	}

	/** The weaver Mixin handed us via {@link #offer}, or {@code null} before bootstrap. */
	public static IMixinTransformer getTransformer() {
		return transformer;
	}

	private static ForbricClassLoader loader() {
		ForbricClassLoader l = gameLoader;
		if (l == null) throw new IllegalStateException("ForbricMixinService used before bind()");

		return l;
	}
	/** Late structural plans register against their derived target, never a table of target names. Both bytecode
	 * views share the loader's generation, so an in-flight old adapter reader cannot republish stale bytes. */
	public static boolean registerBeforeDefinition(String target,Runnable registration){java.util.Objects.requireNonNull(registration);String binary=target.replace('/','.');ForbricClassLoader current=loader();return current.registerBeforeDefinition(binary,()->{try{registration.run();}finally{ADAPTER_CLASS_CACHE.remove(binary.replace('.','/')+".class");}});}
	/** Preflight and the actual weaver must see the same offered callback transport. */
	static byte[] absorbedCallbacksAsLoaded(byte[] bytes, java.util.function.Function<String,byte[]> resource) {
		if(gameLoader==null||bytes==null)return bytes;
		ClassNode source=new ClassNode();new ClassReader(bytes).accept(source,ClassReader.EXPAND_FRAMES);
		// The native shape first, as getClassNode does: a call put back into its method, a value kept in its local, a @Local
		// pinned — so the transports below see a call that is there again and leave it to the mod's own injector.
		java.util.function.Function<String,ClassNode> code=owner->{byte[] current=resource.apply(owner+".class");return current==null?null:MixinFit.parse(current);};
		int changed=NativeCallInlining.adapt(source,code,NativeGameReferences::reference,loader());
		changed+=NativeLocalMaterialization.adapt(source,code,NativeGameReferences::reference,loader());
		changed+=NativeLocalPins.adapt(source,code,NativeGameReferences::reference);
		changed+=NativeEntryRelay.adapt(source,code,NativeGameReferences::reference,loader());
		changed+=UncalledDecisionEntries.adapt(source,owner->{byte[] current=resource.apply(owner+".class");return current==null?null:MixinFit.parse(current);},NativeGameReferences::reference);
		changed+=NativeDecisionIsland.adapt(source,code,NativeGameReferences::reference,loader());
		changed+=ReplacedDecisionValues.adapt(source,code,NativeGameReferences::reference);
		// With the local variable tables: the decisions are matched by the locals their answers are kept in.
		changed+=CarrierDecisions.adapt(source,owner->{byte[] current=resource.apply(owner+".class");if(current==null)return null;ClassNode tables=new ClassNode();new ClassReader(current).accept(tables,0);return tables;},NativeGameReferences::reference);
		changed+=CarrierReturnedCalls.adapt(source,owner->{byte[] current=resource.apply(owner+".class");if(current==null)return null;ClassNode tables=new ClassNode();new ClassReader(current).accept(tables,0);return tables;},NativeGameReferences::reference);
		changed+=CarrierDefaultChains.adapt(source,owner->{byte[] current=resource.apply(owner+".class");return current==null?null:MixinFit.parse(current);},NativeGameReferences::reference);
		// An injector these two move binds as moved, not as compiled: judged so, a mixin whose only injector they carry is not UNFIT.
		changed+=CarrierHelperResults.adapt(source,owner->{byte[] current=resource.apply(owner+".class");if(current==null)return null;ClassNode tables=new ClassNode();new ClassReader(current).accept(tables,0);return tables;},NativeGameReferences::reference);
		changed+=CarrierMethodReferences.adapt(source,owner->{byte[] current=resource.apply(owner+".class");if(current==null)return null;ClassNode tables=new ClassNode();new ClassReader(current).accept(tables,0);return tables;},NativeGameReferences::reference,loader());
		changed+=NativeSliceOccurrences.adapt(source,code,NativeGameReferences::reference);
		changed+=NativeConsumerStores.adapt(source,code,NativeGameReferences::reference);
		changed+=NativeValueStores.adapt(source,code,NativeGameReferences::reference);
		changed+=MixinAbsorbedCallbackTransport.adapt(source,owner->{byte[] current=resource.apply(owner+".class");return current==null?null:MixinFit.parse(current);},
			owner->NativeGameReferences.reference(MixinStubRebind.ecosystemOf(source.name),owner),
			owner->NativeGameReferences.peers(MixinStubRebind.ecosystemOf(source.name),owner),loader());
		changed+=MixinOperationSeamTransport.adapt(source,owner->{byte[] current=resource.apply(owner+".class");return current==null?null:MixinFit.parse(current);},NativeGameReferences::reference,loader());
		if(changed==0)return bytes;org.objectweb.asm.ClassWriter out=new org.objectweb.asm.ClassWriter(0);source.accept(out);return out.toByteArray();
	}

	// --- IMixinService ---

    /** Claims a complete original callback group against the actual current caller/bridge contract before config removal. */
    public static boolean registerSourceCallbacks(ClassNode source) {
        if(source==null||!MergedBaseMixinCompat.sourceMayBeClaimed(source.name))return false;
        // Not bound: no game loader to read the callers from, so nothing can be claimed (only off-game callers see this).
        if(gameLoader==null)return false;
        try { return net.forbric.kernel.boot.LootSourceCallbacks.offer(loader(),source,ForbricMixinService::readAdapterClass); }
        catch(RuntimeException unavailable){Caught.warn(unavailable,"[Forbric/Mixin] the original callback group of %s remains unclaimed",source.name);return false;}
    }

	/**
	 * {@code "Forbric"} to Mixin, the kernel and anything else without a platform of its own; to a mod's code, and to
	 * unowned code such as a bundled library that a mod's code drives, the name the mod's platform's service reports.
	 * See {@link MixinPlatformIdentity}.
	 */
	@Override
	public String getName() {
		return MixinPlatformIdentity.serviceName();
	}

	@Override
	public boolean isValid() {
		return true;
	}

	@Override
	public void prepare() {
	}

	@Override
	public MixinEnvironment.Phase getInitialPhase() {
		return MixinEnvironment.Phase.PREINIT;
	}

	@Override
	public void offer(IMixinInternal internal) {
		if (internal instanceof IMixinTransformerFactory) {
			transformer = ((IMixinTransformerFactory) internal).createTransformer();
		}
	}

	@Override
	public void init() {
	}

	@Override
	public void beginPhase() {
	}

	@Override
	public void checkEnv(Object bootSource) {
	}

	@Override
	public ReEntranceLock getReEntranceLock() {
		return lock;
	}

	@Override
	public IClassProvider getClassProvider() {
		return this;
	}

	@Override
	public IClassBytecodeProvider getBytecodeProvider() {
		return this;
	}

	@Override
	public ITransformerProvider getTransformerProvider() {
		return this;
	}

	@Override
	public IClassTracker getClassTracker() {
		return this;
	}

	@Override
	public IMixinAuditTrail getAuditTrail() {
		return null;
	}

	@Override
	public IFeatureValidator getFeatureValidator() {
		return IFeatureValidator.ALLOW_ALL;
	}

	@Override
	public IAdviceProvider getAdviceProvider() {
		return IAdviceProvider.GENERIC;
	}

	@Override
	public Collection<String> getPlatformAgents() {
		return Collections.singletonList("org.spongepowered.asm.launch.platform.MixinPlatformAgentDefault");
	}

	@Override
	public IContainerHandle getPrimaryContainer() {
		try {
			URL source = ForbricMixinService.class.getProtectionDomain().getCodeSource().getLocation();
			return new ContainerHandleURI(source.toURI());
		} catch (Throwable t) {
			throw new IllegalStateException("cannot resolve the kernel's own code source for Mixin", t);
		}
	}

	@Override
	public Collection<IContainerHandle> getMixinContainers() {
		return Collections.emptyList();
	}

	@Override
	public String getSideName() {
		return envType.name();
	}

	@Override
	public MixinEnvironment.CompatibilityLevel getMinCompatibilityLevel() {
		return MixinEnvironment.CompatibilityLevel.JAVA_8;
	}

	@Override
	public MixinEnvironment.CompatibilityLevel getMaxCompatibilityLevel() {
		return MixinEnvironment.CompatibilityLevel.JAVA_25;
	}

	@Override
	public ILogger getLogger(String name) {
		return new ForbricMixinLogger(name);
	}

	// --- IClassProvider ---

	@Override
	public URL[] getClassPath() {
		// Mixin only used this to find itself, and the kernel reports a correct CodeSource in getPrimaryContainer.
		return new URL[0];
	}

	@Override
	public Class<?> findClass(String name) throws ClassNotFoundException {
		return loader().loadClass(name);
	}

	@Override
	public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
		return Class.forName(name, initialize, loader());
	}

	@Override
	public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
		return Class.forName(name, initialize, ForbricMixinService.class.getClassLoader());
	}

	// --- IClassBytecodeProvider ---

	/** @deprecated Mixin's legacy accessor; it declares only IOException, so a miss returns null rather than throwing. */
	@Deprecated
	public byte[] getClassBytes(String name, String transformedName) throws IOException {
		return loader().getPreMixinClassBytes(name);
	}

	public byte[] getClassBytes(String name, boolean runTransformers) throws ClassNotFoundException, IOException {
		byte[] bytes = loader().getPreMixinClassBytes(name);
		if (bytes == null) throw new ClassNotFoundException(name);

		return bytes;
	}

	@Override
	public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {
		return getClassNode(name, true);
	}

	@Override
	public ClassNode getClassNode(String name, boolean runTransformers) throws ClassNotFoundException, IOException {
		return getClassNode(name, runTransformers, 0);
	}

	@Override
	public ClassNode getClassNode(String name, boolean runTransformers, int readerFlags)
			throws ClassNotFoundException, IOException {
		ClassNode node = new ClassNode();
		byte[] bytes = getClassBytes(name, runTransformers);
		new ClassReader(bytes).accept(node, readerFlags);
		// A mixin its config's plugin added is in no list the config-time pass read: judged here, where Mixin first reads
		// it, so a carrier retarget is planned for it as for a listed one.
		KernelGuestMixinAdapter.judgeLate(name.replace('.', '/'), bytes);
		// The one seam MixinInfo.loadMixinClass reads a mixin through: a retarget plan the adapter remembered for
		// this mixin is applied to the node Mixin receives, never to jar bytes.
		MixinRetarget.applyRemembered(name, node);
		// …and a closed loot callback group the bridge runs, when it is only part of its mixin, is taken out of it here.
		net.forbric.kernel.boot.LootSourceCallbacks.stripPartialGroup(node);
		// …and a single-point injector compiled with an array-valued `at` (another Mixin fork's shape) is given the
		// shape this Mixin declares, before MixinExtras' pre-apply transformer casts it.
		MixinAtShape.normalise(node);
		// …and a call an injector anchors on, which the surviving carrier now makes inside a helper of its own, is made in the
		// method again: the helper is inlined there before any adapter below, or Mixin, reads the target.
		NativeCallInlining.adapt(node, this::mergedBaseNodeWithCode, NativeGameReferences::reference, loader());
		// …and a value a @Local asks for at a call, which the merged method hands that call straight from the stack, is also
		// kept in the local the native method keeps it in.
		NativeLocalMaterialization.adapt(node, this::mergedBaseNodeWithCode, NativeGameReferences::reference, loader());
		// …and a @Local that names one local at the native point but several at the merged one reads the local its own loader
		// hands it.
		NativeLocalPins.adapt(node, this::mergedBaseNodeWithCode, NativeGameReferences::reference);
		// …and a native decision method an injector enters, whose calls the carrier replaced with its own decision, is asked
		// again where it was, ahead of the carrier's.
		NativeEntryRelay.adapt(node, this::mergedBaseNodeWithCode, NativeGameReferences::reference, loader());
		// …and a HEAD injector on a static decision the merged game no longer calls wraps the carrier's own decision at every place
		// the native game asked it, handed the native arguments, its cancel the answer there.
		UncalledDecisionEntries.adapt(node, this::mergedBaseGameCode, NativeGameReferences::reference);
		// …and a stretch of native decisions the carrier replaced with a hook of its own is asked again ahead of the hook, so an
		// injector on one of its calls binds there, and the hook is answered what the island answered.
		NativeDecisionIsland.adapt(node, this::mergedBaseNodeWithCode, NativeGameReferences::reference, loader());
		// …and a value modifier of a vanilla decision the carrier replaced with its own call, deciding the same branch, names
		// that call.
		ReplacedDecisionValues.adapt(node, this::mergedBaseNodeWithCode, NativeGameReferences::reference);
		// …and a sliced point whose one native call the carrier moved out of the slice names that call where the merged method
		// makes it, with the same operands under the same conditions.
		NativeSliceOccurrences.adapt(node, this::mergedBaseNodeWithCode, NativeGameReferences::reference);
		// …and a value modifier written for a local's one native store, which the carrier stores again on its own paths before the
		// native read of it, modifies that read instead: once per call, on the value the read sees.
		NativeConsumerStores.adapt(node, this::mergedBaseNodeWithCode, NativeGameReferences::reference);
		// …and a value modifier at the store of a call's result, where a carrier first stores another value in the local its
		// discriminator picks, names the local the merged method stores that result in (straight, or through the carrier's hook).
		NativeValueStores.adapt(node, this::mergedBaseNodeWithCode, NativeGameReferences::reference);
		// …and an expression definition naming a method that the class now references through the kernel's thunk names
		// the thunk too, so a method-reference expression (this::m) still finds the reference.
		MixinExpressionThunks.adapt(node, this::mergedBaseNodeWithCode);
		MixinCarrierCallbackAdapters.adapt(node, this::mergedBaseGameCode);
		MixinBlockQueryAdapters.adapt(node, this::mergedBaseNodeWithCode);
		MixinBlockInteractionAdapters.adapt(node, this::mergedBaseNodeWithCode);
		MixinEntitySoundCallbackAdapter.adapt(node, this::mergedBaseNodeWithCode);
		MixinHudContextAdapter.adapt(node, this::mergedBaseNodeWithCode);
		FabricRegistryLoaderMixinAdapter.adapt(node, this::mergedBaseNodeWithCode);
		FabricRegistryInitializationMixinAdapter.adapt(node, this::mergedBaseNode);
		FabricFreezeHookMixinAdapter.adapt(node, this::mergedBaseNode);
		FabricCreativePagerMixinAdapter.adapt(node, this::mergedBaseNode);
		KernelClientHookMixinAnchors.adapt(node, this::mergedBaseNodeWithCode);
		MixinGuiItemCaptureAdapter.adapt(node, this::mergedBaseNodeWithCode);
		MixinCameraRollAdapter.adapt(node, this::mergedBaseNodeWithCode);
		// …and a redirect of a vanilla call the carrier replaced at the same place, whose handler only conditions it,
		// forwards the carrier's call there instead.
		ReplacedCallRedirects.adapt(node, this::mergedBaseNodeWithCode);
		// …and a redirect or injector at a vanilla decision the carrier replaced with a decision of its own (the same branch,
		// the same way or the opposite one, or the same local) moves onto the carrier's decision, its answer mapped.
		CarrierDecisions.adapt(node, this::mergedBaseGameCode, NativeGameReferences::reference);
		// …and a wrap of a vanilla call whose answer was the method's answer, where the carrier answers its own way, moves to
		// that answer on exactly the ways the call was made.
		CarrierReturnedCalls.adapt(node, this::mergedBaseGameCode, NativeGameReferences::reference);
		// …and a locals capture reads, as @Locals, the native locals where the merged body keeps them (before the points are
		// renumbered below: it reads the point as the native body counts it).
		MixinCapturedLocals.adapt(node, this::mergedBaseGameCode);
		// …and an ordinal counted on a vanilla call the carrier makes fewer times names the occurrence it kept.
		ThinnedCallOrdinals.adapt(node, this::mergedBaseNodeWithCode);
		// …and an unnumbered call point counts only the native occurrences where the carrier added more of that call.
		AddedCallOccurrences.adapt(node, this::mergedBaseGameCode);
		// …and a locals capture that would throw an Error no handler sees is made to skip and warn instead.
		MixinLocalsCapture.soften(node);
		// …and an injection point naming a call the surviving carrier gave extra parameters is pointed at the
		// longer call, for the injectors whose handler does not describe that call's arguments.
		MixinAtWidenedCall.widen(node, this::mergedBaseNodeWithCode);
		// …and an injection point on a call the kernel relocated out of its method (MinecraftForge's ItemStack.useOn)
		// selects the one-call relay that now makes it.
		MixinRelocatedCall.adapt(node, this::mergedBaseNodeWithCode);
		// …and an @Inject at the start of a carrier's model-data overload nothing calls (MinecraftForge's tesselateBlock(…,
		// ModelData)) also starts the overload's edge twin, which the merged vanilla signature now enters first.
		MixinForgeModelDataEdge.adapt(node, this::mergedBaseNodeWithCode);
		// …and any structurally proven @WrapOperation whose call the carrier reordered or widened is wrapped, so it binds
		// to the merged call and its handler still receives the arguments it was written for (never a @Redirect: it
		// would replace the carrier's call).
		MixinWrapOperationShim.adapt(node, this::mergedBaseNodeWithCode);
		// A redirected collection source follows only a proved copy-prefix into a pure stream composition.
		MixinCollectionSourceAdapter.adapt(node, this::mergedBaseNodeWithCode);
		// A copied shared value is preserved at the same proved result consumer, never replaced by a live value.
		MixinSharedResultTransport.adapt(node, MixinStubRebind.ecosystemOf(node.name), this::mergedBaseNodeWithCode);
		// A default predicate is transported only while its actual virtual dispatch and final body remain proved.
		MixinDefaultPredicateAdapter.adapt(node, this::mergedBaseNodeWithCode);
		MixinDefaultPredicateTransport.adapt(node, MixinStubRebind.ecosystemOf(node.name), this::mergedBaseNodeWithCode);
		MixinReturnDecorationAdapter.adapt(node, MixinStubRebind.ecosystemOf(node.name), this::mergedBaseNodeWithCode);
		MixinDefaultCallbackTransport.adapt(node, MixinStubRebind.ecosystemOf(node.name), this::mergedBaseNodeWithCode);
		MixinNativePredicateSeam.adapt(node, this::mergedBaseNodeWithCode,
				owner -> NativeGameReferences.reference(MixinStubRebind.ecosystemOf(node.name), owner));
		MixinAbsorbedCallbackTransport.adapt(node, this::mergedBaseNodeWithCode,
			owner -> NativeGameReferences.reference(MixinStubRebind.ecosystemOf(node.name), owner),
			owner -> NativeGameReferences.peers(MixinStubRebind.ecosystemOf(node.name), owner), loader());
		MixinOperationSeamTransport.adapt(node, this::mergedBaseNodeWithCode,NativeGameReferences::reference,loader());
		MixinSharedPredicateSeam.adapt(node, this::mergedBaseNodeWithCode,
				owner -> NativeGameReferences.reference(MixinStubRebind.ecosystemOf(node.name), owner),
				owner -> {
					ClassNode original = NativeGameReferences.reference(net.forbric.api.Ecosystem.NEOFORGE, owner);
					return original != null ? original : NativeGameReferences.runtime(net.forbric.api.Ecosystem.NEOFORGE, owner);
				});
		// The modifier stays on its native call where a native decision island can hold it, and the predicate copy the carrier's
		// hook evaluates reads what the call answered there.
		MixinCrossHostPredicateIsland.adapt(node, this::mergedBaseNodeWithCode,
				owner -> NativeGameReferences.reference(MixinStubRebind.ecosystemOf(node.name), owner),
				ForbricMixinService::registerBeforeDefinition,
				(mixin, handler) -> NativeDecisionIsland.heard(mixin, handler, this::mergedBaseNodeWithCode, NativeGameReferences::reference, loader()));
		MixinPredicateDelegateAdapter.adapt(node, MixinStubRebind.ecosystemOf(node.name), this::mergedBaseNodeWithCode);
		MixinResourceContinuationAdapter.adapt(node, MixinStubRebind.ecosystemOf(node.name), this::mergedBaseNodeWithCode);
		MixinUnusedArgumentObserverAdapter.adapt(node, MixinStubRebind.ecosystemOf(node.name), this::mergedBaseNodeWithCode);
		MixinNullableCompositeCallback.adapt(node, MixinStubRebind.ecosystemOf(node.name), this::mergedBaseNodeWithCode);
		MixinDecodeScopeAdapter.adapt(node, this::mergedBaseNodeWithCode);
		// …and a Fabric mod's wrap of vanilla's is(Items.SHEARS) also answers the carrier's canPerformAction(SHEARS_*)
		// that replaced it in six merged bodies (BCLib's tag-based shears), with the carrier's answer as its original.
		MixinShearsRelay.adapt(node, this::mergedBaseNodeWithCode);
		// …and a target whose NUMBER the merge gave to a carrier's anonymous class is moved to where vanilla's
		// body went. Before the twin pass: the class this lands on may itself have a renamed twin.
		MixinAnonymousRetarget.retarget(node, this::mergedBaseHas);
		// …and a target the byte merge had to rename gets its twin added, because the merged code that runs
		// instantiates the renamed copy and the mixin names only the vanilla one.
		MixinMergedTwin.addTwins(node, MixinMergedTwin.enabled() ? this::mergedBaseHas : binary -> false);
		// …and an @Inject handler written for the other ecosystem's shape of the one surviving target is wrapped,
		// so it still receives the values it asked for rather than failing the whole mixin class — on the pruner's
		// record, or on the census of lambdas whose captures a carrier patch reordered (wover-events' WorldLoader hook).
		MixinHandlerShim.adapt(node, this::mergedBaseNode);
		InsertedLambdaArgumentShim.adapt(node, this::mergedBaseNodeWithCode);
		// …and a name-only @Inject selector that Mixin would bind to the other ecosystem's overload, declared first,
		// is pinned to the one overload its handler fits, instead of failing the whole mixin class on the first one;
		// whatever still cannot bind is explained rather than left as "Invalid descriptor". After the shim, never
		// before it: a diagnosis for something that is about to be repaired is a false alarm.
		MixinOverloadPin.pin(node, this::mergedBaseNode);
		// …and an @Inject the carrier put behind a branch of its own moves ahead of that branch, where it runs on every way it
		// runs natively. Before the rebind below: an injector on a stub that only forwards is read on the overload here.
		CarrierGuardedPoints.adapt(node, this::mergedBaseGameCode, NativeGameReferences::reference);
		// …and a redirect inside a native lambda the carrier replaced with a method reference whose inherited implementation folds in
		// the lambda's leftover captured value moves onto the reference's call, made in a method the reference is pointed at, and
		// runs the native lambda's body with the mod's handler at the call.
		CarrierMethodReferences.adapt(node, this::mergedBaseGameCode, NativeGameReferences::reference, loader());
		// …and an @Inject at a vanilla call the carrier now makes inside a helper of its own class, whose boolean answer is true
		// exactly when it made the call, runs on that answer at each of the helper's calls, its @Locals read where the merged
		// method keeps the same values.
		CarrierHelperResults.adapt(node, this::mergedBaseNodeWithCode, NativeGameReferences::reference);
		FabricClientMixinAnchors.adapt(node, this::mergedBaseNodeWithCode);
		// …and a redirect of a vanilla call the carrier now makes through a method of its own (its inherited implementation
		// makes the vanilla call) moves onto that method, running its implementation with the handler where it is inherited —
		// one handler for every method it is bound to, when each of them lost the call to the same carrier call (a bed's
		// occupancy write in the sleep start and in the wake-up lambda). After the client anchors: an empty redirect they turn
		// into skipping the carrier's call keeps that meaning.
		CarrierDefaultChains.adapt(node, this::mergedBaseGameCode, NativeGameReferences::reference);
		MixinSpriteLoaderCallbackAdapter.adapt(node, this::mergedBaseNodeWithCode);
		MixinKeyActionAdapter.adapt(node, this::mergedBaseGameCode);
		MixinStructurePlacementAdapter.adapt(node, this::mergedBaseNodeWithCode);
		FabricBlockStateCodecMixinAdapter.adapt(node, this::mergedBaseNodeWithCode);
		// …and a @Redirect of BlockState.isAir(), which the merged base makes as isEmpty() in the same method, moves
		// onto that call instead of binding nothing (fabric-block-api's LevelChunkSection air check).
		FabricAirCheckMixinAdapter.adapt(node, this::mergedBaseNodeWithCode);
		// …and a @WrapOperation of CustomPacketPayload.codec(), which the merged base gives two more arguments,
		// is handed those arguments instead of binding nothing (fabric-networking's payload codec wrapper).
		FabricCustomPayloadCodecMixinAdapter.adapt(node, this::mergedBaseNodeWithCode);
		MixinPlayerWorldCallbackAdapter.adapt(node, this::mergedBaseNodeWithCode);
		MixinFluidReactionAdapter.adapt(node, this::mergedBaseNodeWithCode);
		// …and an @Inject anchored on a call the merged body makes through a subtype of the same method
		// (Decoder.parse → Codec.parse: lithostitched's Fabric load predicates) moves to that one call.
		MixinSubtypeOwnerRetarget.adapt(node, this::mergedBaseNodeWithCode);
		// …and an injector written for vanilla's signature of a method nothing in the merged game calls any more moves to
		// the overload the carrier added in its place, still handed vanilla's arguments (LiquidBounce's X-Ray face test on
		// ModelBlockRenderer.shouldRenderFace, which NeoForge gave the block's position). After the pin: a pinned selector
		// spells vanilla's descriptor, and this reads both forms.
		MixinTwinRebind.adapt(node, this::mergedBaseNodeWithCode);
		// …and, LAST, a Fabric mod's injector that Mixin still binds to a carrier's delegating stub moves to the method
		// carrying the body — Mixin binds a name-only selector to the FIRST declared overload, which is that stub.
		// Last so every adapter above has had its say: CarrierGuardedPoints moves an elytra check ahead of NeoForge's
		// gliding guard, and a rebind first would have changed the selector it reads.
		// …and an injector at the start or end of a method whose whole body a carrier renamed, which the merged game also
		// runs without passing through that method, stays where it was written and also edges each method that only
		// forwards to the renamed body (ALLOW_LOAD: on addEntity, before NeoForge's join event, and on players'
		// addNewEntityWithoutEvent).
		MixinRenamedBodyEdges.adapt(node, this::mergedBaseNodeWithCode);
		// …and an injector at the start or end of a method a carrier split into pieces the merged game runs one by one, and never
		// through the method (NeoForge's HUD layers draw Hud.extractPlayerHealth's four pieces), moves to the start of the first
		// piece or the end of the last; a cancel at the start skips the later pieces of that run.
		MixinSplitBodyEdges.adapt(node, this::mergedBaseNodeWithCode);
		MixinStubRebind.adapt(node, this::mergedBaseNodeWithCode);
		// …and an injector anchored in a body a carrier moved behind a builder stub (new builder, hand it over, build) moves
		// to the overload that makes its calls.
		MixinBuilderStubRebind.adapt(node, this::mergedBaseGameCode);
		// …and a point shifted BY a count of nodes lands on the instruction it lands on natively, where a carrier's code now
		// stands in that stretch (Shoulder Surfing's TAIL-2 FOV override on Camera.calculateFov landed on MinecraftForge's
		// event call, after the last read of the local it modifies). After the rebind: it reads the injector's final target.
		// On the raw bytes, which are what Mixin counts nodes in. Before the return ordinals: a point it re-spells is counted
		// over the merged body.
		MixinShiftedPoints.adapt(node, this::mergedBaseNodeWithCode);
		// …and a RETURN ordinal counted on the native body names the return of the merged body that is the same way out
		// (the last stays the last; an early one is the one the same test decides), when the merge added or dropped exits.
		// After the rebind: it reads the injector's final target. Before the native tail: that one reads a TAIL as written.
		MixinReturnOrdinals.adapt(node, this::mergedBaseGameCode);
		// …and a NeoForge or MinecraftForge mod's TAIL on a method whose early returns the kernel restored keeps every
		// return its own loader's folded body sent there. After the rebind: it reads the injector's final target.
		MixinNativeTail.adapt(node, this::mergedBaseNodeWithCode);
		// …and, after every rule that sets a handler's body aside, each such body is named for its own mixin: Mixin merges it into
		// the target by name, and two mixins whose handlers share a name would otherwise share one body.
		MixinHandlerShim.markAsides(node);
		// …and an @Inject the adapter found Mixin would reject outright ("Invalid descriptor", which fails the whole mixin)
		// is taken out, after every adapter above has had its say and only while the same rule still says so of this
		// node, so the rest of the mixin applies. Before remember: the ledger must not expect a handler that is gone. With
		// code: the rule asks whether the injector's @At finds a point in the method its name binds.
		net.forbric.kernel.transform.GuestInjectorPruner.pruneRefused(node,
				(mixin, handler) -> MixinFit.stillRejected(mixin, handler, this::mergedBaseNodeWithCode));
		// …and a PARTIAL verdict the config-time pass printed is read again on the mixin as handed over: an injector it said never
		// runs, which an adapter above moved onto a body that runs, is withdrawn from the report with where it landed.
		KernelGuestMixinAdapter.judgeAsHanded(node);
		FinalMixinApplications.remember(node);
		// The call sites its injectors take, as they will apply: a raw post-Mixin patch of another mod that finds one
		// gone is reported as a contention between the two (ContendedCallSites), not arbitrated.
		ContendedCallSites.remember(node);
		// …and, after remember has the author's own counts, an injector-level require/allow on a relaxed guest mixin
		// stops being able to abandon the whole target class: the mod is reported, the class is defined.
		if (!DIAGNOSTICS) MixinLocalsCapture.softenRequirements(node, ForbricMixinService::allOwnersRelaxed);
		// …and, last, every point that selects instructions by what they are counts its occurrences over the native body of
		// its method and selects their counterparts in the merged one, at weave time (NativeOccurrencePoint): an injector runs
		// once per native execution of its point however many occurrences a carrier added. Last, so the ledger above keeps the
		// author's own points and every adapter before reads them as written.
		NativeOccurrences.adapt(node, bytes);

		return node;
	}

	/**
	 * The merged base's node for {@code internalName}, or null when it cannot be read.
	 *
	 * <p>Read through the same pre-mixin bytes the twin check uses, and parsed with {@code SKIP_CODE}: only the
	 * member list is wanted, and a target class is often one of the biggest in the game.
	 */
	private ClassNode mergedBaseNode(String internalName) {
		return mergedBaseNode(internalName, ClassReader.SKIP_CODE);
	}

	/**
	 * The same node WITH instructions, for the rules that have to look at call sites.
	 *
	 * <p>{@link MixinAtWidenedCall} cannot be answered from declarations: the merge kept vanilla's short
	 * {@code CustomPacketPayload.codec} beside the carrier's long one, and the question is which of them the
	 * code actually calls.
	 */
	private ClassNode mergedBaseNodeWithCode(String internalName) {
		return mergedBaseNode(internalName, 0);
	}

	private ClassNode mergedBaseNode(String internalName, int flags) {
		try {
			byte[] bytes = loader().getPreMixinClassBytes(internalName.replace('/', '.'));
			if (bytes == null) return null;
			ClassNode target = new ClassNode();
			new ClassReader(bytes).accept(target, flags);
			return target;
		} catch (Throwable absent) {
			Caught.warn(absent, "[Forbric/Mixin] could not read the merged base's %s; taken as absent", internalName);
			return null;
		}
	}

	/**
	 * The same node WITH instructions, as the game's own code reads: {@link #codeView}. For the adapters that only read a
	 * target to decide where a guest's point lands — a call occurrence, a return ordinal, a local's slot. Never for one
	 * that records a fingerprint or a contract of a target method to compare with the bytes later defined: those are the
	 * instrumented bytes, so it reads those ({@link #mergedBaseNodeWithCode}).
	 */
	private ClassNode mergedBaseGameCode(String internalName) {
		try {
			return codeView(loader().getPreMixinClassBytes(internalName.replace('/', '.')));
		} catch (Throwable absent) {
			Caught.warn(absent, "[Forbric/Mixin] could not read the merged base's %s; taken as absent", internalName);
			return null;
		}
	}

	/**
	 * A target's pre-mixin bytes, with code, as the game's own instructions: without the delivery bookkeeping the kernel's
	 * stand-down writes around the hook calls of a method that calls both families' hooks
	 * ({@link net.forbric.kernel.transform.DeliveryBookkeeping}). The bookkeeping never moves, removes or repeats a game
	 * instruction, so every point decided on this view — a call and its occurrence, a return and its ordinal, a local's
	 * slot — is the same point in the bytes Mixin applies to; read with it in the way, the run in front of a hook, the
	 * locals at a call or the way a return is reached no longer read as the game's (Create's released-key hooks on
	 * {@code keyPress} and its section-change hook on {@code onMove} landed nowhere). Null for null bytes.
	 */
	static ClassNode codeView(byte[] bytes) {
		if (bytes == null) return null;
		ClassNode target = new ClassNode();
		new ClassReader(bytes).accept(target, 0);
		net.forbric.kernel.transform.DeliveryBookkeeping.strip(target);
		return target;
	}

	/** Whether the merged base (or any owned jar) carries {@code binary}. Bytes only — the class is not loaded. */
	private boolean mergedBaseHas(String binary) {
		try {
			return loader().getPreMixinClassBytes(binary) != null;
		} catch (Throwable absent) {
			Caught.warn(absent, "[Forbric/Mixin] could not read the merged base's %s; taken as absent", binary);
			return false;
		}
	}

	// --- IClassTracker ---

	@Override
	public void registerInvalidClass(String className) {
	}

	@Override
	public boolean isClassLoaded(String className) {
		return loader().isClassLoadedByName(className);
	}

	@Override
	public String getClassRestrictions(String className) {
		return "";
	}

	// --- ITransformerProvider (the kernel owns its pipeline; Mixin delegates nothing) ---

	@Override
	public Collection<ITransformer> getTransformers() {
		return Collections.emptyList();
	}

	@Override
	public Collection<ITransformer> getDelegatedTransformers() {
		return Collections.emptyList();
	}

	@Override
	public void addTransformerExclusion(String name) {
	}

	// --- resources: mixin config JSON, with the merged-base compatibility rewrites ---

	@Override
	public InputStream getResourceAsStream(String name) {
		InputStream in = loader().getGameResourceAsStream(name);
		if (in == null) return null;

		if(isMixinConfigName(name))MergedBaseMixinCompat.discoverAll(ForbricMixinService::readGameResource);
		noteLateGuest(name);
		boolean relax = isRelaxedConfig(name);
		List<String> named = suppressedMixinsFor(name);
		List<String> drop = new ArrayList<>(named);
		// The general guest-mixin adapter needs the config's own bytes to enumerate its mixins, so it runs below
		// after the JSON is read (only for things that look like mixin configs — not every resource on the path).
		boolean scanForOwned = isMixinConfigName(name) && KernelGuestMixinAdapter.enabled();
		if (!relax && drop.isEmpty() && !scanForOwned) return in;

		try (InputStream source = in) {
			byte[] bytes = source.readAllBytes();
			String json = new String(bytes, StandardCharsets.UTF_8);
			MixinCompatibility.rememberOriginalConfig(name, bytes);
            MergedBaseMixinCompat.discover(name,bytes,ForbricMixinService::readGameResource);
            named=suppressedMixinsFor(name);drop=new ArrayList<>(named);

			if (scanForOwned) {
				// Derive, from THIS config, the mixins that target a Forge/NeoForge-owned merged class — the general
				// form of MergedBaseMixinCompat's hand-listed renderer/pipeline entries. Each mixin class is a game
				// resource resolvable through the same loader, so no separate mod-jar inventory is needed.
				// The raw resource is the merged base before the transform chain: what tells a target the owning mod's
				// platform lacks too from one the chain removed; the serving jar's members digest is what tells whether
				// the shipped table speaks for that base at all (NativeAbsentTargets).
				for (String owned : KernelGuestMixinAdapter.unfitMixins(name, bytes,
						r -> readAdapterClass(r), envType, ForbricMixinService::readGameResource,
						ForbricMixinService::servingBase)) {
					if (!drop.contains(owned)) drop.add(owned);
				}
			}
			if (!named.isEmpty()) {
				java.util.Map<String, String> sources = new java.util.LinkedHashMap<>();
				for (String mixin : named) sources.put(mixin, suppressionSource(name, mixin));
				KernelGuestMixinAdapter.reportNamedSuppressions(name, bytes, sources, r -> readAdapterClass(r), envType);
			}

			if (!relax && drop.isEmpty()) {
				SERVED.put(name, bytes);
				return new ByteArrayInputStream(bytes);
			}

			if (relax) {
				// A parent config is read right after its child names it, and gives the child every option the child
				// leaves out: its injectors' defaultRequire among them. It belongs to the same mod, so it is relaxed too.
				noteParent(json);
				// Three independent relaxations, each for a different way a guest mixin meets the merged base:
				//
				//   injectors.defaultRequire -> 0   an injection point whose anchor the byte-merge removed
				//                                   (Bootstrap.bootStrap no longer calls wrapStreams()) soft-skips.
				//   overwrites.requireAnnotations -> false   an @Overwrite byte-identical to a Forge-added method.
				//   required -> false               a mixin that cannot APPLY at all — a callback whose descriptor
				//                                   no longer matches, an @Accessor for a retyped field — is
				//                                   dropped with a warning instead of aborting the launch. This is
				//                                   the only lever for apply-time failures: defaultRequire governs
				//                                   the injection *check*, which never runs if apply throws first.
				//
				// Per-injection require/expect annotations still win, so a mixin that declares its own hard
				// requirement still fails loudly. The mixin ADAPTER (M7) is the real fix; this is v1 parity.
				//
				// DIAGNOSTICS (-Dforbric.mixinDiagnostics): keep the injection requirements STRICT so every
				// misfitting injection is reported, while still setting required=false so the launch survives to
				// collect them all. Mixin itself stays silent about a relaxed, non-matching injection; the final
				// class audit emits a WARN with its owner, handler and target instead (FinalMixinApplications).
				if (!DIAGNOSTICS) {
					json = json.replaceAll("(\"requireAnnotations\"\\s*:\\s*)true", "$1false")
							.replaceAll("(\"defaultRequire\"\\s*:\\s*)\\d+", "$10");
				}

				json = json.replaceAll("(\"required\"\\s*:\\s*)true", "$1false");
				ForbricLog.debug("[Forbric/Mixin] relaxed %s for merged-base compatibility", name);
			}

			for (String mixin : drop) {
				// Remove one named mixin from the config's mixins/client/server arrays, leaving the mod's other
				// mixins to apply. Needed for a mixin that applies cleanly but breaks at RUNTIME on the merged base.
				String token = Pattern.quote("\"" + mixin + "\"");
				json = json.replaceAll(",\\s*" + token, "")
						.replaceAll(token + "\\s*,", "")
						.replaceAll(token, "");
				ForbricLog.info("[Forbric/Mixin] suppressed mixin %s from %s", mixin,
						MixinConfigOwners.describe(name));
			}

			byte[] served = json.getBytes(StandardCharsets.UTF_8);
			SERVED.put(name, served);
			return new ByteArrayInputStream(served);
		} catch (IOException e) {
			throw new RuntimeException("Forbric: failed rewriting mixin config " + name, e);
		}
	}

	/** Each config as {@link #getResourceAsStream} last served it to Mixin, after the kernel's drops. */
	private static final java.util.Map<String, byte[]> SERVED = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * The JSON Mixin read for {@code config}: what was served when this service rewrote or inspected it, the resource
	 * itself when it passed through untouched, null when there is neither.
	 */
	public static byte[] servedConfig(String config) {
		byte[] served = SERVED.get(config);
		return served != null ? served : readGameResource(config);
	}

	/** {@link #readAdapterClass}, for a pass that judges mixins after Mixin has prepared the configs. */
	public static java.util.function.Function<String, byte[]> adapterResource() {
		return ForbricMixinService::readAdapterClass;
	}

	/** Reads a game resource ({@code some/pkg/Name.class}) to its bytes, or null. */
	private static byte[] readGameResource(String resourcePath) {
		try (InputStream in = loader().getGameResourceAsStream(resourcePath)) {
			return in == null ? null : in.readAllBytes();
		} catch (IOException e) {
			Caught.warn(e, "[Forbric/Mixin] could not read game resource %s; taken as absent", resourcePath);
			return null;
		}
	}

	/**
	 * The members digest of the jar that serves {@code owner} ({@link NativeAbsentTargets#membersDigest}), the same jar
	 * {@link #readGameResource} reads it from — the merged base, or for a class of Minecraft's own libraries
	 * ({@code com/mojang/brigadier}, DataFixerUpper) that library's jar — or null when none of the game loader's jars
	 * does. Each jar is read once: the first ask reads every class in vanilla's packages there (about a quarter of a
	 * second for the merged base), and only an injector whose every target is already missing asks at all.
	 */
	private static String servingBase(String owner) {
		java.nio.file.Path jar = NativeAbsentTargets.servingJar(loader().findResource(owner + ".class"));
		if (jar == null) return null;
		String digest = BASE_DIGESTS.computeIfAbsent(jar, read -> {
			try {
				return NativeAbsentTargets.membersDigest(read);
			} catch (IOException | RuntimeException unreadable) {
				Caught.warn(unreadable, "[Forbric/Mixin] could not read the members of %s; %s is taken as served by no jar",
						read, owner);
				return "";
			}
		});
		return digest.isEmpty() ? null : digest;
	}

	private static final java.util.Map<java.nio.file.Path, String> BASE_DIGESTS = new java.util.concurrent.ConcurrentHashMap<>();

	/** Cache for {@link #readAdapterClass}: ~70 configs re-request the same merged targets. */
	private record AdapterClass(ForbricClassLoader loader,ForbricClassLoader.BytecodeGeneration generation,byte[] bytes){}
	private static final java.util.Map<String, AdapterClass> ADAPTER_CLASS_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
	private static final byte[] NOT_FOUND = new byte[0];

	/**
	 * The class bytes {@link KernelGuestMixinAdapter} resolves mixin anchors against.
	 *
	 * <p>This MUST serve post-transform-chain bytes, not raw jar bytes: the chain both ADDS members (
	 * {@code ForbricMergedBaseCompatTransformer.addMissingForgeKeyMappingLookupInitializer} installs the
	 * {@code PUTSTATIC} for {@code KeyMapping.MAP}, which {@code merge-conflicts.txt} lists as "read but never
	 * initialized") and REMOVES them ({@code dropInterfaceDefaultShadowingOverrides} deletes methods across
	 * {@code net/minecraft/client/gui/**}). Resolving against the raw jar would judge the mixin against bytecode
	 * that never reaches Mixin — the orphaned-field check in particular would report a false hazard on
	 * {@code KeyMapping.MAP}.
	 *
	 * <p>Falls back to the raw resource for a MIXIN's own class, which is not a game class and so is not transformed.
	 */
	private static byte[] readAdapterClass(String resourcePath) {
		String className=resourcePath.endsWith(".class")?resourcePath.substring(0,resourcePath.length()-6).replace('/','.'):null;
		String path=className==null?resourcePath:className.replace('.','/')+".class";
		for(;;){ForbricClassLoader current=loader();var generation=className==null?null:current.bytecodeGeneration(className);
			AdapterClass cached=ADAPTER_CLASS_CACHE.get(path);
			if(cached!=null&&cached.loader==current&&java.util.Objects.equals(cached.generation,generation)&&current==gameLoader&&(className==null||current.isBytecodeGenerationCurrent(className,generation)))return cached.bytes==NOT_FOUND?null:cached.bytes;
			byte[] bytes=null;
			if(className!=null){try{bytes=current.getPreMixinClassBytes(className);}catch(Throwable notAGameClass){
				Caught.warn(notAGameClass,"[Forbric/Mixin] could not build the pre-mixin bytes of %s; reading its raw resource instead",className);bytes=null;}}
			if(bytes==null){try(InputStream input=current.getGameResourceAsStream(path)){bytes=input==null?null:input.readAllBytes();}catch(IOException unavailable){
				Caught.warn(unavailable,"[Forbric/Mixin] could not read game resource %s; taken as absent",path);bytes=null;}}
			if(current!=gameLoader||className!=null&&!current.isBytecodeGenerationCurrent(className,generation))continue;
			AdapterClass entry=new AdapterClass(current,generation,bytes==null?NOT_FOUND:bytes);ADAPTER_CLASS_CACHE.put(path,entry);
			if(current!=gameLoader||className!=null&&!current.isBytecodeGenerationCurrent(className,generation)){ADAPTER_CLASS_CACHE.remove(path,entry);continue;}
			if(bytes!=null&&className!=null){ClassNode source=new ClassNode();new ClassReader(bytes).accept(source,ClassReader.EXPAND_FRAMES);if(MergedBaseMixinCompat.sourceMayBeClaimed(source.name))net.forbric.kernel.boot.LootSourceCallbacks.offer(current,source,ForbricMixinService::readAdapterClass);}
			if(current==gameLoader&&(className==null||current.isBytecodeGenerationCurrent(className,generation)))return bytes;
			ADAPTER_CLASS_CACHE.remove(path,entry);
		}
	}

	/** Whether {@code name} looks like a mixin config file — the only resources the owned-target scan should read. */
	/**
	 * Whether {@code name} is a mixin config: one the boot registered as such (what a mod declared, whatever it is called),
	 * or a resource named the way configs usually are (a config registered later, by a mod at run time). By the name alone,
	 * a mod whose config is called anything else — {@code modmenu_mixins.modmenu.json} — had none of its mixins read by
	 * the guest-mixin adapter: no owner recorded, so every pass that asks which family wrote a mixin left that mod's alone.
	 */
	static boolean isMixinConfigName(String name) {
		if (name == null) return false;
		if (registeredConfigs.contains(name)) return true;
		int slash = name.lastIndexOf('/');
		String file = slash >= 0 ? name.substring(slash + 1) : name;
		return file.endsWith(".mixins.json")
				|| file.endsWith(".mixin.json")
				|| (file.startsWith("mixins.") && file.endsWith(".json"));
	}

	/**
	 * Mixin entries to drop from config {@code configName}: the built-in {@link MergedBaseMixinCompat} list plus
	 * anything named by {@code -Dforbric.suppressMixins} (csv of {@code configName:MixinEntry}), MINUS anything
	 * named by {@code -Dforbric.keepMixins}. Neither a config name nor a mixin entry contains a colon.
	 *
	 * <p>{@code keepMixins} subtracts last, and it has to. {@link MergedBaseMixinCompat#KEPT_MIXINS} documents that
	 * knob as "the inverse of {@code SUPPRESSED_MIXINS}", but until this method honoured it the only thing it could
	 * actually override was {@link KernelGuestMixinAdapter}'s DERIVED auto-suppression — so an attempt to re-test a
	 * hand-pinned entry (measured on {@code jade.mixins.json:FogRendererMixin}) changed nothing at all, and looked
	 * from the log exactly like the mixin having been tried and re-suppressed. Doc and behaviour disagreeing is the
	 * bug; a knob that silently no-ops on half its documented surface is worse than not having it, because it
	 * answers a measurement question with a wrong answer instead of an error. The blunt {@code
	 * -Dforbric.mergedBaseCompat=off} stays the way to drop the whole list at once.
	 */
	static List<String> suppressedMixinsFor(String configName) { // package-private for ForbricMixinServiceTest
		List<String> out = new ArrayList<>();

		if (MergedBaseMixinCompat.enabled()) {
			collectSuppressed(MergedBaseMixinCompat.SUPPRESSED_MIXINS, configName, out);

		}

		String csv = System.getProperty("forbric.suppressMixins");
		if (csv != null && !csv.isEmpty()) {
			collectSuppressed(List.of(csv.split(",")), configName, out);
		}

		String keep = System.getProperty("forbric.keepMixins");
		if (keep != null && !keep.isEmpty() && !out.isEmpty()) {
			List<String> kept = new ArrayList<>();
			collectSuppressed(List.of(keep.split(",")), configName, kept);
			out.removeAll(kept);
		}

		return out.isEmpty() ? Collections.emptyList() : out;
	}

	/** Which list put {@code configName:mixin} in {@link #suppressedMixinsFor}, in the words the report uses. */
	static String suppressionSource(String configName, String mixin) {
		String entry = configName + ":" + mixin;
		if (MergedBaseMixinCompat.enabled() && MergedBaseMixinCompat.SUPPRESSED_MIXINS.contains(entry)) {
			return "source protocol: " + MergedBaseMixinCompat.reason(configName,mixin);
		}

		return "-Dforbric.suppressMixins";
	}

	private static void collectSuppressed(List<String> entries, String configName, List<String> out) {
		for (String raw : entries) {
			String entry = raw.trim();
			if (entry.isEmpty()) continue;

			int colon = entry.indexOf(':');
			if (colon <= 0 || colon >= entry.length() - 1) continue;

			if (entry.substring(0, colon).trim().equals(configName)) {
				String mixin = entry.substring(colon + 1).trim();
				if (!out.contains(mixin)) out.add(mixin);
			}
		}
	}

	/**
	 * Every mixin config that came from a discovered GUEST mod — relaxed by default.
	 *
	 * <p>Populated by {@link KernelMixinBootstrap} from the exact config list it registers. The kernel authors no
	 * mixins of its own, so every registered config belongs to a guest mod; infrastructure names are excluded
	 * anyway so a future kernel-owned config would still fail loudly.
	 */
	private static volatile java.util.Set<String> guestConfigs = java.util.Set.of();

	/**
	 * Parent configs a relaxed config names ({@code "parent": "c2me.mixins.json"}). No mod declares a parent in its
	 * metadata, yet Mixin reads it right after the child and the child inherits every option it does not set itself:
	 * C2ME's 20 module configs leave their injectors' {@code defaultRequire} to {@code c2me.mixins.json}, which says 1,
	 * so one injector of theirs that found nothing failed its whole target class (the dedicated server's watchdog, and
	 * the server with it) although the child was relaxed.
	 */
	private static final java.util.Set<String> guestParents = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static final Pattern PARENT = Pattern.compile("\"parent\"\\s*:\\s*\"([^\"]+)\"");

	/** Records the parent config a relaxed config's JSON names, if any: that parent is relaxed in its turn. */
	static void noteParent(String json) {
		java.util.regex.Matcher parent = PARENT.matcher(json);
		if (parent.find()) guestParents.add(parent.group(1));
	}

	/** Every registered mixin config name, relaxed or not — the input {@link ForeignMixinTargets} indexes. */
	private static volatile java.util.Set<String> registeredConfigs = java.util.Set.of();

	/** Every mixin config registered this run, in registration order. Empty before {@link #setGuestConfigs}. */
	public static java.util.Set<String> registeredConfigNames() {
		return registeredConfigs;
	}

	/**
	 * Records the guest mixin configs to relax. Called once, before any config is read.
	 *
	 * <p>Relaxing only {@code fabric-*} (the old hardcoded launch-script glob) meant a single unpatchable injector
	 * in ANY other real mod aborted the whole launch: {@code collective} ships a {@code collective_fabric.mixins.json}
	 * whose {@code PlayerMixin} descriptor no longer matches the merged base, and because the name does not start
	 * with {@code fabric-} it was a fatal {@code MixinApplyError} rather than a soft skip. Guest mixins are written
	 * against vanilla bytecode while the base is byte-merged, so ANY guest config can meet a moved anchor — the
	 * relaxation belongs to "is this a guest mod's config", not to one naming convention. (This mirrors the
	 * differential oracle, which relaxes every discovered guest mod's configs and excludes only infrastructure.)
	 */
	public static void setGuestConfigs(Collection<String> configs) {
        MergedBaseMixinCompat.reset();
		// Recorded BEFORE the relax gate, and unconditionally: this list is also what tells the guest-mixin adapter
		// which classes some OTHER mod's mixin will add members to (see ForeignMixinTargets). That question is
		// independent of whether configs are relaxed, so -Dforbric.relaxGuestMixins=off must not empty it.
		if (configs != null) {
			java.util.Set<String> all = new java.util.LinkedHashSet<>();
			for (String config : configs) {
				if (config != null && !config.isEmpty()) all.add(config);
			}
			// In registration order, as the getter promises: Set.copyOf kept none, and MixinAddedMembers needs the
			// order Mixin creates the configs in to tell which of two equal-priority mixins applies first.
			registeredConfigs = java.util.Collections.unmodifiableSet(all);
		}

		guestParents.clear();
		lateGuests.clear();
		if (configs == null || "off".equalsIgnoreCase(System.getProperty("forbric.relaxGuestMixins", "on"))) {
			guestConfigs = java.util.Set.of();
			return;
		}
		java.util.Set<String> guests = new java.util.LinkedHashSet<>();
		for (String config : configs) {
			if (config == null || config.isEmpty()) continue;
			if (isInfrastructureConfig(config)) continue;
			guests.add(config);
		}
		guestConfigs = java.util.Set.copyOf(guests);
	}

	/**
	 * Kernel configs, never relaxed — a genuine failure in our own code must crash loudly.
	 *
	 * <p>This used to also exclude {@code forge.}, {@code neoforge.} and {@code minecraft.}, on the theory that they
	 * name the ecosystem runtimes' own configs. One runtime config exists: NeoForge's {@code neoforge.mixins.json}
	 * (two accessors), which {@code KernelBoot} registers from the {@code --runtimeJar} it came in, like a mod's. It
	 * is relaxed like a guest's on purpose — an accessor the merged base cannot take then breaks only the NeoForge
	 * feature that casts to it, and is reported, instead of stopping the game. And the moment Forge/NeoForge GUEST
	 * configs joined the registered set, those prefixes became a live hazard: a guest config legitimately named
	 * {@code forge.mixins.json} would silently not be relaxed, so one unpatchable injector in it becomes a fatal
	 * {@code MixinApplyError} instead of the soft skip that general relaxation exists to provide.
	 *
	 * <p>Keep {@code forbric}: the kernel authors no mixins today, but if it ever does, that one must fail loudly.
	 */
	static boolean isInfrastructureConfig(String config) {
		return config.startsWith("forbric");
	}

	/**
	 * Whether this config is relaxed: it came from a guest mod, or {@code -Dforbric.relaxMixinOverwrites} names it
	 * (trailing {@code *} = prefix glob). {@code -Dforbric.relaxGuestMixins=off} restores strict behaviour.
	 *
	 * <p>Package-private rather than private so a test can pin which names relax without booting Mixin — the
	 * distinction is invisible at runtime until exactly one injector fails, at which point it decides between a soft
	 * skip and a fatal apply error.
	 */
	/** Whether every config that declares mixin {@code binary} is one the kernel relaxes (never an unknown owner). */
	static boolean allOwnersRelaxed(String binary) {
		java.util.Set<String> owners = FinalMixinApplications.configNames(binary);
		return !owners.isEmpty() && owners.stream().allMatch(ForbricMixinService::isRelaxedConfig);
	}

	/**
	 * Mixin configs a mod registered after the boot did ({@code Mixins.addConfiguration} from {@code preLaunch}): Essential's
	 * loader registers its own and those of every jar it injects as a mod. The kernel authors no mixins, so they are guest
	 * configs like the ones it registered, and are relaxed the same way; strict, fabric-networking's accessor met NeoForge's
	 * own {@code getConnection} under {@code @Overwrite}-required rules and failed the class, and the client's connection
	 * with it.
	 */
	private static final java.util.Set<String> lateGuests = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** Records {@code name} as a late guest config when it is a mixin config the boot did not register. */
	static void noteLateGuest(String name) {
		if (!isMixinConfigName(name) || registeredConfigs.isEmpty() || registeredConfigs.contains(name) || isInfrastructureConfig(name)
				|| "off".equalsIgnoreCase(System.getProperty("forbric.relaxGuestMixins", "on"))) return;
		lateGuests.add(name);
	}

	static boolean isRelaxedConfig(String name) {
		if (guestConfigs.contains(name)) return true;
		if (lateGuests.contains(name)) return true;
		if (guestParents.contains(name) && !isInfrastructureConfig(name)) return true;

		String csv = System.getProperty("forbric.relaxMixinOverwrites");
		if (csv == null || csv.isEmpty()) return false;

		for (String raw : csv.split(",")) {
			String entry = raw.trim();
			if (entry.isEmpty()) continue;

			if (entry.endsWith("*")) {
				if (name.startsWith(entry.substring(0, entry.length() - 1))) return true;
			} else if (name.equals(entry)) {
				return true;
			}
		}

		return false;
	}
}
