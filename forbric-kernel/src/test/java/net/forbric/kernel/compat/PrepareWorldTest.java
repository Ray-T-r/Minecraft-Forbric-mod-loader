package net.forbric.kernel.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Prepare only an explicit disposable save: byte preservation protects every unrelated world setting. */
class PrepareWorldTest {
    @TempDir Path temporary;
    private static final String FLAG = "confirmedExperimentalSettings";

    @Test void changesOnlyTheDirectDataByteAndPreservesEveryOtherNbtPayload() throws Exception {
        Path save = temporary.resolve("level.dat");
        Files.write(save, gzip(fixture(0, true, 1, false)));
        var result = DriverTools.script("win/prepare-world.py", Map.of(), save.toString());
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("confirmedExperimentalSettings=1 changed=true"), result.output());
        assertArrayEquals(fixture(1, true, 1, false), gunzip(Files.readAllBytes(save)),
                "unrelated types, modified UTF-8, datapacks and lookalike names must remain byte-for-byte intact");
        byte[] prepared = Files.readAllBytes(save);
        result = DriverTools.script("win/prepare-world.py", Map.of(), save.toString());
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("changed=false"), result.output());
        assertArrayEquals(prepared, Files.readAllBytes(save), "already prepared saves must not be rewritten");
    }

    @Test void readOnlyCheckAndOutputCopyPreserveTheInputAndAbsentFlagIsInsertedInsideData() throws Exception {
        Path save = temporary.resolve("source.dat");
        byte[] original = gzip(fixture(0, false, 1, false));
        Files.write(save, original);
        var result = DriverTools.script("win/prepare-world.py", Map.of(), save.toString(), "--check");
        assertEquals(1, result.exit(), result.output());
        assertArrayEquals(original, Files.readAllBytes(save));
        Path copy = temporary.resolve("copied.dat");
        result = DriverTools.script("win/prepare-world.py", Map.of(), save.toString(), "--output", copy.toString());
        assertEquals(0, result.exit(), result.output());
        assertArrayEquals(original, Files.readAllBytes(save));
        assertArrayEquals(fixture(1, true, 1, false), gunzip(Files.readAllBytes(copy)));
        result = DriverTools.script("win/prepare-world.py", Map.of(), copy.toString(), "--check");
        assertEquals(0, result.exit(), result.output());
    }

    @Test void uncompressedNbtIsPatchedWithoutChangingItsEncoding() throws Exception {
        Path save = temporary.resolve("level.dat");
        Files.write(save, fixture(0, true, 1, false));
        var result = DriverTools.script("win/prepare-world.py", Map.of(), save.toString());
        assertEquals(0, result.exit(), result.output());
        assertArrayEquals(fixture(1, true, 1, false), Files.readAllBytes(save));
    }

    @Test void aCollidingTemporaryFileIsNotOwnedAndMustSurvive() throws Exception {
        Path save = temporary.resolve("level.dat");
        byte[] original = gzip(fixture(0, true, 1, false));
        Files.write(save, original);
        Path other = temporary.resolve(".level.dat.0000000000000000");
        Files.writeString(other, "another writer's file");
        var result = DriverTools.run(Map.of(), "-c", """
                import os,runpy,sys
                os.urandom=lambda n: bytes(n)
                sys.argv=sys.argv[1:]
                runpy.run_path(sys.argv[0],run_name='__main__')
                """, DriverTools.COMPAT.resolve("win/prepare-world.py").toString(), save.toString());
        assertEquals(2, result.exit(), result.output());
        assertArrayEquals(original, Files.readAllBytes(save));
        assertEquals("another writer's file", Files.readString(other));
    }

    @Test void rejectsWrongTypesDuplicateFlagsAndTruncatedInputWithoutModifyingAnyBytes() throws Exception {
        byte[] correct = fixture(0, true, 1, false);
        List<byte[]> invalid = List.of(fixture(0, true, 3, false), fixture(0, true, 1, true),
                Arrays.copyOf(correct, correct.length - 1), new byte[]{10, 0, 0, 0});
        for (byte[] raw : invalid) {
            Path save = temporary.resolve("bad.dat");
            byte[] original = gzip(raw);
            Files.write(save, original);
            var result = DriverTools.script("win/prepare-world.py", Map.of(), save.toString());
            assertEquals(2, result.exit(), result.output());
            assertArrayEquals(original, Files.readAllBytes(save), "malformed saves cannot be partially edited");
        }
    }

    @Test void windowsSharedHelperRunsTheSameToolAndClearsItsOwnedChildPid() throws Exception {
        Path world = temporary.resolve("copied world");
        Files.createDirectories(world);
        Files.write(world.resolve("level.dat"), gzip(fixture(0, true, 1, false)));
        var result = DriverTools.run(Map.of(), "-c", """
                import pathlib,sys
                sys.path.insert(0,sys.argv[1]);import common
                c={'pid_file':str(pathlib.Path(sys.argv[2])/'.forbric-sweep.pid')}
                common.prepare_world(c,pathlib.Path(sys.argv[2]))
                assert pathlib.Path(c['pid_file']).read_text()==''
                """, DriverTools.COMPAT.resolve("win").toString(), world.toString());
        assertEquals(0, result.exit(), result.output());
        assertArrayEquals(fixture(1, true, 1, false), gunzip(Files.readAllBytes(world.resolve("level.dat"))));
    }

    @Test void windowsServerStagesAndAcknowledgesTheCopyWhilePreservingTheGeneratedSave() throws Exception {
        Path source = temporary.resolve("generated world");
        Path instance = temporary.resolve("instance");
        Files.createDirectories(source);
        Files.createDirectories(instance);
        byte[] original = gzip(fixture(0, true, 1, false));
        Files.write(source.resolve("level.dat"), original);
        Files.writeString(source.resolve("region-marker.txt"), "generated terrain");
        var result = DriverTools.run(Map.of(), "-c", """
                import ast,pathlib,shutil,sys
                win=pathlib.Path(sys.argv[1]);sys.path.insert(0,str(win));import common
                tree=ast.parse((win/'run-server-test.py').read_text())
                blocks=[n.body for n in ast.walk(tree) if isinstance(getattr(n,'body',None),list)]
                body,index=next((body,i) for body in blocks for i,n in enumerate(body)
                    if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='target' for t in n.targets))
                instance=pathlib.Path(sys.argv[2]);world=pathlib.Path(sys.argv[3])
                c={'world':'copied-world','pid_file':str(instance/'.forbric-sweep.pid')}
                scope=dict(instance=instance,world=world,configuration=c,shutil=shutil,prepare_world=common.prepare_world)
                exec(compile(ast.Module(body=body[index:index+4],type_ignores=[]),'actual-server-copy','exec'),scope)
                """, DriverTools.COMPAT.resolve("win").toString(), instance.toString(), source.toString());
        assertEquals(0, result.exit(), result.output());
        assertArrayEquals(original, Files.readAllBytes(source.resolve("level.dat")));
        Path copied = instance.resolve("saves/copied-world");
        assertArrayEquals(fixture(1, true, 1, false), gunzip(Files.readAllBytes(copied.resolve("level.dat"))));
        assertEquals("generated terrain", Files.readString(copied.resolve("region-marker.txt")));
    }

    @Test void theClientGateAcknowledgesItsActualZeroModSaveThroughTheSharedTool() throws Exception {
        String script = Files.readString(Path.of("run/gate-m26-forgeclient.sh"));
        int begin = script.indexOf("# WORLD_CONFIRM_BEGIN");
        int end = script.indexOf("# WORLD_CONFIRM_END", begin);
        assertTrue(begin >= 0 && end > begin);
        Path world = temporary.resolve("saves/test world");
        Files.createDirectories(world);
        Path save = world.resolve("level.dat");
        Files.write(save, gzip(fixture(0, true, 1, false)));
        ProcessBuilder builder = new ProcessBuilder("bash", "-c", script.substring(begin, end));
        builder.environment().put("KERNEL", Path.of("").toAbsolutePath().toString());
        builder.environment().put("RUNDIR", temporary.toString());
        builder.environment().put("WORLD", "test world");
        Path log = temporary.resolve("gate-confirm.log");
        Process process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertTrue(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), Files.readString(log));
        assertArrayEquals(fixture(1, true, 1, false), gunzip(Files.readAllBytes(save)));
    }

    @Test void theCarrierReadsThisFlagAndUsesItToSuppressOnlyTheExperimentalBackupPrompt() throws Exception {
        Path loader = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"));
        Path merged = loader.resolve("run/merged-base/patched-mc-merged-26.2.jar");
        assumeTrue(Files.isRegularFile(merged), "requires current staged merged base");
        try (ZipFile zip = new ZipFile(merged.toFile())) {
            ClassNode data = read(zip, "net/minecraft/world/level/storage/PrimaryLevelData");
            MethodNode parse = data.methods.stream().filter(m -> m.name.equals("parse")).findFirst().orElseThrow();
            List<AbstractInsnNode> code = Arrays.asList(parse.instructions.toArray());
            int key = -1, getBoolean = -1, confirm = -1;
            for (int i = 0; i < code.size(); i++) {
                if (code.get(i) instanceof LdcInsnNode literal && FLAG.equals(literal.cst)) key = i;
                if (key >= 0 && code.get(i) instanceof MethodInsnNode call && call.name.equals("asBoolean")) getBoolean = i;
                if (key >= 0 && code.get(i) instanceof MethodInsnNode call && call.name.equals("withConfirmedWarning")) confirm = i;
            }
            assertTrue(key >= 0 && getBoolean > key && confirm > getBoolean, "carrier must parse this persisted boolean");
            MethodNode getter = data.methods.stream().filter(m -> m.name.equals("hasConfirmedExperimentalWarning")).findFirst().orElseThrow();
            assertTrue(Arrays.stream(getter.instructions.toArray()).anyMatch(instruction -> instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD && field.name.equals("confirmedExperimentalWarning") && field.desc.equals("Z")));
            ClassNode flows = read(zip, "net/minecraft/client/gui/screens/worldselection/WorldOpenFlows");
            MethodNode compatibility = flows.methods.stream().filter(m -> m.name.equals("openWorldCheckWorldStemCompatibility")).findFirst().orElseThrow();
            MethodInsnNode check = Arrays.stream(compatibility.instructions.toArray()).filter(instruction -> instruction instanceof MethodInsnNode call
                    && call.name.equals("hasConfirmedExperimentalWarning")).map(MethodInsnNode.class::cast).findFirst().orElseThrow();
            JumpInsnNode acknowledged = assertInstanceOf(JumpInsnNode.class, nextCode(check));
            assertEquals(Opcodes.IFNE, acknowledged.getOpcode(), "true acknowledgement must jump to the no-warning branch");
            assertEquals(Opcodes.ICONST_0, nextCode(acknowledged.label).getOpcode());
            assertTrue(Arrays.stream(compatibility.instructions.toArray()).anyMatch(instruction -> instruction instanceof MethodInsnNode call
                    && call.name.equals("askForBackup")), "the prompt itself must remain in production");
        }
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        do instruction = instruction.getNext(); while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
    private static ClassNode read(ZipFile zip, String name) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(zip.getInputStream(zip.getEntry(name + ".class")).readAllBytes()).accept(node, 0);
        return node;
    }
    private static void named(DataOutputStream out, int type, String name) throws Exception { out.writeByte(type); out.writeUTF(name); }
    private static byte[] fixture(int acknowledged, boolean include, int flagType, boolean duplicate) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            named(out, 10, "");
            named(out, 1, FLAG); out.writeByte(0); // A root-level lookalike must remain unchanged.
            named(out, 10, "Data");
            named(out, 8, "forgeLifecycle"); out.writeUTF("stable");
            named(out, 8, "modified-UTF8"); out.writeUTF("embedded\u0000nul and \ud83c\udf0d");
            named(out, 4, "seed"); out.writeLong(1234567890123456L);
            named(out, 3, "DataVersion"); out.writeInt(4903);
            named(out, 2, "short"); out.writeShort(1234);
            named(out, 5, "float"); out.writeFloat(1.25f);
            named(out, 6, "double"); out.writeDouble(3.5);
            named(out, 11, "ints"); out.writeInt(2); out.writeInt(123); out.writeInt(-456);
            named(out, 12, "longs"); out.writeInt(1); out.writeLong(-789);
            ByteArrayOutputStream misleading = new ByteArrayOutputStream();
            try (DataOutputStream fake = new DataOutputStream(misleading)) { named(fake, 1, FLAG); fake.writeByte(0); }
            named(out, 7, "opaque bytes"); out.writeInt(misleading.size()); out.write(misleading.toByteArray());
            named(out, 10, "nested"); named(out, 1, FLAG); out.writeByte(0); out.writeByte(0);
            named(out, 9, "list"); out.writeByte(10); out.writeInt(1); named(out, 1, FLAG); out.writeByte(0); out.writeByte(0);
            named(out, 10, "DataPacks"); named(out, 9, "Enabled"); out.writeByte(8); out.writeInt(2);
            out.writeUTF("vanilla"); out.writeUTF("forbric/carrier/test"); out.writeByte(0);
            if (include) {
                named(out, flagType, FLAG); if (flagType == 1) out.writeByte(acknowledged); else out.writeInt(acknowledged);
                if (duplicate) { named(out, 1, FLAG); out.writeByte(acknowledged); }
            }
            out.writeByte(0); out.writeByte(0);
        }
        return bytes.toByteArray();
    }
    private static byte[] gzip(byte[] raw) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) { gzip.write(raw); }
        return bytes.toByteArray();
    }
    private static byte[] gunzip(byte[] compressed) throws Exception {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) { return gzip.readAllBytes(); }
    }
}
