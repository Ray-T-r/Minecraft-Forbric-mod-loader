/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which implementation a virtual call selects for a receiver: whether it is still the one a given class or interface
 * declares, or one a subtype put in front of it.
 *
 * <p>Asked by the redirect handlers {@code CarrierDefaultChains} writes. A carrier routed a vanilla call through a method of
 * its own whose inherited implementation makes that very call; the moved redirect runs the inherited implementation with the
 * mod's handler in place of the vanilla call — but only for a receiver that still inherits it. A receiver whose class (a
 * carrier mod's block, sound, item) overrides the method gets its override, untouched, as without the mod.
 *
 * <p>The answer is the JVM's selection: the first class from the receiver's up that declares a concrete method of that name
 * and descriptor, else the one maximally specific interface default. A class whose reflective members cannot be read (a
 * signature naming a class that is absent) answers "overridden": the receiver then gets the carrier's own call, which is
 * what the game would have done without the mod.
 */
public final class KernelDispatch {
	private static final ClassValue<Map<String, Boolean>> ANSWERS = new ClassValue<>() {
		@Override
		protected Map<String, Boolean> computeValue(Class<?> type) {
			return new ConcurrentHashMap<>();
		}
	};

	private KernelDispatch() {
	}

	/**
	 * Whether {@code receiver}'s class selects the implementation {@code declarer} (a binary class name) declares for
	 * {@code name} and the JVM {@code descriptor}.
	 */
	public static boolean inherits(Object receiver, String declarer, String name, String descriptor) {
		Class<?> type = receiver.getClass();
		return ANSWERS.get(type).computeIfAbsent(declarer + '#' + name + descriptor, key -> selects(type, declarer, name, descriptor));
	}

	static boolean selects(Class<?> type, String declarer, String name, String descriptor) {
		try {
			for (Class<?> c = type; c != null; c = c.getSuperclass()) {
				if (c.getName().equals(declarer)) return true;
				for (Method method : c.getDeclaredMethods()) {
					if (!matches(method, name, descriptor) || Modifier.isStatic(method.getModifiers()) || Modifier.isPrivate(method.getModifiers())) continue;
					// A concrete one below the declarer is what the JVM selects; an abstract one re-declares it, and the class that
					// implements it again is below — both mean the declarer's is not the one run.
					return false;
				}
			}
			// No class declares it: the selected method is the maximally specific interface default.
			Set<Class<?>> candidates = new LinkedHashSet<>();
			Set<Class<?>> seen = new HashSet<>();
			Deque<Class<?>> pending = new ArrayDeque<>();
			for (Class<?> c = type; c != null; c = c.getSuperclass()) for (Class<?> each : c.getInterfaces()) pending.add(each);
			while (!pending.isEmpty()) {
				Class<?> each = pending.poll();
				if (!seen.add(each)) continue;
				for (Method method : each.getDeclaredMethods())
					if (matches(method, name, descriptor) && !Modifier.isStatic(method.getModifiers()) && !Modifier.isPrivate(method.getModifiers())) {
						candidates.add(each);
						break;
					}
				for (Class<?> parent : each.getInterfaces()) pending.add(parent);
			}
			candidates.removeIf(candidate -> candidates.stream().anyMatch(other -> other != candidate && candidate.isAssignableFrom(other)));
			return candidates.size() == 1 && candidates.iterator().next().getName().equals(declarer)
					&& !Modifier.isAbstract(declared(candidates.iterator().next(), name, descriptor).getModifiers());
		} catch (LinkageError | SecurityException unreadable) {
			return false;
		}
	}

	private static Method declared(Class<?> owner, String name, String descriptor) {
		for (Method method : owner.getDeclaredMethods()) if (matches(method, name, descriptor)) return method;
		throw new IllegalStateException(owner + " declares no " + name + descriptor);
	}

	private static boolean matches(Method method, String name, String descriptor) {
		return method.getName().equals(name) && MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString().equals(descriptor);
	}
}
