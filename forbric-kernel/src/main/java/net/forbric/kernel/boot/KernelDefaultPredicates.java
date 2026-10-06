/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.Type;
import net.forbric.kernel.boot.DefinedMethodContracts.MethodContract;

/** A native virtual override takes precedence over transport of a modifier of the proved default predicate. */
public final class KernelDefaultPredicates {
	@FunctionalInterface public interface PredicateModifier { boolean modify(boolean original, Object operand); }
	public record ImmutableField(String owner, String name, String descriptor) { }
	public record Contract(MethodContract entry, MethodContract getter, MethodContract fallback,
			List<MethodContract> identityHelpers, ImmutableField projectionField, String firstType, String secondType) {
		public Contract { identityHelpers = List.copyOf(identityHelpers); }
	}
	private static final Map<String, Contract> CONTRACTS = new ConcurrentHashMap<>();
	private KernelDefaultPredicates() { }

	public static String register(Contract contract) {
		try {
			String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(contract.toString().getBytes(StandardCharsets.UTF_8)));
			CONTRACTS.putIfAbsent(key, contract); return key;
		} catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
	}

	/** The outer wrapper already executed native exactly once. Only an unchanged, fully witnessed default chain
	 * reaches the modifier; unknown definitions, overridden dispatch and mutable captures keep native's result. */
	public static boolean modifyDefault(boolean nativeResult, Object state, String key, PredicateModifier modifier) {
		Contract contract = CONTRACTS.get(key);
		if (contract == null || state == null || modifier == null || !DefinedMethodContracts.validates(state, contract.entry())
				|| !DefinedMethodContracts.validates(state, contract.getter())) return nativeResult;
		Object operand; ClassLoader predicateLoader; boolean first;
		try {
			ClassLoader source = state.getClass().getClassLoader();
			for (MethodContract helper : contract.identityHelpers()) {
				Class<?> owner = Class.forName(binary(helper.owner()), false, source);
				if (!DefinedMethodContracts.observed(owner.getClassLoader(), helper)) return nativeResult;
			}
			ImmutableField projection = contract.projectionField();
			Class<?> fieldOwner = Class.forName(binary(projection.owner()), false, source);
			Field field = fieldOwner.getDeclaredField(projection.name());
			if (!Modifier.isFinal(field.getModifiers()) || Modifier.isStatic(field.getModifiers())
					|| !Type.getDescriptor(field.getType()).equals(projection.descriptor())) return nativeResult;
			Method getter = exact(state.getClass(), contract.getter());
			if (getter == null) return nativeResult;
			operand = getter.invoke(state);
			if (operand == null || !DefinedMethodContracts.validates(operand, contract.fallback())) return nativeResult;
			Class<?> fallbackOwner = Class.forName(binary(contract.fallback().owner()), false, operand.getClass().getClassLoader());
			predicateLoader = fallbackOwner.getClassLoader();
			Class<?> firstType = Class.forName(binary(contract.firstType()), false, predicateLoader);
			first = firstType.isInstance(operand);
			// Before the guest callback, verify that the observed native result really is this default expression.
			// Loading/evaluating the second type remains short-circuited exactly as in the original OR.
			boolean expected = first || Class.forName(binary(contract.secondType()), false, predicateLoader).isInstance(operand);
			if (expected != nativeResult) return nativeResult;
		} catch (ReflectiveOperationException | LinkageError unproved) {
			return nativeResult;
		}
		// The guest body is preserved, including its exceptions; guard failure is not a catch-all around it.
		boolean changed = modifier.modify(first, operand);
		if (changed) return true;
		try { return Class.forName(binary(contract.secondType()), false, predicateLoader).isInstance(operand); }
		catch (ClassNotFoundException impossible) { throw new IllegalStateException("A proved predicate fallback type is unavailable: " + contract.secondType(), impossible); }
	}

	private static Method exact(Class<?> type, MethodContract contract) {
		for (Method method : type.getMethods()) if (method.getName().equals(contract.name())
				&& Type.getMethodDescriptor(method).equals(contract.descriptor())) return method;
		return null;
	}
	private static String binary(String owner) { return owner.replace('/', '.'); }
	public static void resetForTests() { CONTRACTS.clear(); }
}
