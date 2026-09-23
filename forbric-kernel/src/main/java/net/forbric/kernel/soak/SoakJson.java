package net.forbric.kernel.soak;

import java.util.Map;
import java.util.stream.Collectors;

/** Small deterministic JSON encoder for the diagnostic's strings, numbers, booleans, maps and lists. */
public final class SoakJson {
	private SoakJson() { }
	public static String encode(Object value) {
		if (value == null) return "null";
		if (value instanceof Number || value instanceof Boolean) return value.toString();
		if (value instanceof Map<?, ?> map) return map.entrySet().stream().map(e -> quote(String.valueOf(e.getKey())) + ":" + encode(e.getValue())).collect(Collectors.joining(",", "{", "}"));
		if (value instanceof Iterable<?> values) { StringBuilder out = new StringBuilder("["); boolean first = true; for (Object entry : values) { if (!first) out.append(','); first = false; out.append(encode(entry)); } return out.append(']').toString(); }
		return quote(String.valueOf(value));
	}
	private static String quote(String value) {
		StringBuilder out = new StringBuilder("\"");
		for (char c : value.toCharArray()) switch (c) {
			case '\\' -> out.append("\\\\"); case '"' -> out.append("\\\""); case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
			default -> { if (c < 32) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
		}
		return out.append('"').toString();
	}
}
