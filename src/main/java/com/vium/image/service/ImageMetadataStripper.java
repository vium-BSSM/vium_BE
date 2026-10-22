package com.vium.image.service;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Losslessly drops metadata (EXIF GPS, XMP, comments, text chunks, trailing data) from validated images.
 * JPEG EXIF orientation survives as a minimal EXIF segment so phone photos keep their rotation.
 */
@Component
public class ImageMetadataStripper {
	private static final byte[] EXIF = "Exif\0\0".getBytes(ISO_8859_1);
	private static final byte[] JFIF = "JFIF\0".getBytes(ISO_8859_1);
	// "JFIF\0", version, density units, X/Y density, thumbnail width/height.
	private static final int JFIF_HEADER = 14;
	private static final byte[] ICC ="ICC_PROFILE\0".getBytes(ISO_8859_1);
	private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
	private static final Set<String> PNG_RENDERING_CHUNKS =
		Set.of("tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "bKGD", "pHYs");

	public byte[] strip(byte[] bytes, String contentType) {
		try {
			return contentType.equals("image/png") ? stripPng(bytes) : stripJpeg(bytes);
		} catch (IndexOutOfBoundsException e) {
			throw invalid();
		}
	}

	private byte[] stripJpeg(byte[] in) {
		if (u8(in, 0) != 0xFF || u8(in, 1) != 0xD8) throw invalid();
		var out = new ByteArrayOutputStream(in.length);
		out.write(in, 0, 2);
		boolean orientationWritten = false;
		int i = 2;
		while (true) {
			if (u8(in, i) != 0xFF) throw invalid();
			while (u8(in, i) == 0xFF) i++;
			int marker = u8(in, i++);
			if (marker == 0xD9) {
				out.write(0xFF); out.write(0xD9);
				return out.toByteArray();
			}
			if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
				out.write(0xFF); out.write(marker);
				continue;
			}
			int length = u16(in, i, false);
			int end = i + length;
			if (length < 2 || end > in.length) throw invalid();
			int payload = i + 2;
			if (marker == 0xE1 && startsWith(in, payload, end, EXIF)) {
				int orientation = orientation(in, payload + EXIF.length, end);
				if (!orientationWritten && orientation > 1) {
					out.writeBytes(orientationSegment(orientation));
					orientationWritten = true;
				}
			} else if (marker == 0xE0) {
				// JFIF header only: drop its RGB thumbnail and JFXX extensions, which embed whole JPEGs.
				if (end - payload >= JFIF_HEADER && startsWith(in, payload, end, JFIF)) {
					out.write(0xFF); out.write(0xE0); out.write(0); out.write(JFIF_HEADER + 2);
					out.write(in, payload, JFIF_HEADER - 2);
					out.write(0); out.write(0);
				}
			} else if (keepJpegSegment(marker, in, payload, end)) {
				out.write(0xFF); out.write(marker);
				out.write(in, i, length);
			}
			i = end;
			if (marker == 0xDA) {
				int scanEnd = scanEnd(in, i);
				out.write(in, i, scanEnd - i);
				i = scanEnd;
			}
		}
	}

	// APPn and COM carry metadata; keep only ICC color profiles and Adobe color transform.
	private static boolean keepJpegSegment(int marker, byte[] in, int payload, int end) {
		if (marker == 0xFE) return false;
		if (marker < 0xE0 || marker > 0xEF) return true;
		return marker == 0xEE || (marker == 0xE2 && startsWith(in, payload, end, ICC));
	}

	// Entropy-coded data ends at the first marker that is not byte stuffing or a restart marker.
	private static int scanEnd(byte[] in, int i) {
		while (true) {
			if (u8(in, i) != 0xFF) { i++; continue; }
			int next = u8(in, i + 1);
			if (next == 0x00 || (next >= 0xD0 && next <= 0xD7)) i += 2;
			else return i;
		}
	}

	private static int orientation(byte[] in, int tiff, int end) {
		if (tiff + 8 > end) return 1;
		boolean little = in[tiff] == 'I' && in[tiff + 1] == 'I';
		if (!little && !(in[tiff] == 'M' && in[tiff + 1] == 'M')) return 1;
		long ifd = tiff + u32(in, tiff + 4, little);
		if (ifd + 2 > end) return 1;
		int entries = u16(in, (int) ifd, little);
		for (int k = 0; k < entries; k++) {
			int entry = (int) ifd + 2 + 12 * k;
			if (entry + 12 > end) return 1;
			if (u16(in, entry, little) == 0x0112 && u16(in, entry + 2, little) == 3) {
				int value = u16(in, entry + 8, little);
				return value >= 1 && value <= 8 ? value : 1;
			}
		}
		return 1;
	}

	// Big-endian TIFF with a single IFD0 entry: Orientation (0x0112), SHORT, count 1.
	private static byte[] orientationSegment(int orientation) {
		return new byte[] {
			(byte) 0xFF, (byte) 0xE1, 0, 34,
			'E', 'x', 'i', 'f', 0, 0,
			'M', 'M', 0, 42, 0, 0, 0, 8,
			0, 1,
			1, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0,
			0, 0, 0, 0
		};
	}

	private byte[] stripPng(byte[] in) {
		if (!Arrays.equals(in, 0, PNG_SIGNATURE.length, PNG_SIGNATURE, 0, PNG_SIGNATURE.length)) throw invalid();
		var out = new ByteArrayOutputStream(in.length);
		out.write(in, 0, PNG_SIGNATURE.length);
		int i = PNG_SIGNATURE.length;
		while (true) {
			long length = u32(in, i, false);
			String type = new String(in, i + 4, 4, ISO_8859_1);
			long end = i + 12 + length;
			if (end > in.length) throw invalid();
			// Critical chunks (uppercase first letter) are required to decode; text/eXIf/tIME are dropped.
			if (Character.isUpperCase(type.charAt(0)) || PNG_RENDERING_CHUNKS.contains(type))
				out.write(in, i, (int) end - i);
			i = (int) end;
			if (type.equals("IEND")) return out.toByteArray();
		}
	}

	private static boolean startsWith(byte[] in, int from, int end, byte[] prefix) {
		return end - from >= prefix.length && Arrays.equals(in, from, from + prefix.length, prefix, 0, prefix.length);
	}

	private static int u8(byte[] in, int i) { return in[i] & 0xFF; }

	private static int u16(byte[] in, int i, boolean little) {
		return little ? u8(in, i) | u8(in, i + 1) << 8 : u8(in, i) << 8 | u8(in, i + 1);
	}

	private static long u32(byte[] in, int i, boolean little) {
		return little ? (u16(in, i, true) | (long) u16(in, i + 2, true) << 16)
			: ((long) u16(in, i, false) << 16 | u16(in, i + 2, false));
	}

	private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_IMAGE); }
}
