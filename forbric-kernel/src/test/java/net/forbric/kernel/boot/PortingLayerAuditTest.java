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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * Covers the audit that names a Fabric mod whose shipped copy of a Forge-family class disagrees with the carrier.
 *
 * <p>Synthetic jars here, because what is being tested is the COMPARISON — which differences count and which do
 * not — and that has to be pinned by construction rather than by whatever the one real porting layer happens to
 * do this version. The real jar is the subject of {@code PortingLayerAbiInjectorTest}.
 */
class PortingLayerAuditTest {

	@TempDir
	Path tmp;

	@Test
	void aDescriptorThatDiffersIsTheWholePoint() throws Exception {
		Path mod = jar("mod.jar", "net/neoforged/fml/config/ConfigTracker",
				method("registerConfig", "(Ljava/lang/String;)V"));
		Path carrier = jar("carrier.jar", "net/neoforged/fml/config/ConfigTracker",
				method("registerConfig", "(Lnet/neoforged/fml/ModContainer;)V"));

		PortingLayerAudit.Report report = PortingLayerAudit.audit(List.of(mod), List.of(carrier));

		assertEquals(1, report.shadowed());
		assertEquals(1, report.skews().size(), "a method the carrier does not declare is exactly the failure this "
				+ "audit exists to name — the mod's own compiled call site resolves against the carrier");
		PortingLayerAudit.Skew skew = report.skews().get(0);
		assertTrue(skew.onlyInJar().toString().contains("(Ljava/lang/String;)V"));
		assertTrue(skew.onlyInCarrier().toString().contains("Lnet/neoforged/fml/ModContainer;"));
	}

	@Test
	void anIdenticalCopyIsNotReported() throws Exception {
		Path mod = jar("mod.jar", "net/neoforged/fml/config/ConfigTracker",
				method("registerConfig", "(Lnet/neoforged/fml/ModContainer;)V"));
		Path carrier = jar("carrier.jar", "net/neoforged/fml/config/ConfigTracker",
				method("registerConfig", "(Lnet/neoforged/fml/ModContainer;)V"));

		PortingLayerAudit.Report report = PortingLayerAudit.audit(List.of(mod), List.of(carrier));

		assertEquals(1, report.shadowed(), "it is still shadowed — the carrier's copy is the one that loads");
		assertTrue(report.clean(), "but identical declarations cannot break a caller, and 46 of the real port's 54 "
				+ "classes are in this state. Reporting them would bury the 8 that matter");
	}

	/** Same members, different access. A method that stops being public breaks its callers just as thoroughly. */
	@Test
	void anAccessChangeAloneIsAReport() throws Exception {
		Path mod = jar("mod.jar", "net/neoforged/fml/config/ConfigTracker",
				method("registerConfig", "()V", Opcodes.ACC_PUBLIC));
		Path carrier = jar("carrier.jar", "net/neoforged/fml/config/ConfigTracker",
				method("registerConfig", "()V", 0));

		assertEquals(1, PortingLayerAudit.audit(List.of(mod), List.of(carrier)).skews().size());
	}

	/**
	 * fabric-api's own nested jars ship {@code net/fabricmc/fabric/**} and are its only legitimate source. That
	 * package is ALWAYS_GAME like the other two, but no carrier provides it, so every hit would be a false one.
	 */
	@Test
	void fabricApisOwnPackageIsNotAuditedAtAll() throws Exception {
		Path mod = jar("mod.jar", "net/fabricmc/fabric/api/Thing", method("x", "()V"));
		Path carrier = jar("carrier.jar", "net/fabricmc/fabric/api/Thing", method("y", "()V"));

		PortingLayerAudit.Report report = PortingLayerAudit.audit(List.of(mod), List.of(carrier));

		assertEquals(0, report.shadowed());
		assertTrue(report.clean());
	}

	/** A class no carrier has is the port's own, not a shadow: it loads, and nothing is wrong with it. */
	@Test
	void aClassTheCarrierDoesNotHaveIsNotAShadow() throws Exception {
		Path mod = jar("mod.jar", "net/neoforged/fml/config/PortOnly", method("x", "()V"));
		Path carrier = jar("carrier.jar", "net/neoforged/fml/config/Something", method("y", "()V"));

		assertEquals(0, PortingLayerAudit.audit(List.of(mod), List.of(carrier)).shadowed());
	}

	private record Member(String name, String desc, int access) {
	}

	private static Member method(String name, String desc) {
		return new Member(name, desc, Opcodes.ACC_PUBLIC);
	}

	private static Member method(String name, String desc, int access) {
		return new Member(name, desc, access);
	}

	private Path jar(String fileName, String internalName, Member... members) throws IOException {
		Path jar = tmp.resolve(fileName);
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
		for (Member m : members) cw.visitMethod(m.access(), m.name(), m.desc(), null, null).visitEnd();
		cw.visitEnd();
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			zip.putNextEntry(new ZipEntry(internalName + ".class"));
			OutputStream out = zip;
			out.write(cw.toByteArray());
			zip.closeEntry();
		}
		return jar;
	}
}
