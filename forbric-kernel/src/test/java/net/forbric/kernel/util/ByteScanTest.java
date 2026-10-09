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

package net.forbric.kernel.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * The cheap first question several transformers ask of every class the game loads.
 *
 * <p>A "no" from this has to be FINAL, not a guess: the callers skip the class outright on a no, so a false
 * negative silently drops a repair. A false positive only costs a parse the caller was going to reject anyway.
 */
class ByteScanTest {

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.US_ASCII);
	}

	@Test
	void findsANeedleAnywhereInTheHaystack() {
		byte[] haystack = bytes("....net/minecraft/Thing....");

		assertTrue(ByteScan.contains(haystack, ByteScan.needle("net/minecraft/Thing")));
		assertTrue(ByteScan.contains(bytes("start"), ByteScan.needle("start")));
		assertTrue(ByteScan.contains(bytes("xxend"), ByteScan.needle("end")));
	}

	@Test
	void anyOfSeveralNeedlesIsEnough() {
		byte[][] needles = {ByteScan.needle("alpha"), ByteScan.needle("omega")};

		assertTrue(ByteScan.containsAny(bytes("...omega..."), needles));
		assertTrue(ByteScan.containsAny(bytes("alpha"), needles));
		assertFalse(ByteScan.containsAny(bytes("...beta..."), needles));
	}

	@Test
	void aNeedleLongerThanTheHaystackIsNotFound() {
		// The bounds case: a short class must not walk off the end while testing a long name.
		assertFalse(ByteScan.contains(bytes("ab"), ByteScan.needle("abcdef")));
	}

	@Test
	void aNeedleThatOverlapsTheEndIsNotAMatch() {
		// "abcd" ends with "abc"; looking for "abcz" must not match by running past the last byte.
		assertFalse(ByteScan.contains(bytes("abcd"), ByteScan.needle("abcz")));
		assertFalse(ByteScan.contains(bytes("abc"), ByteScan.needle("abcd")));
	}

	@Test
	void aShorterNeedleStillMatchesNearTheEndWhenAnotherIsLonger() {
		// The loop bounds are driven by the SHORTEST needle; a longer one must not stop the shorter one from
		// matching in the last few bytes, and must not read past the end while trying.
		byte[][] needles = {ByteScan.needle("aVeryLongNameIndeed"), ByteScan.needle("xy")};

		assertTrue(ByteScan.containsAny(bytes(".......xy"), needles));
	}

	/** A class whose pool holds two-slot constants, a call, a handle and a dynamic call site ahead of the names asked. */
	private static byte[] classFile() {
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
		writer.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC, "mixed/pool/Holder", null, "java/lang/Object", null);
		writer.newConst(Long.valueOf(7L));
		writer.newConst(Double.valueOf(2.5));
		writer.newConst(Float.valueOf(1.5f));
		writer.newConst(Integer.valueOf(123456));
		var method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC, "run", "()V", null, null);
		method.visitCode();
		method.visitLdcInsn(org.objectweb.asm.Type.getType("()V"));
		method.visitInsn(org.objectweb.asm.Opcodes.POP);
		method.visitInvokeDynamicInsn("go", "()Ljava/lang/Runnable;", new org.objectweb.asm.Handle(org.objectweb.asm.Opcodes.H_INVOKESTATIC,
				"java/lang/invoke/LambdaMetafactory", "metafactory",
				"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false));
		method.visitInsn(org.objectweb.asm.Opcodes.POP);
		method.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, "far/away/Target", "deliver", "(J)V", false);
		method.visitInsn(org.objectweb.asm.Opcodes.RETURN);
		method.visitMaxs(0, 0);
		method.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	@Test
	void theConstantPoolAnswersExactNamesOnly() {
		byte[][] entries = {ByteScan.poolEntry("far/away/Target"), ByteScan.poolEntry("deliver"), ByteScan.poolEntry("(J)V"),
				ByteScan.poolEntry("mixed/pool/Holder"), ByteScan.poolEntry("far/away"), ByteScan.poolEntry("far/away/TargetX"),
				ByteScan.poolEntry("never/Named")};
		boolean[] found = ByteScan.constantPoolNames(classFile(), entries);

		assertTrue(found[0] && found[1] && found[2], "the owner, name and descriptor of a call");
		assertTrue(found[3], "the class's own name");
		assertFalse(found[4], "a prefix of a name is not a name");
		assertFalse(found[5], "nor is a longer one");
		assertFalse(found[6]);
		assertTrue(ByteScan.namesAny(classFile(), new byte[][] {ByteScan.poolEntry("never/Named"), ByteScan.poolEntry("deliver")}));
		assertFalse(ByteScan.namesAny(classFile(), new byte[][] {ByteScan.poolEntry("never/Named")}));
		assertFalse(ByteScan.namesAny(classFile(), new byte[0][]), "nothing asked, nothing named");
	}

	/** A class whose names are not ASCII: an accented method, a method named with a supplementary character, and a
	 * string constant holding U+0000. The class file stores each in modified UTF-8. */
	private static byte[] unicodeClassFile(String accented, String astral, String withNul) {
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
		writer.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC, "mixed/pool/Unicode", null, "java/lang/Object", null);
		for (String name : new String[] {accented, astral}) {
			var method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC, name, "()V", null, null);
			method.visitCode();
			method.visitInsn(org.objectweb.asm.Opcodes.RETURN);
			method.visitMaxs(0, 0);
			method.visitEnd();
		}
		writer.newConst(withNul);
		writer.visitEnd();
		return writer.toByteArray();
	}

	@Test
	void aNameThatIsNotAsciiIsFoundAsTheClassFileStoresIt() {
		String accented = "ank\u00fcndigen", astral = "sig\uD835\uDD38nal", withNul = "a\u0000b";
		byte[] unicode = unicodeClassFile(accented, astral, withNul);

		boolean[] found = ByteScan.constantPoolNames(unicode, new byte[][] {ByteScan.poolEntry(accented), ByteScan.poolEntry(astral),
				ByteScan.poolEntry(withNul), astral.getBytes(java.nio.charset.StandardCharsets.UTF_8),
				withNul.getBytes(java.nio.charset.StandardCharsets.UTF_8), ByteScan.needle(accented)});
		assertTrue(found[0], "a two-byte character");
		assertTrue(found[1], "a supplementary character, stored as its two surrogates");
		assertTrue(found[2], "U+0000, stored as two bytes");
		assertFalse(found[3], "standard UTF-8 is not how a class file stores a supplementary character");
		assertFalse(found[4], "nor U+0000");
		assertFalse(found[5], "and an ASCII needle cannot name a non-ASCII name at all");
		assertArrayEquals(ByteScan.needle("plain/Ascii"), ByteScan.poolEntry("plain/Ascii"), "ASCII is its own encoding");
	}

	@Test
	void anUnreadablePoolReportsEveryNeedlePresent() {
		// Present means "parse it and see": the caller then fails or not exactly as it would have without asking.
		byte[][] entries = {ByteScan.poolEntry("never/Named")};
		byte[] whole = classFile();

		assertFalse(ByteScan.namesAny(whole, entries), "the intact class does not name it");
		assertTrue(ByteScan.namesAny(java.util.Arrays.copyOf(whole, 40), entries), "truncated inside the pool");
		int header = new org.objectweb.asm.ClassReader(whole).header;
		assertTrue(ByteScan.namesAny(java.util.Arrays.copyOf(whole, header + 4), entries), "truncated right after the pool");
		assertTrue(ByteScan.namesAny(bytes("not a class file at all"), entries), "no magic");
		assertTrue(ByteScan.namesAny(new byte[3], entries), "shorter than a header");
		byte[] unknownTag = whole.clone();
		unknownTag[10] = 99;
		assertTrue(ByteScan.namesAny(unknownTag, entries), "a tag this walk does not know");
		byte[] emptyPool = java.util.Arrays.copyOf(whole, 64);
		emptyPool[8] = 0;
		emptyPool[9] = 0;
		assertTrue(ByteScan.namesAny(emptyPool, entries), "a pool count of zero, which no class file has");
		boolean[] all = ByteScan.constantPoolNames(unknownTag, new byte[][] {ByteScan.poolEntry("a"), ByteScan.poolEntry("b")});
		assertTrue(all[0] && all[1], "every entry, not just one");
	}

	@Test
	void nothingToSearchAnswersNo() {
		assertFalse(ByteScan.contains(null, ByteScan.needle("a")));
		assertFalse(ByteScan.contains(new byte[0], ByteScan.needle("a")));
		assertFalse(ByteScan.containsAny(bytes("abc"), null));
		assertFalse(ByteScan.containsAny(bytes("abc"), new byte[][] {}));
		assertFalse(ByteScan.containsAny(bytes("abc"), new byte[][] {null, new byte[0]}));
	}
}
