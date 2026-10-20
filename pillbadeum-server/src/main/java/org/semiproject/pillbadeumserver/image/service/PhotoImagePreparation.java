package org.semiproject.pillbadeumserver.image.service;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import org.semiproject.pillbadeumserver.common.ai.OpenAiClient;
import org.semiproject.pillbadeumserver.common.exception.InvalidFileException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** REG-002/006: 선언한 확장자만 믿지 않고 실제 디코딩한 뒤 최대2048px JPEG로 보낸다. EXIF는 전달하지 않는다. */
@Component
public class PhotoImagePreparation {
  public String dataUrl(MultipartFile file) {
    if (file == null || file.isEmpty() || file.getSize() > 10L * 1024 * 1024)
      throw new InvalidFileException("사진은 10MB 이하만 올릴 수 있어요.");
    try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(file.getBytes()))) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw new InvalidFileException("올바른 JPG·PNG·WEBP 사진이 필요합니다.");
      var reader = readers.next();
      try {
        String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("jpeg", "jpg", "png", "webp").contains(format))
          throw new InvalidFileException("지원하지 않는 사진 형식입니다.");
        String expected = format.equals("jpg") ? "jpeg" : format;
        String name =
            java.util.Objects.toString(file.getOriginalFilename(), "")
                .toLowerCase(java.util.Locale.ROOT);
        boolean extensionMatches =
            expected.equals("jpeg")
                ? name.endsWith(".jpg") || name.endsWith(".jpeg")
                : name.endsWith("." + expected);
        if (!extensionMatches || !("image/" + expected).equalsIgnoreCase(file.getContentType()))
          throw new InvalidFileException("사진의 실제 형식과 확장자·Content-Type이 일치해야 합니다.");
        reader.setInput(input, true, true);
        int w = reader.getWidth(0), h = reader.getHeight(0);
        if (w < 1 || h < 1 || (long) w * h > 32_000_000)
          throw new InvalidFileException("사진 해상도가 너무 큽니다.");
        BufferedImage original = reader.read(0);
        double scale = Math.min(1.0, 2048.0 / Math.max(w, h));
        var reduced =
            new BufferedImage(
                Math.max(1, (int) (w * scale)),
                Math.max(1, (int) (h * scale)),
                BufferedImage.TYPE_INT_RGB);
        var graphics = reduced.createGraphics();
        try {
          graphics.setColor(Color.WHITE);
          graphics.fillRect(0, 0, reduced.getWidth(), reduced.getHeight());
          graphics.setRenderingHint(
              RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
          graphics.drawImage(original, 0, 0, reduced.getWidth(), reduced.getHeight(), null);
        } finally {
          graphics.dispose();
          original.flush();
        }
        var output = new ByteArrayOutputStream();
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (var stream = ImageIO.createImageOutputStream(output)) {
          writer.setOutput(stream);
          var params = writer.getDefaultWriteParam();
          params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
          params.setCompressionQuality(.85f);
          writer.write(null, new IIOImage(reduced, null, null), params);
        } finally {
          writer.dispose();
          reduced.flush();
        }
        return OpenAiClient.toDataUrl(output.toByteArray(), "image/jpeg");
      } finally {
        reader.dispose();
      }
    } catch (IOException | IllegalArgumentException exception) {
      throw new InvalidFileException("사진 파일을 읽을 수 없습니다.");
    }
  }
}
