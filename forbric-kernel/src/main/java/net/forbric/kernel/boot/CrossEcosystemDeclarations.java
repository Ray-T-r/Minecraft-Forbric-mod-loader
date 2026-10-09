/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlFormat;

import net.fabricmc.loader.api.metadata.CustomValue;
import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.fabric.KernelModMetadata.EntrypointDecl;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.ForbricSwitches;

/**
 * One mod's declarations to OTHER mods, spelled the way the other family's readers look for them.
 *
 * <h2>The broken link</h2>
 *
 * <p>Both families give a mod one free-form place to tell other mods something: a Fabric mod's {@code custom} block
 * (read through {@code ModMetadata.getCustomValue}) and its {@code entrypoints}, a Forge-family mod's
 * {@code [modproperties.<id>]} table (read through {@code IModInfo.getModProperties()}). A library built for one
 * family finds its users by walking its own loader's mod list and reading that place. Here the walk can only reach its
 * own family: a NeoForge library that enumerates {@code ModList} reading {@code getModProperties()} never meets a
 * Fabric mod, because no Fabric mod is in {@code ModList} and none of them has a {@code [modproperties]} table. The
 * integration does not fail, it simply never exists, with nothing in the log.
 *
 * <p>Measured on the packs this kernel is tested with, the keys really are shared: {@code sodium:config_api_user}
 * names the SAME class in seven mods that ship both builds (Fabric entrypoint, NeoForge property — Gamma Utils, Iris,
 * LambDynamicLights, MoreCulling, Reese's Sodium Options, Sodium Extra, Shadowy Path Blocks), and
 * {@code fabric-renderer-api-v1:contains_renderer}, {@code modupdater} and {@code rrls} are written both as Fabric
 * custom values and as NeoForge properties. Readers that walk a NeoForge list for such a key: Sodium (config users),
 * Jade ({@code jade}), LibJF ({@code libjf}, {@code libjf:entrypoints}), ResourcefulLib
 * ({@code resourcefullib:resourcepack}), fzzy_config, rrls and yumi.
 *
 * <h2>What crosses, Fabric to the Forge family</h2>
 *
 * <p>Every {@code custom} value, under its own key, as FML's TOML reader would have handed it: an object is a
 * night-config {@code CommentedConfig}, an array a {@code List} ({@link #customProperties}). The two blocks are the
 * same channel, so this crosses unconditionally.
 *
 * <p>An entrypoint is a different thing — a class NAME under a key — and a property under the same key is not always a
 * name: LibJF's NeoForge build casts its {@code libjf:config} property to a night-config {@code Config} (a migration
 * table), while on Fabric {@code libjf:config} is the entrypoint naming a config class. So the names a Fabric mod
 * declares under a key are offered there only when a reader has said, in its own bytecode, that it reads that key as a
 * name — the value of {@code getModProperties().get(key)} goes into {@code instanceof String} or
 * {@code checkcast String} — and no reader reads it as anything else ({@link #noteRead}, recorded by
 * {@code DeclarationReaderModListInjector} as each reader class is defined, so before it runs). Sodium's
 * {@code ConfigLoaderForge} is such a reader of {@code sodium:config_api_user}; LibJF's {@code DslConfigInstance} marks
 * {@code libjf:config} as not one. Only NAMESPACED keys ({@code <namespace>:<name>}) are offered at all: a bare key like
 * {@code main}, {@code jade} or {@code modmenu} belongs to one loader's API, and the other family's library may use the
 * same word for something unrelated. The table a reader sees is therefore a view ({@link #declarationsOf}): the custom
 * values, plus the names under each key a reader has asked for by name.
 *
 * <h2>What crosses, Forge family to Fabric</h2>
 *
 * <p>Properties reach Fabric as custom values ({@code KernelFabricEcosystem.customValuesOf}). A namespaced property whose
 * value is a class name (or a list of them) is also an entrypoint under that key ({@link #fabricEntrypoints}); a Fabric
 * reader then type-checks and constructs it through its own entrypoint machinery, and skips what does not fit. The
 * {@code fabric} namespace is excluded: {@code fabric:provides} and its kin are Fabric METADATA fields written as
 * properties.
 *
 * <h2>When adding the Fabric mods fails</h2>
 *
 * <p>The readers this feeds run inside a library's own startup — a config loader inside {@code Minecraft.<init>}, with no
 * handler of its own around the walk — so the game-side hooks never let a failure of theirs escape: the reader gets
 * exactly what its own {@code ModList} answered, as if no Fabric mod declared anything, and the failure is a
 * {@code SUSPECTED} finding naming the reader ({@link #readerFailed}).
 *
 * <p>{@code -Dforbric.crossEcosystemDeclarations=off} turns all of it off, in both directions; the old
 * {@code -Dforbric.sodiumConfigUsers} is honoured as its former name.
 */
public final class CrossEcosystemDeclarations {
	public static final String SWITCH = "forbric.crossEcosystemDeclarations";

	/** How a reader's own bytecode uses the value it reads under a key. */
	public enum Read {
		/** {@code instanceof String} / {@code checkcast String}: the reader asks for a name. */
		NAME,
		/** A cast or type test to anything else: the reader asks for something that is not a name. */
		OTHER
	}

	private static final Set<String> NAME_READS = ConcurrentHashMap.newKeySet();
	private static final Set<String> OTHER_READS = ConcurrentHashMap.newKeySet();
	/** Fabric mod id → key → the class name declared there, or the list of them. Published once per boot. */
	private static volatile Map<String, Map<String, Object>> fabricNames = Map.of();

	private CrossEcosystemDeclarations() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(ForbricSwitches.get(SWITCH, "on"));
	}

	/**
	 * Whether {@code key} is written {@code <namespace>:<name>} — both halves non-empty. Only the first colon counts,
	 * so {@code adventure-internal:sidedproxy/client} is namespaced and {@code :x} / {@code x:} are not.
	 */
	public static boolean namespaced(String key) {
		if (key == null) return false;
		int colon = key.indexOf(':');
		return colon > 0 && colon < key.length() - 1;
	}

	// ---- Fabric → Forge family ------------------------------------------------------------------------------------

	/** A Fabric mod's {@code custom} block as {@code [modproperties]} entries. Empty when the switch is off. */
	public static Map<String, Object> customProperties(Map<String, CustomValue> custom) {
		if (!enabled() || custom == null || custom.isEmpty()) return Map.of();
		Map<String, Object> table = new LinkedHashMap<>();
		for (Map.Entry<String, CustomValue> entry : custom.entrySet()) {
			Object value = fmlValue(entry.getValue());
			if (entry.getKey() != null && value != null) table.put(entry.getKey(), value);
		}
		return table.isEmpty() ? Map.of() : Collections.unmodifiableMap(table);
	}

	/**
	 * The class names a Fabric mod declares under each namespaced entrypoint key: the name, or the list of names in
	 * declaration order when there are several. Lifecycle and other bare keys are left out.
	 */
	public static Map<String, Object> entrypointNames(Map<String, List<EntrypointDecl>> entrypoints) {
		if (entrypoints == null || entrypoints.isEmpty()) return Map.of();
		Map<String, Object> names = new LinkedHashMap<>();
		for (Map.Entry<String, List<EntrypointDecl>> entry : entrypoints.entrySet()) {
			if (!namespaced(entry.getKey())) continue;
			List<String> classes = new ArrayList<>();
			for (EntrypointDecl decl : entry.getValue() == null ? List.<EntrypointDecl>of() : entry.getValue()) {
				if (decl != null && decl.value() != null && !decl.value().isBlank()) classes.add(decl.value());
			}
			if (classes.size() == 1) names.put(entry.getKey(), classes.get(0));
			else if (!classes.isEmpty()) names.put(entry.getKey(), List.copyOf(classes));
		}
		return names.isEmpty() ? Map.of() : Collections.unmodifiableMap(names);
	}

	/** Publishes every Fabric mod's {@link #entrypointNames}, by mod id. Replaces what was published before. */
	public static void publishFabricEntrypointNames(Map<String, Map<String, Object>> byModId) {
		fabricNames = byModId == null ? Map.of() : Map.copyOf(byModId);
	}

	/**
	 * What {@code mod} declares, as the {@code [modproperties]} table a Forge-family reader reads: for a Fabric mod a
	 * live view of its custom values plus the names under each key a reader has asked for by name (see the class
	 * javadoc); for any other mod its own table, unchanged.
	 */
	public static Map<String, Object> declarationsOf(DiscoveredMod mod) {
		if (mod == null) return Map.of();
		if (mod.getEcosystem() != Ecosystem.FABRIC) return mod.getModProperties();
		Map<String, Object> names = fabricNames.getOrDefault(mod.getId(), Map.of());
		if (names.isEmpty()) return mod.getModProperties();
		return new FabricDeclarations(mod.getModProperties(), names);
	}

	/**
	 * Whether {@code mod} has anything that can cross: custom values, or entrypoint names a reader may ask for. What it
	 * declares right now is {@link #declarationsOf}, which depends on what readers have asked for so far.
	 */
	public static boolean mayDeclare(DiscoveredMod mod) {
		if (mod == null) return false;
		return !mod.getModProperties().isEmpty()
				|| mod.getEcosystem() == Ecosystem.FABRIC && !fabricNames.getOrDefault(mod.getId(), Map.of()).isEmpty();
	}

	/**
	 * Records how a reader class reads {@code key}. Called by {@code DeclarationReaderModListInjector} for every class
	 * that reads {@code getModProperties()}, as it is defined.
	 */
	public static void noteRead(String key, Read read, String reader) {
		if (key == null || read == null) return;
		boolean added = (read == Read.NAME ? NAME_READS : OTHER_READS).add(key);
		if (added && namespaced(key)) {
			ForbricLog.info("[Forbric/Declarations] %s reads [modproperties] '%s' as %s%s", reader, key,
					read == Read.NAME ? "a class name" : "something other than a name",
					read == Read.NAME ? " — a Fabric mod naming a class under that entrypoint key declares it there too"
							: " — Fabric entrypoints under that key are not offered there");
		}
	}

	/** Whether a reader asks for names under {@code key}, and none for anything else. */
	public static boolean asksForName(String key) {
		return NAME_READS.contains(key) && !OTHER_READS.contains(key);
	}

	/** Forgets every recorded read and published name. For tests. */
	public static void resetForTests() {
		NAME_READS.clear();
		OTHER_READS.clear();
		REPORTED.clear();
		fabricNames = Map.of();
	}

	// ---- a reader the Fabric mods could not be added for -------------------------------------------------------------

	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
	private static final StackWalker FRAMES = StackWalker.getInstance();

	/**
	 * Called by a game-side declaration-reader hook that could not add the declaring Fabric mods to what a
	 * Forge-family {@code ModList} answered — the hook then hands the reader that native answer unchanged. Records,
	 * once per reader class and hook, a {@code SUSPECTED} finding naming the reader: it is running, with its own
	 * family's mods, and only the Fabric mods that declare its keys are missing from what it sees. A
	 * {@link VirtualMachineError} is not a failure of the hook and is rethrown; nothing else ever leaves this method,
	 * since it runs inside the hook's own guard.
	 */
	public static void readerFailed(String hook, Throwable failure) {
		if (failure instanceof VirtualMachineError fatal) throw fatal;
		try {
			String reader = readerClass();
			String id = "declaration-readers:" + hook + ":" + reader;
			if (!REPORTED.add(id)) return;
			String detail = reader + " reads mods' [modproperties] out of ModList." + hook + ", and adding the Fabric "
					+ "mods that declare the same keys failed; it was given ModList's own answer, without them";
			ForbricLog.warn("[Forbric/Declarations] %s: %s", detail, String.valueOf(failure));
			net.forbric.api.CompatibilityFindings.record(new net.forbric.api.CompatibilityFinding(id, "forbric",
					"Mod integration", "CrossEcosystemDeclarations",
					net.forbric.api.CompatibilityFinding.Confidence.SUSPECTED, false, detail,
					List.of("reader=" + reader, "hook=ModList." + hook, String.valueOf(failure))));
		} catch (VirtualMachineError fatal) {
			throw fatal;
		} catch (Throwable unreported) {
			// The reader already has its native answer; failing to report that must not take it away.
		}
	}

	/** The class that called a hook: the first frame that is neither the kernel's nor the JDK's own. */
	private static String readerClass() {
		return FRAMES.walk(frames -> frames.map(StackWalker.StackFrame::getClassName)
				.filter(name -> !name.startsWith("net.forbric.kernel.") && !name.startsWith("java.")
						&& !name.startsWith("jdk.") && !name.startsWith("sun."))
				.findFirst().orElse("an unnamed reader"));
	}

	/** A Fabric mod's custom values, plus the entrypoint names under each key a reader asks for by name. */
	private static final class FabricDeclarations extends AbstractMap<String, Object> {
		private final Map<String, Object> custom;
		private final Map<String, Object> names;

		FabricDeclarations(Map<String, Object> custom, Map<String, Object> names) {
			this.custom = custom;
			this.names = names;
		}

		/** A custom value under a key is the mod's own word for it and is never replaced by a name. */
		private boolean offered(Object key) {
			return key instanceof String k && !custom.containsKey(k) && names.containsKey(k) && enabled() && asksForName(k);
		}

		@Override
		public Object get(Object key) {
			Object value = custom.get(key);
			if (value != null) return value;
			return offered(key) ? names.get(key) : null;
		}

		@Override
		public boolean containsKey(Object key) {
			return custom.containsKey(key) || offered(key);
		}

		@Override
		public boolean isEmpty() {
			if (!custom.isEmpty()) return false;
			for (String key : names.keySet()) {
				if (offered(key)) return false;
			}
			return true;
		}

		@Override
		public Set<Entry<String, Object>> entrySet() {
			Map<String, Object> now = new LinkedHashMap<>(custom);
			for (Map.Entry<String, Object> entry : names.entrySet()) {
				if (offered(entry.getKey())) now.put(entry.getKey(), entry.getValue());
			}
			return Collections.unmodifiableMap(now).entrySet();
		}
	}

	// ---- Forge family → Fabric ------------------------------------------------------------------------------------

	/**
	 * A Forge-family mod's {@code [modproperties]} table as the Fabric entrypoints it declares: each namespaced key
	 * outside the {@code fabric} namespace whose value is a class name or a list of class names. Empty when the switch
	 * is off.
	 */
	public static Map<String, List<EntrypointDecl>> fabricEntrypoints(Map<String, Object> modProperties) {
		if (!enabled() || modProperties == null || modProperties.isEmpty()) return Map.of();
		Map<String, List<EntrypointDecl>> out = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : modProperties.entrySet()) {
			String key = entry.getKey();
			if (!namespaced(key) || key.startsWith("fabric:")) continue;
			List<String> classes = classNames(entry.getValue());
			if (classes.isEmpty()) continue;
			List<EntrypointDecl> decls = new ArrayList<>(classes.size());
			for (String name : classes) decls.add(new EntrypointDecl("default", name));
			out.put(key, List.copyOf(decls));
		}
		return out.isEmpty() ? Map.of() : Collections.unmodifiableMap(out);
	}

	/** A string, or a non-empty list of nothing but strings; anything else is not a class declaration. */
	private static List<String> classNames(Object value) {
		if (value instanceof String single) return single.isBlank() ? List.of() : List.of(single);
		if (!(value instanceof List<?> list) || list.isEmpty()) return List.of();
		List<String> names = new ArrayList<>(list.size());
		for (Object element : list) {
			if (!(element instanceof String name) || name.isBlank()) return List.of();
			names.add(name);
		}
		return names;
	}

	// ---- value shapes ----------------------------------------------------------------------------------------------

	/**
	 * One custom value with the types night-config's TOML reader gives the same data: {@code CommentedConfig} for an
	 * object, {@code List} for an array, {@code String}, {@code Boolean}, {@code Integer}/{@code Long} for a whole
	 * number and {@code Double} otherwise. A JSON {@code null} has no TOML spelling and is left out, as is an array
	 * element or object member that is one.
	 */
	static Object fmlValue(CustomValue value) {
		if (value == null) return null;
		return switch (value.getType()) {
			case OBJECT -> {
				CommentedConfig table = TomlFormat.instance().createConfig(LinkedHashMap::new);
				for (Map.Entry<String, CustomValue> member : value.getAsObject()) {
					Object converted = fmlValue(member.getValue());
					// A singleton path: a key containing '.' is ONE key, not a dotted path into a sub-table.
					if (member.getKey() != null && converted != null) table.set(List.of(member.getKey()), converted);
				}
				yield table;
			}
			case ARRAY -> {
				List<Object> elements = new ArrayList<>();
				for (CustomValue element : value.getAsArray()) {
					Object converted = fmlValue(element);
					if (converted != null) elements.add(converted);
				}
				yield elements;
			}
			case STRING -> value.getAsString();
			case BOOLEAN -> value.getAsBoolean();
			case NUMBER -> number(value.getAsNumber());
			case NULL -> null;
		};
	}

	private static Object number(Number number) {
		if (number == null) return null;
		if (number instanceof Integer || number instanceof Short || number instanceof Byte) return number.intValue();
		if (number instanceof Long || number instanceof BigInteger) {
			BigInteger whole = number instanceof BigInteger big ? big : BigInteger.valueOf(number.longValue());
			if (whole.bitLength() < 32) return whole.intValue();
			if (whole.bitLength() < 64) return whole.longValue();
			return number.doubleValue();
		}
		if (number instanceof BigDecimal decimal) return decimal.doubleValue();
		return number.doubleValue();
	}
}
