package com.vium.image;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.*;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.image.service.ImageMetadataStripper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ImageMetadataStripperTest {
	private final ImageMetadataStripper stripper = new ImageMetadataStripper();

	@ParameterizedTest @ValueSource(strings = {"png", "jpeg"})
	void leavesCleanImagesUntouched(String format) throws Exception {
		byte[] image = ImageTestFiles.image(format, 8, 8);
		assertThat(stripper.strip(image, "image/" + format)).isEqualTo(image);
	}

	@ParameterizedTest @ValueSource(booleans = {true, false})
	void dropsJpegMetadataButKeepsOrientation(boolean littleEndian) throws Exception {
		byte[] clean = ImageTestFiles.image("jpeg", 8, 8);
		byte[] dirty = concat(insertAfterApp0(clean, concat(
				segment(0xE1, exif(littleEndian, 6)),
				segment(0xE1, "http://ns.adobe.com/xap/1.0/\0SECRET-XMP".getBytes(ISO_8859_1)),
				segment(0xE2, "MPF\0SECRET-MPF".getBytes(ISO_8859_1)),
				segment(0xFE, "SECRET-COMMENT".getBytes(ISO_8859_1)))),
			"SECRET-TRAILER".getBytes(ISO_8859_1));

		byte[] stripped = stripper.strip(dirty, "image/jpeg");

		assertThat(new String(stripped, ISO_8859_1)).doesNotContain("SECRET");
		assertThat(stripped).hasSize(clean.length + 36);
		assertThat(indexOf(stripped, new byte[] {0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6})).isPositive();
		assertThat(ImageIO.read(new ByteArrayInputStream(stripped))).isNotNull();
	}

	@Test void dropsDefaultOrientationEntirely() throws Exception {
		byte[] clean = ImageTestFiles.image("jpeg", 8, 8);
		byte[] dirty = insertAfterApp0(clean, segment(0xE1, exif(false, 1)));
		assertThat(stripper.strip(dirty, "image/jpeg")).isEqualTo(clean);
	}

	@Test void keepsIccProfile() throws Exception {
		byte[] withIcc = insertAfterApp0(ImageTestFiles.image("jpeg", 8, 8),
			segment(0xE2, "ICC_PROFILE\0\1\1profile".getBytes(ISO_8859_1)));
		assertThat(stripper.strip(withIcc, "image/jpeg")).isEqualTo(withIcc);
	}

	@Test void dropsPngTextChunksAndTrailingData() throws Exception {
		byte[] clean = ImageTestFiles.image("png", 8, 8);
		byte[] dirty = concat(Arrays.copyOf(clean, 33),
			chunk("tEXt", "Comment\0SECRET-TEXT"), chunk("eXIf", "MM\0*SECRET-EXIF"), chunk("pHYs", "\0\0\0\1\0\0\0\1\0"),
			Arrays.copyOfRange(clean, 33, clean.length), "SECRET-TRAILER".getBytes(ISO_8859_1));

		byte[] stripped = stripper.strip(dirty, "image/png");

		assertThat(new String(stripped, ISO_8859_1)).doesNotContain("SECRET").contains("pHYs");
		assertThat(ImageIO.read(new ByteArrayInputStream(stripped))).isNotNull();
	}

	@ParameterizedTest @ValueSource(strings = {"png", "jpeg"})
	void rejectsBrokenStructure(String format) throws Exception {
		byte[] image = ImageTestFiles.image(format, 8, 8);
		assertThatThrownBy(() -> stripper.strip(Arrays.copyOf(image, image.length - 4), "image/" + format))
			.isInstanceOfSatisfying(BusinessException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_IMAGE));
	}

	private static byte[] exif(boolean little, int orientation) {
		var order = little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
		byte[] secret = "SECRET-GPS".getBytes(ISO_8859_1);
		var tiff = ByteBuffer.allocate(8 + 2 + 24 + 4 + secret.length).order(order);
		tiff.put(little ? (byte) 'I' : (byte) 'M').put(little ? (byte) 'I' : (byte) 'M').putShort((short) 42).putInt(8);
		tiff.putShort((short) 2);
		tiff.putShort((short) 0x010F).putShort((short) 2).putInt(secret.length).putInt(38);
		tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
		tiff.putInt(0).put(secret);
		return concat("Exif\0\0".getBytes(ISO_8859_1), tiff.array());
	}

	private static byte[] segment(int marker, byte[] payload) {
		int length = payload.length + 2;
		return concat(new byte[] {(byte) 0xFF, (byte) marker, (byte) (length >> 8), (byte) length}, payload);
	}

	private static byte[] insertAfterApp0(byte[] jpeg, byte[] segments) {
		int app0End = 4 + ((jpeg[4] & 0xFF) << 8 | (jpeg[5] & 0xFF));
		return concat(Arrays.copyOf(jpeg, app0End), segments, Arrays.copyOfRange(jpeg, app0End, jpeg.length));
	}

	private static byte[] chunk(String type, String data) {
		byte[] typeAndData = concat(type.getBytes(ISO_8859_1), data.getBytes(ISO_8859_1));
		var crc = new CRC32();
		crc.update(typeAndData);
		return concat(ByteBuffer.allocate(4).putInt(data.length()).array(), typeAndData,
			ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
	}

	private static byte[] concat(byte[]... parts) {
		var out = new ByteArrayOutputStream();
		for (byte[] part : parts) out.writeBytes(part);
		return out.toByteArray();
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		for (int i = 0; i + needle.length <= haystack.length; i++)
			if (Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) return i;
		return -1;
	}
}
