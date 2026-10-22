package com.vium.image;

import static org.assertj.core.api.Assertions.*;
import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.image.service.ImageValidator;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;

class ImageValidatorTest {
	private final ImageValidator validator = new ImageValidator();

	@ParameterizedTest @ValueSource(strings = {"png", "jpeg"})
	void validatesActualImageAndIgnoresUntrustedFilename(String format) throws Exception {
		var result = validator.validate(file("image/" + format, ImageTestFiles.image(format, 8, 8)));
		assertThat(result.contentType()).isEqualTo("image/" + format);
		assertThat(result.extension()).isEqualTo(format.equals("jpeg") ? "jpg" : "png");
	}

	@Test void rejectsMimeMismatch() throws Exception {
		reject("image/png", ImageTestFiles.image("jpeg", 2, 2), ErrorCode.UNSUPPORTED_IMAGE_TYPE);
	}
	@Test void rejectsGif() throws Exception {
		reject("image/gif", ImageTestFiles.image("gif", 2, 2), ErrorCode.UNSUPPORTED_IMAGE_TYPE);
	}
	@Test void rejectsFakeImage() {
		reject("image/png", "<script>alert(1)</script>".getBytes(), ErrorCode.INVALID_IMAGE);
	}
	@Test void rejectsEmpty() { reject("image/png", new byte[0], ErrorCode.INVALID_IMAGE); }
	@Test void rejectsOversized() { reject("image/png", new byte[ImageValidator.MAX_BYTES+1], ErrorCode.IMAGE_TOO_LARGE); }
	@Test void rejectsDimensionsBeforeDecoding() throws Exception {
		reject("image/png", ImageTestFiles.image("png", 4097, 1), ErrorCode.INVALID_IMAGE);
	}
	@Test void acceptsDimensionBoundary() throws Exception {
		validator.validate(file("image/png", ImageTestFiles.image("png", 4096, 1)));
	}
	@ParameterizedTest @ValueSource(strings = {"png", "jpeg"})
	void rejectsTruncatedImage(String format) throws Exception {
		byte[] bytes = ImageTestFiles.image(format, 20, 20);
		reject("image/" + format, Arrays.copyOf(bytes, bytes.length / 2), ErrorCode.INVALID_IMAGE);
	}
	@Test void acceptsExactSizeLimit() throws Exception {
		var bytes = Arrays.copyOf(ImageTestFiles.image("png", 2, 2), ImageValidator.MAX_BYTES);
		assertThat(validator.validate(file("image/png", bytes)).bytes()).hasSize(ImageValidator.MAX_BYTES);
	}
	private MockMultipartFile file(String mime, byte[] bytes) {
		return new MockMultipartFile("file", "../../untrusted.exe", mime, bytes);
	}
	private void reject(String mime, byte[] bytes, ErrorCode code) {
		assertThatThrownBy(() -> validator.validate(file(mime, bytes))).isInstanceOfSatisfying(
			BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
	}
}
