/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.metadata;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class ApiJarContractTest {
	private static final List<String> CONTRACT = List.of("class unrelated/api/Storage public,interface",
			"field unrelated/api/Storage LOOKUP Ljava/lang/Object; public,static",
			"method unrelated/api/Storage transfer (J)J public,instance");
	private static byte[] api(String name, String parent, String result, String extra) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, name, null, "java/lang/Object", parent == null ? null : new String[]{parent});
		writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "LOOKUP", "Ljava/lang/Object;", null, null).visitEnd();
		if (result != null) writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "transfer", "(J)" + result, null, null).visitEnd();
		if (extra != null) writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, extra, "I", null, 12).visitEnd();
		writer.visitEnd(); return writer.toByteArray();
	}
	@Test void differentReleaseBytesAndAdditionalMembersPreserveTheSamePublicContract() {
		byte[] first = api("unrelated/api/Storage", null, "J", null), next = api("unrelated/api/Storage", null, "J", "ADDITIONAL_VERSION_DETAIL");
		assertFalse(java.util.Arrays.equals(first, next));
		assertEquals(List.of(), ApiJarContract.verify(CONTRACT, Map.of("unrelated/api/Storage.class", first)::get));
		assertEquals(List.of(), ApiJarContract.verify(CONTRACT, Map.of("unrelated/api/Storage.class", next)::get));
	}
	@Test void inheritedDeclarationsRemainCompatibleButChangedReturnTypesDoNot() {
		Map<String, byte[]> inherited = Map.of("unrelated/api/Storage.class", api("unrelated/api/Storage", "unrelated/api/Base", null, null),
				"unrelated/api/Base.class", api("unrelated/api/Base", null, "J", null));
		assertEquals(List.of(), ApiJarContract.verify(CONTRACT, inherited::get));
		byte[] incompatible = api("unrelated/api/Storage", null, "I", null);
		assertEquals(List.of(CONTRACT.get(2)), ApiJarContract.verify(CONTRACT, Map.of("unrelated/api/Storage.class", incompatible)::get));
	}
	@Test void missingClassesAndUnknownContractConditionsFailExplicitly() {
		assertEquals(CONTRACT, ApiJarContract.verify(CONTRACT, ignored -> null));
		assertThrows(IllegalArgumentException.class, () -> ApiJarContract.verify(List.of("class unrelated/api/Storage guessed"),
				Map.of("unrelated/api/Storage.class", api("unrelated/api/Storage", null, "J", null))::get));
	}
}
