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

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;

/** The needle hint, the ASM confirm, and the catalog mark — over synthetic jars. */
@org.junit.jupiter.api.parallel.ResourceLock("ModCatalog")
class FieldDriftAuditTest {
	/** The drift the ledger still lists: WeightedList.Builder's result, re-typed from ImmutableList.Builder to List. */
	private static final String OWNER = "net/minecraft/util/random/WeightedList$Builder";
	private static final String FIELD = "result";
	private static final String VANILLA_DESC = "Lcom/google/common/collect/ImmutableList$Builder;";
	private static final String MERGED_DESC = "Ljava/util/List;";

	private List<ModCatalog.Entry> previous;

	@AfterEach
	void forget() {
		FieldDriftAudit.reset();
		System.clearProperty(FieldDriftAudit.SWITCH);
		if (previous != null) ModCatalog.publish(previous);
	}

	/** A class that reads {@code OWNER.FIELD} with {@code desc}, or only names OWNER as a type. */
	private static byte[] reader(String desc) {
		return reader(OWNER, FIELD, desc);
	}

	private static byte[] reader(String owner, String field, String desc) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Keys", null, "java/lang/Object", null);
		MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "peek", "()V", null, null);
		m.visitCode();
		if (desc != null) {
			m.visitFieldInsn(Opcodes.GETSTATIC, owner, field, desc);
			m.visitInsn(Opcodes.POP);
		} else {
			m.visitTypeInsn(Opcodes.NEW, owner);    // the type is named, no field is read
			m.visitInsn(Opcodes.POP);
		}
		m.visitInsn(Opcodes.RETURN);
		m.visitMaxs(1, 0);
		m.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static Path jar(Path dir, String name, byte[] classBytes) throws Exception {
		Path jar = dir.resolve(name);
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("com/example/Keys.class"));
			zip.write(classBytes);
			zip.closeEntry();
		}
		return jar;
	}

	@Test
	void aVanillaDescriptorReadNamesItsJarWithTheTriple(@TempDir Path dir) throws Exception {
		Path hit = jar(dir, "oldmod.jar", reader(VANILLA_DESC));
		FieldDriftAudit.scan(List.of(hit));
		assertEquals(Set.of(OWNER + "#" + FIELD + ":" + VANILLA_DESC), FieldDriftAudit.hits().get("oldmod.jar"));
	}

	@Test
	void theMergedDescriptorAndATypeOnlyMentionAreNotFindings(@TempDir Path dir) throws Exception {
		FieldDriftAudit.scan(List.of(jar(dir, "neo.jar", reader(MERGED_DESC)), jar(dir, "typeonly.jar", reader(null)),
				jar(dir, "keys.jar", reader("net/minecraft/client/KeyMapping", "MAP", "Ljava/util/Map;"))));
		assertTrue(FieldDriftAudit.hits().isEmpty(), "a NeoForge build reads the merged descriptor; a type mention reads nothing; "
				+ "vanilla's KeyMapping.MAP is given back: " + FieldDriftAudit.hits());
	}

	@Test
	void unreadableBytesNameNobody() {
		FieldDriftAudit.note("junk.jar", new byte[] { 1, 2, 3 });
		FieldDriftAudit.note("junk.jar", ("garbage " + OWNER + " garbage").getBytes());
		assertTrue(FieldDriftAudit.hits().isEmpty());
	}

	@Test
	void reportMarksOnlyTheCatalogEntryWhoseJarMatchesAndLeavesFailedSticky(@TempDir Path dir) throws Exception {
		previous = ModCatalog.everything();
		ModCatalog.publish(List.of(
				new ModCatalog.Entry(Ecosystem.FABRIC, "oldmod", "Old", "1", "", List.of(), "oldmod.jar", "", ""),
				new ModCatalog.Entry(Ecosystem.FABRIC, "other", "Other", "1", "", List.of(), "other.jar", "", ""),
				new ModCatalog.Entry(Ecosystem.FABRIC, "broken", "Broken", "1", "", List.of(), "broken.jar", "", "")));
		ModCatalog.mark("broken", ModCatalog.Status.FAILED, "its entrypoint threw");
		FieldDriftAudit.scan(List.of(jar(dir, "oldmod.jar", reader(VANILLA_DESC)), jar(dir, "broken.jar", reader(VANILLA_DESC))));
		FieldDriftAudit.report();
		var failures = ModCatalog.failures();
		ModCatalog.Entry old = failures.stream().filter(e -> e.modId().equals("oldmod")).findFirst().orElse(null);
		assertTrue(old != null && old.status() == ModCatalog.Status.DEGRADED && old.statusDetail().contains("WeightedList$Builder#result"), String.valueOf(old));
		assertTrue(failures.stream().noneMatch(e -> e.modId().equals("other")), "a jar the scan never hit is untouched");
		ModCatalog.Entry broken = failures.stream().filter(e -> e.modId().equals("broken")).findFirst().orElse(null);
		assertTrue(broken != null && broken.status() == ModCatalog.Status.FAILED, "FAILED outranks DEGRADED: " + broken);
	}

	@Test
	void switchedOffItScansNothing(@TempDir Path dir) throws Exception {
		System.setProperty(FieldDriftAudit.SWITCH, "off");
		FieldDriftAudit.scan(List.of(jar(dir, "oldmod.jar", reader(VANILLA_DESC))));
		assertTrue(FieldDriftAudit.hits().isEmpty());
	}
}
