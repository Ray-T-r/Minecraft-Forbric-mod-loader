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

package net.forbric.kernel.boot;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.kernel.util.ForbricLog;

/**
 * NeoForge's {@code RegistrationEvents.init()}, run one step at a time.
 *
 * <p>That method is seven no-argument static calls — cauldron fluid content, cauldron interactions,
 * {@code CapabilityHooks.init} (posts {@code RegisterCapabilitiesEvent}), forced chunks,
 * {@code RegistryManager.initDataMaps} (posts {@code RegisterDataMapTypesEvent}), data component modifiers and POI
 * extension. The kernel called it whole, so the first step that threw ended it: on the sweep pack's client one data
 * map listener died on a poisoned {@code DataPackRegistriesHooks}, and with it went every data map type (NeoForge's
 * eleven built-ins included), {@code ModifyDefaultComponentsEvent}, {@code ExtendPoiTypesEvent} and — because the
 * kernel's transfer bridge sat after the call in the same {@code try} — the cross-ecosystem item/fluid/energy
 * bridge. Native NeoForge is all-or-nothing too, but loudly: a failure there is a loading-error screen. The kernel
 * carries on, so it owes the steps that did not fail their turn.
 *
 * <p>Still NeoForge's own method, not a copy: the step list is read from its bytecode at run time, so a NeoForge
 * that adds a step gets it run, and one whose {@code init} is anything but a straight run of such calls — or whose
 * loaded class a transformer has changed — is called whole, as before. Order is the method's own. A failed step is
 * never fed back into {@code ModLoader} as a loading issue: {@code ModLoader.postEvent} refuses every later event
 * once one is recorded ("Cowardly refusing to send event"), which would silently undo the isolation.
 * {@code -Dforbric.isolatedRegistrationEvents=off} calls the method whole.
 */
final class RegistrationEventSteps {
	static final String SWITCH = "forbric.isolatedRegistrationEvents";
	static final String EVENTS = "net.neoforged.neoforge.internal.RegistrationEvents";
	static final Step CAPABILITIES = new Step("net/neoforged/neoforge/capabilities/CapabilityHooks", "init");
	static final Step DATA_MAPS = new Step("net/neoforged/neoforge/registries/RegistryManager", "initDataMaps");

	private RegistrationEventSteps() {
	}

	/** One static call inside {@code init}, by internal owner name and method name. */
	record Step(String owner, String name) {
		String display() {
			return owner.substring(owner.lastIndexOf('/') + 1) + "." + name;
		}
	}

	/**
	 * What one run did. {@code steps} is empty when {@code init} was called whole, and then {@code wholeFailure}
	 * is the only failure there is.
	 */
	record Outcome(boolean isolated, List<Step> steps, Map<Step, Throwable> failures, Throwable wholeFailure,
			int dataMapTypes) {
		boolean clean() {
			return failures.isEmpty() && wholeFailure == null;
		}

		/**
		 * Whether the capability step ran to completion, which is what the transfer bridge waits for: it is
		 * installed "after native capability registration". Called whole, only a clean call says so.
		 */
		boolean capabilitiesRegistered() {
			if (!isolated) return wholeFailure == null;
			return !steps.contains(CAPABILITIES) || !failures.containsKey(CAPABILITIES);
		}

		/**
		 * The data-map step's own failure. Called whole there is no telling which step a failure came from, so this
		 * is null and the data-map finding rests on the count alone.
		 */
		Throwable dataMapsFailure() {
			return isolated ? failures.get(DATA_MAPS) : null;
		}

		/**
		 * Whether data maps were registered: a count of zero after the step ran. NeoForge registers eleven types of
		 * its own, so an empty set means the event reached nobody — as much a loss as a throw. Unknown (no
		 * {@code RegistryManager} to ask) is not evidence either way.
		 */
		boolean dataMapsEmpty() {
			boolean ran = !isolated || steps.contains(DATA_MAPS);
			return ran && dataMapTypes == 0;
		}
	}

	/**
	 * The steps of {@code init} in {@code classBytes}, or null when its body is anything other than no-argument
	 * {@code INVOKESTATIC ... ()V} calls followed by one {@code RETURN}: a branch, a local, a handler, an argument —
	 * anything whose meaning is not just "call these in order" — and the method is not ours to take apart.
	 */
	static List<Step> plan(byte[] classBytes) {
		if (classBytes == null) return null;
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		MethodNode init = null;
		for (MethodNode m : node.methods) {
			if ("init".equals(m.name) && "()V".equals(m.desc) && (m.access & Opcodes.ACC_STATIC) != 0) init = m;
		}
		if (init == null || !init.tryCatchBlocks.isEmpty()) return null;

		List<Step> steps = new ArrayList<>();
		boolean returned = false;
		for (AbstractInsnNode insn : init.instructions.toArray()) {
			if (insn.getOpcode() < 0) continue; // labels only; debug and frames were skipped
			if (returned) return null;
			if (insn.getOpcode() == Opcodes.RETURN) {
				returned = true;
			} else if (insn.getOpcode() == Opcodes.INVOKESTATIC && insn instanceof MethodInsnNode call
					&& "()V".equals(call.desc) && !call.itf) {
				steps.add(new Step(call.owner, call.name));
			} else {
				return null;
			}
		}
		return returned && !steps.isEmpty() ? List.copyOf(steps) : null;
	}

	/** The method names {@code classBytes} declares, constructors and initialisers excluded. */
	static Set<String> declaredMethodNames(byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, ClassReader.SKIP_CODE);
		Set<String> names = new HashSet<>();
		for (MethodNode m : node.methods) {
			if (!m.name.startsWith("<")) names.add(m.name);
		}
		return names;
	}

	/**
	 * Runs {@code RegistrationEvents.init} from {@code cl}, one step at a time when its shape allows, and reports
	 * what failed. Null when there is no such method to run (a carrier without it).
	 */
	static Outcome fire(ClassLoader cl) {
		Class<?> events;
		Method init;
		try {
			events = Class.forName(EVENTS, false, cl);
			init = events.getDeclaredMethod("init");
			init.setAccessible(true);
		} catch (ClassNotFoundException | NoSuchMethodException absent) {
			ForbricLog.debug("[Forbric/Lifecycle] no RegistrationEvents.init to run: %s", String.valueOf(absent));
			return null;
		}

		List<Step> steps = "off".equalsIgnoreCase(System.getProperty(SWITCH, "on")) ? null : planFor(events, cl);
		Outcome outcome;
		if (steps == null) {
			Throwable failure = null;
			try {
				init.invoke(null);
			} catch (Throwable t) {
				failure = unwrap(t);
			}
			outcome = new Outcome(false, List.of(), Map.of(), failure, dataMapTypes(cl));
		} else {
			Map<Step, Throwable> failures = new LinkedHashMap<>();
			for (Step step : steps) {
				try {
					resolve(step, cl).invoke(null);
				} catch (Throwable t) {
					failures.put(step, unwrap(t));
				}
			}
			outcome = new Outcome(true, steps, failures, null, dataMapTypes(cl));
		}
		report(outcome);
		return outcome;
	}

	/**
	 * The isolated plan for the class as it is LOADED: the file's steps, unless the loaded class declares a method
	 * the file does not. That is what a Mixin injector into {@code init} leaves behind (its merged handler), and
	 * replaying the file's steps would silently skip it.
	 */
	private static List<Step> planFor(Class<?> events, ClassLoader cl) {
		byte[] bytes;
		try (InputStream in = cl.getResourceAsStream(EVENTS.replace('.', '/') + ".class")) {
			bytes = in == null ? null : in.readAllBytes();
		} catch (Exception unreadable) {
			bytes = null;
		}
		List<Step> steps;
		try {
			steps = plan(bytes);
		} catch (RuntimeException unparsable) {
			steps = null;
		}
		if (steps == null) {
			ForbricLog.info("[Forbric/Lifecycle] NeoForge's RegistrationEvents.init is not a straight run of static "
					+ "calls here — calling it whole, so one failing step still ends it");
			return null;
		}
		Set<String> fileMethods = declaredMethodNames(bytes);
		try {
			for (Method m : events.getDeclaredMethods()) {
				if (!fileMethods.contains(m.getName())) {
					ForbricLog.info("[Forbric/Lifecycle] RegistrationEvents was transformed after it left NeoForge's "
							+ "jar (it declares %s) — calling init whole rather than replaying the file's steps",
							m.getName());
					return null;
				}
			}
		} catch (Throwable unlinkable) {
			return null;
		}
		return steps;
	}

	/** The method an {@code INVOKESTATIC owner.name()V} resolves to: declared on the owner or a superclass. */
	private static Method resolve(Step step, ClassLoader cl) throws ReflectiveOperationException {
		Class<?> owner = Class.forName(step.owner().replace('/', '.'), false, cl);
		for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
			try {
				Method m = c.getDeclaredMethod(step.name());
				m.setAccessible(true);
				return m;
			} catch (NoSuchMethodException notHere) {
				// keep walking, as the JVM's own resolution does
			}
		}
		throw new NoSuchMethodError(step.owner() + "." + step.name() + "()V");
	}

	/** Every data map type NeoForge registered, or -1 when there is no {@code RegistryManager} to ask. */
	private static int dataMapTypes(ClassLoader cl) {
		try {
			Object maps = Class.forName(DATA_MAPS.owner().replace('/', '.'), false, cl)
					.getMethod("getDataMaps").invoke(null);
			if (!(maps instanceof Map<?, ?> byRegistry)) return -1;
			int n = 0;
			for (Object perRegistry : byRegistry.values()) {
				if (perRegistry instanceof Map<?, ?> types) n += types.size();
			}
			return n;
		} catch (Throwable unknown) {
			return -1;
		}
	}

	private static void report(Outcome outcome) {
		for (Map.Entry<Step, Throwable> failed : outcome.failures().entrySet()) {
			ForbricLog.warn("[Forbric/Lifecycle] NeoForge's registration step " + failed.getKey().display()
					+ " failed — the other steps still ran", failed.getValue());
		}
		if (!outcome.isolated() && outcome.wholeFailure() != null) {
			ForbricLog.warn("[Forbric/Lifecycle] NeoForge's registration events failed — capabilities and data "
					+ "maps will be missing", outcome.wholeFailure());
			CompatibilityFindings.record(new CompatibilityFinding("neoforge-registration-events", "forbric",
					"NeoForge built-in registration", "RegistrationEvents.init", CompatibilityFinding.Confidence.CONFIRMED,
					true, "NeoForge's registration events failed part-way: capabilities, data maps, default item "
							+ "component changes and mod villager workstations may be missing",
					List.of(String.valueOf(outcome.wholeFailure()))));
		}
		for (Map.Entry<Step, Throwable> failed : outcome.failures().entrySet()) {
			if (failed.getKey().equals(DATA_MAPS)) continue; // its own finding below
			CompatibilityFindings.record(new CompatibilityFinding("neoforge-registration:" + failed.getKey().display(),
					"forbric", "NeoForge built-in registration", "RegistrationEvents.init",
					CompatibilityFinding.Confidence.CONFIRMED, true,
					"NeoForge's " + failed.getKey().display() + " failed; native NeoForge stops loading here, "
							+ "Forbric ran the remaining registration steps without it",
					List.of(String.valueOf(failed.getValue()))));
		}

		// Data maps get a finding whether they failed loudly or came back empty, and not on the WARN alone: the
		// sweep pack's client lost every one of them with nothing but a log line to show for it.
		Throwable dataMaps = outcome.dataMapsFailure();
		if (dataMaps != null || outcome.dataMapsEmpty()) {
			String cause = dataMaps != null ? String.valueOf(dataMaps)
					: outcome.wholeFailure() != null ? String.valueOf(outcome.wholeFailure())
					: "no data map type was registered";
			CompatibilityFindings.record(new CompatibilityFinding("neoforge-data-maps", "forbric", "NeoForge data maps",
					"RegistryManager.initDataMaps", CompatibilityFinding.Confidence.CONFIRMED, true,
					"NeoForge data maps unavailable: " + cause + " — compostables, furnace fuels, waxables, "
							+ "strippables and every mod's data map read as empty",
					List.of(cause, "data map types=" + outcome.dataMapTypes())));
		}

		String types = outcome.dataMapTypes() < 0 ? "" : ", " + outcome.dataMapTypes() + " data map type(s)";
		if (outcome.clean()) {
			ForbricLog.info("[Forbric/Lifecycle] ran NeoForge's registration events%s — capabilities and data maps "
					+ "are registered, and its cauldron/forced-chunk/data-component/POI built-ins initialised%s",
					outcome.isolated() ? " (" + outcome.steps().size() + " step(s), each on its own)" : "", types);
		} else if (outcome.isolated()) {
			// Worded so it cannot be read as the clean line above: gates m7/m9 and compat/assert.sh take "ran
			// NeoForge's registration events" as proof that they all ran.
			List<String> failed = new ArrayList<>();
			for (Step s : outcome.failures().keySet()) failed.add(s.display());
			ForbricLog.info("[Forbric/Lifecycle] NeoForge's registration events were run one step at a time — %d "
					+ "of %d clean, %s failed%s", outcome.steps().size() - failed.size(), outcome.steps().size(),
					failed, types);
		}
	}

	private static Throwable unwrap(Throwable t) {
		return t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
	}
}
