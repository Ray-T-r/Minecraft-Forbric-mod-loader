package net.forbric.installer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON parser + pretty writer. Just enough for the installer to read its own
 * build manifest ({@code forbric-libraries.json}) and emit a Minecraft version profile. Values map to
 * {@link Map}/{@link List}/{@link String}/{@link Long}/{@link Double}/{@link Boolean}/{@code null}.
 */
final class Json {
	private Json() {}

	// ---------- parse ----------

	static Object parse(String s) {
		Parser p = new Parser(s);
		p.ws();
		Object v = p.value();
		p.ws();
		if (!p.end()) throw new IllegalArgumentException("JSON: trailing data at index " + p.i);
		return v;
	}

	private static final class Parser {
		final String s;
		int i;

		Parser(String s) { this.s = s; }

		boolean end() { return i >= s.length(); }

		char cur() {
			if (end()) throw err("more input");
			return s.charAt(i);
		}

		void ws() { while (!end() && Character.isWhitespace(s.charAt(i))) i++; }

		Object value() {
			ws();
			char c = cur();
			switch (c) {
				case '{': return obj();
				case '[': return arr();
				case '"': return str();
				case 't': expect("true"); return Boolean.TRUE;
				case 'f': expect("false"); return Boolean.FALSE;
				case 'n': expect("null"); return null;
				default: return num();
			}
		}

		Map<String, Object> obj() {
			Map<String, Object> m = new LinkedHashMap<>();
			i++; // consume '{'
			ws();
			if (cur() == '}') { i++; return m; }
			while (true) {
				ws();
				String k = str();
				ws();
				if (cur() != ':') throw err(":");
				i++;
				m.put(k, value());
				ws();
				char c = cur();
				if (c == ',') { i++; continue; }
				if (c == '}') { i++; break; }
				throw err(", or }");
			}
			return m;
		}

		List<Object> arr() {
			List<Object> l = new ArrayList<>();
			i++; // consume '['
			ws();
			if (cur() == ']') { i++; return l; }
			while (true) {
				l.add(value());
				ws();
				char c = cur();
				if (c == ',') { i++; continue; }
				if (c == ']') { i++; break; }
				throw err(", or ]");
			}
			return l;
		}

		String str() {
			if (cur() != '"') throw err("\"");
			i++;
			StringBuilder b = new StringBuilder();
			while (true) {
				char c = s.charAt(i++);
				if (c == '"') break;
				if (c == '\\') {
					char e = s.charAt(i++);
					switch (e) {
						case '"': b.append('"'); break;
						case '\\': b.append('\\'); break;
						case '/': b.append('/'); break;
						case 'b': b.append('\b'); break;
						case 'f': b.append('\f'); break;
						case 'n': b.append('\n'); break;
						case 'r': b.append('\r'); break;
						case 't': b.append('\t'); break;
						case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
						default: throw err("valid escape");
					}
				} else {
					b.append(c);
				}
			}
			return b.toString();
		}

		Object num() {
			int start = i;
			while (!end() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
			String n = s.substring(start, i);
			if (n.isEmpty()) throw err("a value");
			if (n.indexOf('.') >= 0 || n.indexOf('e') >= 0 || n.indexOf('E') >= 0) return Double.parseDouble(n);
			try { return Long.parseLong(n); } catch (NumberFormatException e) { return Double.parseDouble(n); }
		}

		void expect(String w) {
			if (!s.startsWith(w, i)) throw err(w);
			i += w.length();
		}

		RuntimeException err(String expected) {
			return new IllegalArgumentException("JSON: expected " + expected + " at index " + i);
		}
	}

	// ---------- write (2-space pretty) ----------

	static String write(Object o) {
		StringBuilder b = new StringBuilder();
		write(o, b, 0);
		return b.toString();
	}

	private static void write(Object o, StringBuilder b, int indent) {
		if (o == null) { b.append("null"); return; }
		if (o instanceof String) { writeString((String) o, b); return; }
		if (o instanceof Boolean || o instanceof Number) { b.append(o.toString()); return; }
		if (o instanceof Map) {
			Map<?, ?> m = (Map<?, ?>) o;
			if (m.isEmpty()) { b.append("{}"); return; }
			b.append("{\n");
			int n = 0;
			for (Map.Entry<?, ?> e : m.entrySet()) {
				indent(b, indent + 1);
				writeString(String.valueOf(e.getKey()), b);
				b.append(": ");
				write(e.getValue(), b, indent + 1);
				if (++n < m.size()) b.append(',');
				b.append('\n');
			}
			indent(b, indent);
			b.append('}');
			return;
		}
		if (o instanceof List) {
			List<?> l = (List<?>) o;
			if (l.isEmpty()) { b.append("[]"); return; }
			b.append("[\n");
			for (int k = 0; k < l.size(); k++) {
				indent(b, indent + 1);
				write(l.get(k), b, indent + 1);
				if (k < l.size() - 1) b.append(',');
				b.append('\n');
			}
			indent(b, indent);
			b.append(']');
			return;
		}
		writeString(o.toString(), b);
	}

	private static void indent(StringBuilder b, int n) { for (int k = 0; k < n; k++) b.append("  "); }

	private static void writeString(String s, StringBuilder b) {
		b.append('"');
		for (int k = 0; k < s.length(); k++) {
			char c = s.charAt(k);
			switch (c) {
				case '"': b.append("\\\""); break;
				case '\\': b.append("\\\\"); break;
				case '\n': b.append("\\n"); break;
				case '\r': b.append("\\r"); break;
				case '\t': b.append("\\t"); break;
				case '\b': b.append("\\b"); break;
				case '\f': b.append("\\f"); break;
				default:
					if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
					else b.append(c);
			}
		}
		b.append('"');
	}
}
