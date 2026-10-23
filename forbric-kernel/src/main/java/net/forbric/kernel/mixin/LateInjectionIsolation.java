/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.GenericSignatureFormatError;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;
import org.spongepowered.asm.mixin.transformer.throwables.InvalidMixinException;

import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * Gives MixinExtras' late injectors the per-mixin failure isolation Mixin gives every other injector, so that one mod's
 * late injector failing costs that injection, not the other mods' mixins on the same class.
 *
 * <h2>What upstream does</h2>
 *
 * <p>Mixin applies a target's mixins one by one in {@code MixinApplicatorStandard.apply}, and an
 * {@code InvalidMixinException} from a mixin whose config is not {@code required} is set aside
 * ({@code TargetClassContext.addSuppressed}, reported after the class is done): that mixin stops, the others go on.
 * MixinExtras' {@code @WrapOperation}, {@code @WrapWithCondition} and {@code @ModifyExpressionValue} are late injectors:
 * during that loop they only queue themselves, and {@code LateInjectionApplicatorExtension.postApply} injects and
 * checks every queued one after all the mixins are applied. That is a target-class extension
 * ({@code TargetClassContext.postApply}), outside the loop. An {@code InvalidMixinException} there leaves
 * {@code TargetClassContext.applyMixins}; {@code MixinProcessor.applyMixins} reports it and answers "not transformed",
 * and {@code MixinTransformer} hands back the class's original bytes. Every mixin of every mod on that class is gone,
 * while the mixin that failed belongs to a config that asked to be optional. Read from sponge-mixin 0.17.3 and
 * MixinExtras 0.5.4, the kernel's pinned versions.
 *
 * <p>Two ways reach it. In a player's game only one does: a late injector's own checks in {@code inject} —
 * {@code @WrapOperation} validating its handler against the call it found, or finding an instruction it cannot wrap —
 * throw {@code InvalidInjectionException} at any setting. The other needs {@code mixin.debug.countInjections} (or
 * {@code mixin.debug}, which implies it), a debug switch the kernel's test harness turns on and a player's launcher does
 * not: it makes {@code InjectionInfo.postInject} throw for every injector that lands fewer times than its
 * {@code expect} (1 unless written), which the kernel's relaxed {@code require}/{@code defaultRequire} do not govern.
 * The crash this was found on is of the second kind. With lambdynamiclights in a pack and {@code countInjections} on,
 * spruceui's NeoForge {@code GuiMixin} wraps {@code ClientHooks.extractScreen}, a call the merged
 * {@code Gui.extractRenderState} no longer makes; its {@code @WrapOperation} found nothing, the check threw, and
 * {@code Gui} was defined without fabric-screen-api's {@code GuiExtensions}, architectury's {@code MixinGui} or anyone
 * else's: {@code Minecraft.<init>} died on the cast. balm's {@code FabricCropBlockMixin}, whose moisture wrap needs the
 * moisture read in {@code CropBlock.getGrowthSpeed} that neither of the merged base's two overloads still makes, cost
 * {@code CropBlock} every mixin the same way. Those two lost calls are the merged base's, not this class's, and stay
 * open: with or without the switch, isolated or not, those two wraps land nowhere, so spruceui's
 * {@code BEFORE_EXTRACT}/{@code AFTER_EXTRACT} screen events and balm's moisture hook do not fire. Without the switch the
 * packs measured never reached this class at all.
 *
 * <h2>What this does</h2>
 *
 * <p>An extension placed directly in front of MixinExtras' applicator takes the class's queue and runs it in the order
 * the applicator does — each injection type in turn, every {@code lateInject} of that type, then every
 * {@code latePostInject} — with Mixin's own rule around each step: an {@code InvalidMixinException} from a mixin whose
 * config is not required is handed to the class's suppressed list, where Mixin reports it after the class is done as
 * it reports any optional mixin that failed (and {@link KernelMixinErrorHandler} marks the mod), and the queue goes on.
 * The applicator then finds nothing left for the class.
 *
 * <p>Anything else is thrown as it is thrown upstream: a required config's failure, a failure that names no mixin, an
 * {@code Error} such as {@code InjectionError}, any other exception. Thrown the same way, but not always reached the same
 * way: upstream abandons the class at its first failure and never runs the rest of its queue, while here the queue goes
 * on past a failure set aside. So a later late injection whose failure upstream never got to see is now judged on its
 * own, and if it is one upstream would not tolerate either — a required config's, or an {@code InjectionError} from a
 * {@code require}/{@code allow} the kernel left strict (which on relaxed guest mixins takes
 * {@code -Dforbric.mixinDiagnostics} or {@code -Dforbric.requireFailSoft=off}; a config the kernel does not relax keeps
 * its own) — the game stops where upstream, having already given up on the class, warned and left it unwoven. A mod
 * that declared a hard requirement still stops the game; it is no longer hidden behind another mod's softer failure.
 * Whatever leaves the queue after a failure was set aside, Mixin reports only that (it reads a class's suppressed list
 * only when the class completes), so the failures set aside before are said in the log and carried on it as suppressed
 * exceptions; and the line that says a class kept its other mixins is written only once its queue is done.
 *
 * <p>The granularity is the injection, not the whole mixin. By the time late injections run, the rest of the failing
 * mixin — its fields, methods, interfaces and ordinary injectors — is already in the class, as it is when Mixin sets a
 * mixin aside in a later pass. Its other late injections are run too: each was found and queued on its own, and
 * skipping their {@code latePostInject} would leave the call sites they rewrote without the methods
 * ({@code injectedMethods}) they call. For the same reason the failed injection's own {@code latePostInject} still
 * runs, adding what its {@code lateInject} generated before it stopped; a second complaint from it is the same failure
 * and is not reported twice.
 *
 * <p>An injection can stop half-way: an injector that matched two calls (a target without a descriptor, two overloads)
 * rewrites the first and throws at the second. What it rewrote stays: {@code @WrapOperation} and
 * {@code @WrapWithCondition} validate their handler against a call before they touch it, and {@code @WrapOperation} adds
 * the bridge a rewritten call needs to the class in the same step, so each call is either fully wrapped or untouched;
 * the injection's {@code latePostInject} then adds anything it queued. Nothing is rolled back, as Mixin rolls back
 * nothing of an optional mixin it sets aside; and a rollback would not be sound, because the instructions an injection
 * replaced are shared with every other injection queued on them (Mixin's injection nodes), another mod's included.
 * That guarantee is read from the code, not enforced by it, and it does not cover everything: MixinExtras'
 * {@code @ModifyExpressionValue} has one path that inserts an instruction before it validates its handler (a
 * {@code DUP} behind a {@code new} it sees as not yet duplicated). On javac's {@code new} it leaves nothing behind: the
 * weave test puts one whose handler does not fit on the {@code new} inside a conditional, and the method keeps javac's
 * one {@code DUP} and the class is kept. No MixinExtras 0.5.4 injector is known to change code before its check on
 * javac-compiled code; other compilers' code and a later MixinExtras may differ. So before a failure is set aside,
 * every method of the class is analysed (ASM's {@code BasicVerifier}: stack heights at every join, the basic kind of
 * every value — not a full verification: a stray value in straight-line code, or a reference of the wrong class, passes
 * it); if one no longer analyses, the class is handed back unwoven with its first failure, which is exactly upstream's
 * outcome. The analysis runs only after a late injection failed. {@code LateInjectionIsolationWeaveTest} weaves both
 * outcomes and runs them: a wrap that rewrites one of two calls and fails at the other, beside another mod's
 * {@code @ModifyExpressionValue} on the call it rewrote, is kept and does what each injection asked; a class that a
 * stand-in leaves with a stray {@code DUP} inside a conditional when a wrap fails is handed back.
 *
 * <p>Nothing changes for a class whose late injections all succeed: same steps, same order, same bytes. The queue is
 * MixinExtras' private {@code QUEUED_INJECTIONS} and the suppressed list is {@code TargetClassContext}'s
 * package-private {@code addSuppressed}, both read reflectively, as MixinExtras itself reaches into Mixin's extension
 * lists. Where they are not what MixinExtras 0.5.4 and sponge-mixin 0.17.3 have — either is missing, the queue's
 * declared type is not {@code Map<ITargetClassContext, Map<String, List<Runnable[]>>>}, a queued injection is not a
 * pair of steps — the isolation stands down and says so once: at install it is not installed, and a queue entry it does
 * not understand is left where it is, for the applicator to run the upstream way.
 *
 * <p>A guest that bundles a newer, relocated MixinExtras can take over from the kernel's after this is installed:
 * {@code MixinExtrasBootstrap.init} in its config plugin's {@code onLoad} makes the kernel's copy concede, which
 * unregisters the applicator this sits in front of, and the newer copy appends its own applicator with its own queue.
 * The isolation would then hold a queue nothing fills. It checks, whenever Mixin's active extensions change, that the
 * first applicator after it is still the one whose queue it holds, and says once that it is not and that late injections
 * now fail the upstream way. {@code -Dforbric.lateInjectionIsolation=off} does not install it.
 */
public final class LateInjectionIsolation implements IExtension {
	public static final String PROPERTY = "forbric.lateInjectionIsolation";
	/** MixinExtras' extension that applies the queued late injections (MixinExtras 0.5.x). */
	static final String APPLICATOR = "com.llamalad7.mixinextras.injector.LateInjectionApplicatorExtension";
	static final String QUEUE_FIELD = "QUEUED_INJECTIONS";
	/** Said when the isolation finds it no longer sits in front of the applicator whose queue it holds. */
	public static final String STRANDED = "the late-injection isolation no longer sits in front of the MixinExtras applicator "
			+ "whose queue it takes";

	/** One step of a queued injection: {@code lateInject} is index 0, {@code latePostInject} index 1, as MixinExtras queues them. */
	private static final int INJECT = 0;
	private static final int POST_INJECT = 1;
	private static final int STEPS = 2;

	private final Map<?, ?> queue;
	private final Method addSuppressed;
	private final Extensions extensions;
	private final Class<?> applicator;
	/** The active extension list last looked at; looked at again only when Mixin's list is another list. */
	private volatile List<IExtension> seenActive;
	private final AtomicBoolean strandedSaid = new AtomicBoolean();
	private final AtomicBoolean unreadableSaid = new AtomicBoolean();

	private LateInjectionIsolation(Map<?, ?> queue, Method addSuppressed, Extensions extensions, Class<?> applicator) {
		this.queue = queue;
		this.addSuppressed = addSuppressed;
		this.extensions = extensions;
		this.applicator = applicator;
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/**
	 * Puts the isolation directly in front of MixinExtras' late applicator in {@code extensions}, both in the registered
	 * list and in the active one Mixin iterates (MixinExtras registers into both). Returns whether it was installed; when
	 * it was not, neither list was changed. Called once MixinExtras has registered its extensions and before the first
	 * class is woven.
	 */
	@SuppressWarnings("unchecked")
	static boolean install(Extensions extensions, ClassLoader gameLoader) {
		if (!enabled() || extensions == null) return false;
		Class<?> applicator;
		try {
			applicator = Class.forName(APPLICATOR, false, gameLoader);
		} catch (ClassNotFoundException absent) {
			// No MixinExtras: no late injectors, nothing to isolate.
			Caught.expected(absent);
			return false;
		}
		try {
			Field queueField = applicator.getDeclaredField(QUEUE_FIELD);
			if (!Modifier.isStatic(queueField.getModifiers()) || !queueShapeUnderstood(queueField.getGenericType())) {
				ForbricLog.warn("[Forbric/Mixin] MixinExtras' %s.%s is declared %s, not the static Map<ITargetClassContext, "
						+ "Map<String, List<Runnable[]>>> the late-injection isolation reads (MixinExtras 0.5.4) — not "
						+ "installed: a late injector that fails costs its target class every mixin, as upstream",
						applicator.getName(), QUEUE_FIELD, queueField.getGenericType().getTypeName());
				return false;
			}
			queueField.setAccessible(true);
			if (!(queueField.get(null) instanceof Map<?, ?> queue)) {
				ForbricLog.warn("[Forbric/Mixin] MixinExtras' %s.%s holds no map — the late-injection isolation is not "
						+ "installed: a late injector that fails costs its target class every mixin, as upstream",
						applicator.getName(), QUEUE_FIELD);
				return false;
			}
			Method addSuppressed = Class.forName("org.spongepowered.asm.mixin.transformer.TargetClassContext")
					.getDeclaredMethod("addSuppressed", InvalidMixinException.class);
			addSuppressed.setAccessible(true);

			// Everything read before anything is changed, so a lookup that fails leaves both lists as they were and the
			// warning below true.
			Field registeredField = Extensions.class.getDeclaredField("extensions");
			registeredField.setAccessible(true);
			Field activeField = Extensions.class.getDeclaredField("activeExtensions");
			activeField.setAccessible(true);
			List<IExtension> registered = (List<IExtension>) registeredField.get(extensions);
			List<IExtension> active = new ArrayList<>((List<IExtension>) activeField.get(extensions));
			int at = indexOf(registered, applicator);
			if (at < 0) {
				ForbricLog.warn("[Forbric/Mixin] MixinExtras' late applicator %s is not among Mixin's extensions, so the "
						+ "late-injection isolation is not installed — whichever MixinExtras applies late injections does it "
						+ "the upstream way, and one that fails costs its target class every mixin", applicator.getName());
				return false;
			}
			int activeAt = indexOf(active, applicator);

			LateInjectionIsolation isolation = new LateInjectionIsolation(queue, addSuppressed, extensions, applicator);
			registered.add(at, isolation);
			if (activeAt >= 0) {
				active.add(activeAt, isolation);
				try {
					activeField.set(extensions, Collections.unmodifiableList(active));
				} catch (IllegalAccessException | RuntimeException notSet) {
					// Not installed after all: take it back out, so the warning below is true.
					registered.remove(isolation);
					throw notSet;
				}
			}
			ForbricLog.info("[Forbric/Mixin] MixinExtras' late injectors fail one injection at a time: one that fails in an "
					+ "optional mixin is reported like any other optional mixin, and its target class keeps every other "
					+ "mixin (-D%s=off for upstream, where the class loses them all)", PROPERTY);
			return true;
		} catch (ReflectiveOperationException | RuntimeException | GenericSignatureFormatError shapeChanged) {
			// GenericSignatureFormatError: getGenericType on a queue field whose Signature attribute does not parse. It is an
			// Error, but only of this one field's metadata, so it is answered here like any other unreadable shape.
			Caught.warn(shapeChanged, "[Forbric/Mixin] could not put the late-injection isolation in front of MixinExtras' "
					+ "applicator (its queue or Mixin's suppressed list or extension lists are not where MixinExtras 0.5.4 / "
					+ "sponge-mixin 0.17.3 keep them) — not installed: a late injector that fails costs its target class "
					+ "every mixin, as upstream");
			return false;
		}
	}

	/** {@code Map<ITargetClassContext, Map<String, List<Runnable[]>>>}, the queue's declared type in MixinExtras 0.5.x. */
	static boolean queueShapeUnderstood(Type declared) {
		return mapOf(declared, LateInjectionIsolation::isTargetContext,
				byType -> mapOf(byType, String.class::equals, LateInjectionIsolation::isListOfStepPairs));
	}

	private static boolean mapOf(Type type, java.util.function.Predicate<Type> key, java.util.function.Predicate<Type> value) {
		return type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> raw && Map.class.isAssignableFrom(raw)
				&& p.getActualTypeArguments().length == 2 && key.test(p.getActualTypeArguments()[0])
				&& value.test(p.getActualTypeArguments()[1]);
	}

	private static boolean isTargetContext(Type type) {
		return type instanceof Class<?> c && c.getName().equals(ITargetClassContext.class.getName());
	}

	private static boolean isListOfStepPairs(Type type) {
		if (!(type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> raw && List.class.isAssignableFrom(raw)
				&& p.getActualTypeArguments().length == 1)) {
			return false;
		}
		Type element = p.getActualTypeArguments()[0];
		return element == Runnable[].class
				|| element instanceof GenericArrayType array && array.getGenericComponentType() == Runnable.class;
	}

	private static int indexOf(List<IExtension> extensions, Class<?> type) {
		for (int i = 0; i < extensions.size(); i++) {
			if (type.isInstance(extensions.get(i))) return i;
		}
		return -1;
	}

	@Override
	public boolean checkActive(MixinEnvironment environment) {
		return true;
	}

	@Override
	public void preApply(ITargetClassContext context) {
	}

	@Override
	public void postApply(ITargetClassContext context) {
		checkStillInFront();
		Object entry = queue.get(context);
		if (entry == null) return;
		List<List<Runnable[]>> relevant = understood(entry);
		if (relevant == null) {
			if (unreadableSaid.compareAndSet(false, true)) {
				ForbricLog.warn("[Forbric/Mixin] a MixinExtras late-injection queue entry is not the lists of step pairs "
						+ "the late-injection isolation reads (MixinExtras 0.5.4): %s — such entries are left to MixinExtras' "
						+ "applicator, where a late injector that fails costs its target class every mixin, as upstream",
						entry.getClass().getName());
			}
			return;
		}
		queue.remove(context);
		List<InvalidMixinException> setAside = new ArrayList<>();
		try {
			for (List<Runnable[]> injections : relevant) {
				boolean[] failed = new boolean[injections.size()];
				for (int i = 0; i < injections.size(); i++) {
					failed[i] = !step(injections.get(i)[INJECT], context, setAside, false);
				}
				for (int i = 0; i < injections.size(); i++) {
					step(injections.get(i)[POST_INJECT], context, setAside, failed[i]);
				}
			}
		} catch (Throwable stopped) {
			// The class is handed back unwoven after all, and Mixin reports only what leaves applyMixins: its suppressed
			// list is read only when the class completes. What was set aside before goes with the class, said here and
			// carried on the exception that is reported.
			lostWithTheClass(setAside, stopped, context);
			throw stopped;
		}
		// Said only now that the class is kept: until the queue is done a later failure can still hand it back.
		ClassNode target = context.getClassNode();
		for (InvalidMixinException failure : setAside) {
			IMixinInfo mixin = failure.getMixin();
			ForbricLog.info("[Forbric/Mixin] %s:%s — one late injection failed (%s); only it is lost, and %s keeps every "
					+ "other mixin applied to it (upstream MixinExtras would hand back the class unwoven)",
					MixinConfigOwners.describe(mixin.getConfig().getName()), mixin.getClassName(),
					failure.getClass().getSimpleName(), target == null ? "the class" : target.name.replace('/', '.'));
		}
	}

	/**
	 * The entry as lists of {@code {lateInject, latePostInject}} pairs, in the map's order; null when anything in it is
	 * not that, so that a MixinExtras that queues something else gets its own applicator's handling instead of a cast
	 * failing here outside every catch.
	 */
	private static List<List<Runnable[]>> understood(Object entry) {
		if (!(entry instanceof Map<?, ?> byType)) return null;
		List<List<Runnable[]>> lists = new ArrayList<>(byType.size());
		for (Object injections : byType.values()) {
			if (!(injections instanceof List<?> list)) return null;
			List<Runnable[]> steps = new ArrayList<>(list.size());
			for (Object injection : list) {
				if (!(injection instanceof Runnable[] pair) || pair.length != STEPS || pair[INJECT] == null
						|| pair[POST_INJECT] == null) {
					return null;
				}
				steps.add(pair);
			}
			lists.add(steps);
		}
		return lists;
	}

	/**
	 * Whether the first late applicator after this one in Mixin's active extensions is still the one whose queue this
	 * holds; says once when it is not. Looked at again only when Mixin's active list is a different list, which is
	 * what both Mixin's {@code select} and MixinExtras' (un)registration leave behind.
	 */
	private void checkStillInFront() {
		List<IExtension> active = extensions.getActiveExtensions();
		if (seenActive == active) return;
		int me = -1;
		int queued = -1;
		IExtension replacement = null;
		for (int i = 0; i < active.size(); i++) {
			IExtension one = active.get(i);
			if (one == this) {
				me = i;
			} else if (applicator.isInstance(one)) {
				if (queued < 0) queued = i;
			} else if (replacement == null && one.getClass().getSimpleName().equals(applicator.getSimpleName())) {
				replacement = one;
			}
		}
		seenActive = active;
		if (!(me >= 0 && queued > me) && strandedSaid.compareAndSet(false, true)) {
			ForbricLog.warn("[Forbric/Mixin] %s (%s; %s) — from here on a late injector that fails in an optional mixin costs "
					+ "its target class every mixin, as upstream", STRANDED, applicator.getName(),
					replacement != null
							? replacement.getClass().getName() + " is active instead: a newer MixinExtras took over after "
									+ "the kernel installed the isolation"
							: queued < 0 ? "it is no longer active" : "it now runs before the isolation");
		}
	}

	/**
	 * Runs one step; false when it failed in a way Mixin sets aside for an optional mixin, which it then has been.
	 * {@code alreadyFailed}: this injection's {@code lateInject} failed and was set aside, so this step's complaint is the
	 * same failure and is neither checked nor reported again.
	 */
	private boolean step(Runnable step, ITargetClassContext context, List<InvalidMixinException> setAside,
			boolean alreadyFailed) {
		try {
			step.run();
			return true;
		} catch (InvalidMixinException failure) {
			IMixinConfig optional = optionalConfig(failure);
			// A required config's failure, or one that names no mixin: not Mixin's to set aside either, upstream's answer.
			if (optional == null) throw failure;
			// Set aside only while what the failed injection left behind still analyses; otherwise the class is handed back
			// unwoven with its first failure, which is upstream's outcome.
			String broken = alreadyFailed ? null : unsound(context.getClassNode());
			if (broken != null) throw handBack(failure, setAside, broken, context);
			// Set aside, as MixinApplicatorStandard sets aside an optional mixin's failure: the answer, handed on to Mixin's
			// own report rather than lost.
			Caught.expected(failure);
			if (!alreadyFailed) setAside(failure, context, setAside);
			return false;
		}
	}

	/** The failure's config when Mixin would set it aside (its mixin is named and the config is not required), else null. */
	private static IMixinConfig optionalConfig(InvalidMixinException failure) {
		IMixinInfo mixin = failure.getMixin();
		IMixinConfig config = mixin == null ? null : mixin.getConfig();
		return config == null || config.isRequired() ? null : config;
	}

	private void setAside(InvalidMixinException failure, ITargetClassContext context, List<InvalidMixinException> setAside) {
		setAside.add(failure);
		try {
			addSuppressed.invoke(context, failure);
		} catch (ReflectiveOperationException | RuntimeException unreachable) {
			Caught.warn(unreachable, "[Forbric/Mixin] could not hand %s's late-injection failure to Mixin's report; it is "
					+ "logged here instead: %s", failure.getMixin().getClassName(), failure.getMessage());
		}
	}

	/**
	 * What to throw when {@code failure} left the class in a state that does not analyse: the class's first failure, the
	 * one upstream would have thrown and stopped at, carrying {@code failure} when it is a later one.
	 */
	private static InvalidMixinException handBack(InvalidMixinException failure, List<InvalidMixinException> setAside,
			String broken, ITargetClassContext context) {
		InvalidMixinException first = setAside.isEmpty() ? failure : setAside.get(0);
		if (first != failure) first.addSuppressed(failure);
		ClassNode target = context.getClassNode();
		ForbricLog.warn("[Forbric/Mixin] %s is handed back unwoven, as upstream MixinExtras does: after %s's late injection "
				+ "failed (%s), %s no longer analyses, and a class is not kept half-woven in a state that may not verify",
				target == null ? "the class" : target.name.replace('/', '.'), failure.getMixin().getClassName(),
				failure.getClass().getSimpleName(), broken);
		return first;
	}

	/** Says, and attaches to {@code stopped}, each failure set aside before the class was handed back after all. */
	private static void lostWithTheClass(List<InvalidMixinException> setAside, Throwable stopped, ITargetClassContext context) {
		ClassNode target = context.getClassNode();
		for (InvalidMixinException failure : setAside) {
			if (failure == stopped) continue;
			stopped.addSuppressed(failure);
			ForbricLog.warn("[Forbric/Mixin] %s's late injection failed (%s) and was set aside, but %s is handed back unwoven "
					+ "after all (%s); Mixin reports only that", failure.getMixin().getClassName(),
					failure.getClass().getSimpleName(), target == null ? "the class" : target.name.replace('/', '.'), stopped);
		}
	}

	/** Generous room in a method's frames: an injector may grow its stack and locals before Mixin writes the new maxima. */
	private static final int SLACK = 64;

	/**
	 * The first method of {@code node} that does not analyse, with why; null when every one does. ASM's
	 * {@link BasicVerifier} over each method: stack heights at every join, and the basic kind of every value, which is
	 * what an instruction an injector inserted before its own check failed (a {@code DUP} with nothing to consume it)
	 * breaks. Run only after a late injection failed.
	 */
	static String unsound(ClassNode node) {
		if (node == null) return null;
		for (MethodNode method : node.methods) {
			if (method.instructions == null || method.instructions.size() == 0) continue;
			MethodNode view = new MethodNode(method.access, method.name, method.desc, method.signature, null);
			view.instructions = method.instructions;
			view.tryCatchBlocks = method.tryCatchBlocks;
			view.maxLocals = method.maxLocals + SLACK;
			view.maxStack = method.maxStack + SLACK;
			try {
				new Analyzer<>(new BasicVerifier()).analyze(node.name, view);
			} catch (AnalyzerException | RuntimeException doesNotAnalyse) {
				// The answer: this method does not analyse (ASM wraps what its frames throw; anything else it threw is read
				// the same way, so the class goes back unwoven rather than kept on a guess).
				Caught.expected(doesNotAnalyse);
				return method.name + method.desc + " (" + doesNotAnalyse.getMessage() + ")";
			}
		}
		return null;
	}

	@Override
	public void export(MixinEnvironment env, String name, boolean force, ClassNode classNode) {
	}
}
