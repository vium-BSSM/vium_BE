package com.vium.image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

public class ImageTestFiles {
	public static byte[] image(String format, int width, int height) throws Exception {
		var output = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, output);
		return output.toByteArray();
	}
}
