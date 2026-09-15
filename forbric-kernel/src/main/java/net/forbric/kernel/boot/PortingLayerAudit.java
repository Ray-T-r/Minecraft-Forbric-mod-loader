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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.kernel.util.ForbricLog;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Names, at boot, every Fabric mod that ships its own copy of a Forge-family class the carrier also has — and
 * whether the two agree.
 *
 * <p>A "porting layer" is a Fabric mod that re-implements {@code net.neoforged.*} or {@code net.minecraftforge.*}
 * so Fabric mods can use that API. On a normal Fabric instance its copy IS the API. Under Forbric those packages
 * are {@code ALWAYS_GAME}, so the CARRIER's copy wins and the port's is never loaded — which is correct and
 * usually harmless, because the port is trying to be the real thing and mostly succeeds.
 *
 * <p>When it does not succeed, the failure has no useful shape. The port's own call sites were compiled against
 * its own copy, so a method the carrier renamed or re-signatured is a {@code NoSuchMethodError}, and an interface
 * method the carrier added is an {@code AbstractMethodError} — thrown not by the porting layer but by whichever
 * dependent first tried to use it, several lifecycle steps later. ForgeConfigAPIPort and ShoulderSurfing arrived
 * that way: the error named a config method, the crash named {@code Minecraft.<init>}, and neither named the jar.
 *
 * <p>So the skew is measured up front and reported by name. Comparing declarations only — access, name, descriptor,
 * supertypes — because that is exactly the surface a shadowed class's compiled callers depend on; bodies differ
 * freely and legitimately. Of the 54 shadow classes in ForgeConfigAPIPort, 46 match the carrier exactly.
 *
 * <p>It REPORTS and never refuses, which is the same policy {@code DependencyAudit} states: one bad jar must not
 * cost a player every other mod. {@code -Dforbric.portingLayerAudit=off} silences it.
 */
public final class PortingLayerAudit {

	private PortingLayerAudit() {
	}

	/** One shadowed class whose declarations differ from the carrier's. */
	public record Skew(Path jar, String className, List<String> onlyInJar, List<String> onlyInCarrier) {
	}

	/** What one audit found. {@code shadowed} counts every shadow class, agreeing or not. */
	public record Report(int shadowed, List<Skew> skews) {
		public boolean clean() {
			return skews.isEmpty();
		}
	}

	private static final String SWITCH = "forbric.portingLayerAudit";

	/**
	 * Compares every {@code net/neoforged/**} and {@code net/minecraftforge/**} class in {@code modJars} against
	 * the carriers'.
	 *
	 * <p>Deliberately NOT {@code net/fabricmc/fabric/**}: that is {@code ALWAYS_GAME} too, but no carrier provides
	 * it — fabric-api's own nested jars are its only and rightful source, so every hit would be a false one.
	 */
	public static Report audit(List<Path> modJars, List<Path> runtimeJars) {
		List<Skew> skews = new ArrayList<>();
		int shadowed = 0;
		Map<String, byte[]> carrier = new LinkedHashMap<>();
		for (Path jar : modJars) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (ZipEntry entry : zip.stream().toList()) {
					String name = entry.getName();
					if (!name.endsWith(".class")) continue;
					if (!name.startsWith("net/neoforged/") && !name.startsWith("net/minecraftforge/")) continue;
					byte[] theirs;
					try (InputStream in = zip.getInputStream(entry)) {
						theirs = in.readAllBytes();
					}
					byte[] ours = carrier.computeIfAbsent(name, n -> readFromCarriers(runtimeJars, n));
					if (ours.length == 0) continue; // port-only: no carrier has it, so nothing shadows it
					shadowed++;
					Skew skew = compare(jar, name, theirs, ours);
					if (skew != null) skews.add(skew);
				}
			} catch (IOException unreadable) {
				ForbricLog.debug("[Forbric/PortAudit] could not read %s: %s", jar.getFileName(), unreadable);
			}
		}
		return new Report(shadowed, List.copyOf(skews));
	}

	/** Runs the audit and says what it found. Never throws, never refuses a jar. */
	public static void report(List<Path> modJars, List<Path> runtimeJars) {
		if ("off".equalsIgnoreCase(System.getProperty(SWITCH, "on"))) return;
		Report report;
		try {
			report = audit(modJars, runtimeJars);
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/PortAudit] audit failed: %s", String.valueOf(t));
			return;
		}
		if (report.clean()) {
			ForbricLog.debug("[Forbric/PortAudit] %d shadowed Forge-family class(es), all matching the carrier",
					report.shadowed());
			return;
		}
		for (Skew skew : report.skews()) {
			ForbricLog.warn("[Forbric/PortAudit] %s ships its own %s and it does NOT match the carrier — its own "
					+ "compiled call sites will hit whatever the carrier declares instead. only in the mod: %s; "
					+ "only in the carrier: %s", skew.jar().getFileName(), skew.className().replace('/', '.'),
					skew.onlyInJar(), skew.onlyInCarrier());
		}
		ForbricLog.warn("[Forbric/PortAudit] %d of %d shadowed class(es) disagree with the carrier. A Fabric mod "
				+ "that re-implements net.neoforged.* / net.minecraftforge.* loses to the carrier by design; where "
				+ "the two disagree, every consumer of that API breaks, and the error surfaces in the CONSUMER",
				report.skews().size(), report.shadowed());
	}

	private static byte[] readFromCarriers(List<Path> runtimeJars, String entryName) {
		for (Path jar : runtimeJars) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				ZipEntry entry = zip.getEntry(entryName);
				if (entry == null) continue;
				try (InputStream in = zip.getInputStream(entry)) {
					return in.readAllBytes();
				}
			} catch (IOException unreadable) {
				// Next carrier; a carrier we cannot read is reported by whatever needed it.
			}
		}
		return new byte[0];
	}

	/** Null when the two declare the same thing. Bodies are not compared — only what a caller can depend on. */
	private static Skew compare(Path jar, String className, byte[] theirs, byte[] ours) {
		TreeSet<String> mine = declarations(theirs);
		TreeSet<String> yours = declarations(ours);
		if (mine.equals(yours)) return null;
		List<String> onlyMine = new ArrayList<>(mine);
		onlyMine.removeAll(yours);
		List<String> onlyYours = new ArrayList<>(yours);
		onlyYours.removeAll(mine);
		return new Skew(jar, className, onlyMine, onlyYours);
	}

	private static TreeSet<String> declarations(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node,
				ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		TreeSet<String> out = new TreeSet<>();
		out.add("extends " + node.superName);
		for (String itf : node.interfaces == null ? List.<String>of() : node.interfaces) out.add("implements " + itf);
		for (FieldNode f : node.fields) out.add("field " + f.access + " " + f.name + " " + f.desc);
		for (MethodNode m : node.methods) out.add("method " + m.access + " " + m.name + m.desc);
		return out;
	}
}
