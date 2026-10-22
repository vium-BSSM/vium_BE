package com.vium.image.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class ImageValidator {
	public static final int MAX_BYTES = 5 * 1024 * 1024;
	public record Validated(byte[] bytes, String contentType, String extension) {}

	public Validated validate(MultipartFile file) {
		if (file == null || file.isEmpty()) throw new BusinessException(ErrorCode.INVALID_IMAGE);
		if (file.getSize() > MAX_BYTES) throw new BusinessException(ErrorCode.IMAGE_TOO_LARGE);
		if (!"image/jpeg".equals(file.getContentType()) && !"image/png".equals(file.getContentType()))
			throw new BusinessException(ErrorCode.UNSUPPORTED_IMAGE_TYPE);
		try (var in = file.getInputStream()) {
			byte[] bytes = in.readNBytes(MAX_BYTES + 1);
			if (bytes.length > MAX_BYTES) throw new BusinessException(ErrorCode.IMAGE_TOO_LARGE);
			try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
				var readers = ImageIO.getImageReaders(input);
				if (!readers.hasNext()) throw new BusinessException(ErrorCode.INVALID_IMAGE);
				var reader = readers.next();
				try {
					String format = reader.getFormatName();
					String contentType = format.equalsIgnoreCase("JPEG") ? "image/jpeg"
						: format.equalsIgnoreCase("PNG") ? "image/png" : null;
					if (contentType == null || !contentType.equals(file.getContentType()))
						throw new BusinessException(ErrorCode.UNSUPPORTED_IMAGE_TYPE);
					reader.setInput(input, true, true);
					int width = reader.getWidth(0), height = reader.getHeight(0);
					if (width < 1 || height < 1 || width > 4096 || height > 4096)
						throw new BusinessException(ErrorCode.INVALID_IMAGE);
					// JPEG readers can recover truncated files; warnings must reject those too.
					boolean[] warning = {false};
					reader.addIIOReadWarningListener((source, message) -> warning[0] = true);
					var decoded = reader.read(0);
					if (decoded == null || warning[0]) throw new BusinessException(ErrorCode.INVALID_IMAGE);
					decoded.flush();
					return new Validated(bytes, contentType, contentType.equals("image/png") ? "png" : "jpg");
				} finally { reader.dispose(); }
			}
		} catch (IOException | IllegalArgumentException e) {
			throw new BusinessException(ErrorCode.INVALID_IMAGE);
		}
	}
}
