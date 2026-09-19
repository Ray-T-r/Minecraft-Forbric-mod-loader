package net.forbric.kernel.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionProbeCompatTest {
	@TempDir Path temporary;

	@Test void gzipAndZlibChunksCountWhileLz4IsExplicitlyUnreadable() throws Exception {
		byte[] zlib = compressed(2, nbt("minecraft:purpur_block minecraft:spawner minecraft:mossy_cobblestone minecraft:zombie"));
		byte[] gzip = compressed(1, nbt("minecraft:end_stone"));
		region(new int[] {2, 1, 4}, new byte[][] {zlib, gzip, nbt("minecraft:purpur_block")});
		var result = run("minecraft:purpur_block", "minecraft:end_stone", "--dungeons");
		assertEquals(0, result.exitCode(), result.output());
		assertTrue(result.output().contains("chunks read: 2"), result.output());
		assertTrue(result.output().contains("minecraft:purpur_block: 1"), result.output());
		assertTrue(result.output().contains("minecraft:end_stone: 1"), result.output());
		assertTrue(result.output().contains("unreadable: 1 (lz4=1, custom=0, corrupt=0)"), result.output());
		assertTrue(result.output().contains("dungeons: 1"), result.output());
		assertTrue(result.output().contains("spawner chunk naming minecraft:zombie: 1"), result.output());
	}

	@Test void corruptAndCustomChunksNeverInflateReadableCount() throws Exception {
		region(new int[] {2, 127, 3}, new byte[][] {
				new byte[] {1, 2, 3}, nbt("minecraft:purpur_block"), nbt("minecraft:end_stone")});
		var result = run("minecraft:purpur_block", "minecraft:end_stone");
		assertEquals(0, result.exitCode(), result.output());
		assertTrue(result.output().contains("chunks read: 1"), result.output());
		assertTrue(result.output().contains("minecraft:purpur_block: 0"), result.output());
		assertTrue(result.output().contains("minecraft:end_stone: 1"), result.output());
		assertTrue(result.output().contains("unreadable: 2 (lz4=0, custom=1, corrupt=1)"), result.output());
	}

	@Test void truncatedRegionIsReportedInsteadOfSilentlySkipped() throws Exception {
		Files.write(temporary.resolve("r.0.0.mca"), new byte[] {0, 0, 0});
		var result = run("minecraft:spawner");
		assertEquals(0, result.exitCode(), result.output());
		assertTrue(result.output().contains("chunks read: 0"), result.output());
		assertTrue(result.output().contains("unreadable: 1 (lz4=0, custom=0, corrupt=1)"), result.output());
	}

	private CompatProbeProcess.Result run(String... arguments) throws Exception {
		String[] all = new String[arguments.length + 1];
		all[0] = temporary.toString();
		System.arraycopy(arguments, 0, all, 1, arguments.length);
		return CompatProbeProcess.run(temporary, "python3", "region-probe.py", all);
	}

	private void region(int[] types, byte[][] blobs) throws Exception {
		ByteBuffer data = ByteBuffer.allocate(4096 * (types.length + 2));
		for (int slot = 0; slot < types.length; slot++) {
			data.putInt(slot * 4, ((slot + 2) << 8) | 1);
			data.position((slot + 2) * 4096);
			data.putInt(blobs[slot].length + 1).put((byte) types[slot]).put(blobs[slot]);
		}
		Files.write(temporary.resolve("r.0.0.mca"), data.array());
	}

	private static byte[] nbt(String values) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeByte(10); out.writeUTF(""); // Root compound.
			out.writeByte(8); out.writeUTF("fixture"); out.writeUTF(values);
			out.writeByte(0);
		}
		return bytes.toByteArray();
	}

	private static byte[] compressed(int type, byte[] raw) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (var stream = type == 1 ? new GZIPOutputStream(bytes) : new DeflaterOutputStream(bytes)) {
			stream.write(raw);
		}
		return bytes.toByteArray();
	}
}
