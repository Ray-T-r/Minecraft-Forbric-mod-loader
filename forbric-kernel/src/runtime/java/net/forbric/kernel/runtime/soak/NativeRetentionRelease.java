package net.forbric.kernel.runtime.soak;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Differential attribution of a retained integrated server to a reviewed native mod root.
 *
 * <p>Runs once, after the measured soak has finished and every session's server has stopped. For each root
 * named by the launcher (only roots whose retention was reproduced on the unmodified native loader), it removes
 * exactly the block entities whose level belongs to one of the given stopped servers, and nothing else. If the
 * servers then become collectable, no other strong path held them: the retention was that native root alone.
 * If any server stays reachable the run remains a retention review. Nothing is changed while the game is being
 * measured.
 */
final class NativeRetentionRelease {
	private NativeRetentionRelease() { }

	/** Supported shape: a static {@code Collection<BlockEntity>} field, named {@code owner.FIELD}. */
	static List<Map<String, Object>> release(List<String> roots, Set<MinecraftServer> stopped, ClassLoader loader) {
		List<Map<String, Object>> rows = new ArrayList<>();
		Set<MinecraftServer> servers = Collections.newSetFromMap(new IdentityHashMap<>());
		servers.addAll(stopped);
		for (MinecraftServer server : servers)
			if (!server.isStopped()) throw new IllegalStateException("native retention release requires stopped servers only");
		for (String root : roots) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("root", root);
			int split = root.lastIndexOf('.');
			if (split <= 0 || split == root.length() - 1) throw new IllegalArgumentException("invalid native retention root " + root);
			Class<?> owner;
			try { owner = Class.forName(root.substring(0, split), false, loader); }
			catch (ClassNotFoundException absent) { row.put("present", false); rows.add(row); continue; }
			row.put("present", true);
			row.put("codeSource", codeSource(owner));
			Collection<?> values;
			try {
				Field field = owner.getDeclaredField(root.substring(split + 1));
				if (!Modifier.isStatic(field.getModifiers()) || !Collection.class.isAssignableFrom(field.getType()))
					throw new IllegalStateException("native retention root is not a static collection: " + root);
				field.setAccessible(true);
				values = (Collection<?>) field.get(null);
			} catch (ReflectiveOperationException failure) {
				throw new IllegalStateException("native retention root unreadable: " + root, failure);
			}
			int size = values == null ? 0 : values.size(), removed = 0, unsupported = 0;
			if (values != null) {
				for (Iterator<?> iterator = values.iterator(); iterator.hasNext(); ) {
					Object value = iterator.next();
					if (!(value instanceof BlockEntity entity)) { unsupported++; continue; }
					Level level = entity.getLevel();
					if (level != null && servers.contains(level.getServer())) { iterator.remove(); removed++; }
				}
			}
			row.put("sizeBefore", size);
			row.put("removedStoppedServerEntries", removed);
			row.put("unsupportedEntries", unsupported);
			rows.add(row);
		}
		servers.clear();
		return rows;
	}

	private static String codeSource(Class<?> owner) {
		try {
			var source = owner.getProtectionDomain().getCodeSource();
			return source == null || source.getLocation() == null ? null : Path.of(source.getLocation().toURI()).toString();
		} catch (Exception unknown) { return null; }
	}
}
