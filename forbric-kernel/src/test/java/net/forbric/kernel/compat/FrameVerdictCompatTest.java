package net.forbric.kernel.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FrameVerdictCompatTest {
	@TempDir Path temporary;

	@Test void imageIoBlackFrameFailsTheDrawingGate() throws Exception {
		var result = classify(new BufferedImage(128, 72, BufferedImage.TYPE_INT_RGB));
		assertEquals(1, result.exitCode(), result.output());
		assertTrue(result.output().contains("verdict=BLACK"), result.output());
		assertTrue(result.output().contains("colours=1"), result.output());
	}

	@Test void imageIoGradientProvesDrawing() throws Exception {
		BufferedImage image = new BufferedImage(128, 72, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, (x * 2 << 16) | (y * 3 << 8) | x);
		}
		var result = classify(image);
		assertEquals(0, result.exitCode(), result.output());
		assertTrue(result.output().contains("verdict=DREW"), result.output());
	}

	@Test void imageIoIndexedPaletteIsDecodedAsColours() throws Exception {
		byte[] values = {0, 64, (byte) 128, (byte) 255};
		IndexColorModel palette = new IndexColorModel(2, 4, values, values, values);
		BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_BYTE_BINARY, palette);
		for (int y = 0; y < 32; y++) {
			for (int x = 0; x < 32; x++) image.getRaster().setSample(x, y, 0, x % 4);
		}
		var result = classify(image);
		assertEquals(0, result.exitCode(), result.output());
		assertTrue(result.output().contains("verdict=DREW"), result.output());
		assertTrue(result.output().contains("colours=4"), result.output());
	}

	@Test void imageIoGrayscaleIsNotMistakenForRgb() throws Exception {
		BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_USHORT_GRAY);
		for (int y = 0; y < 32; y++) {
			for (int x = 0; x < 32; x++) image.getRaster().setSample(x, y, 0, x * 2048);
		}
		var result = classify(image);
		assertEquals(0, result.exitCode(), result.output());
		assertTrue(result.output().contains("verdict=DREW"), result.output());
	}

	@Test void invisibleRgbaColoursAreNotDrawing() throws Exception {
		BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 32; y++) {
			for (int x = 0; x < 32; x++) image.setRGB(x, y, (x * 8 << 16) | (y * 8 << 8));
		}
		var result = classify(image);
		assertEquals(1, result.exitCode(), result.output());
		assertTrue(result.output().contains("verdict=BLACK"), result.output());
	}

	@Test void garbageAndTruncationAreUnsupportedRatherThanBlackOrDrawn() throws Exception {
		Path garbage = Files.writeString(temporary.resolve("garbage.png"), "not an image");
		var result = CompatProbeProcess.run(temporary, "python3", "frame-verdict.py", garbage.toString());
		assertEquals(2, result.exitCode(), result.output());
		assertTrue(result.output().contains("verdict=UNSUPPORTED"), result.output());
		Path truncated = temporary.resolve("truncated.png");
		ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", truncated.toFile());
		byte[] bytes = Files.readAllBytes(truncated);
		Files.write(truncated, java.util.Arrays.copyOf(bytes, bytes.length - 12));
		var shortResult = CompatProbeProcess.run(temporary, "python3", "frame-verdict.py", truncated.toString());
		assertEquals(2, shortResult.exitCode(), shortResult.output());
		assertTrue(shortResult.output().contains("verdict=UNSUPPORTED"), shortResult.output());
	}

	private CompatProbeProcess.Result classify(BufferedImage image) throws Exception {
		Path png = temporary.resolve("frame.png");
		assertTrue(ImageIO.write(image, "png", png.toFile()));
		return CompatProbeProcess.run(temporary, "python3", "frame-verdict.py", png.toString());
	}
}
