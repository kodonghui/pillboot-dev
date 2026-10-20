package org.semiproject.pillbadeumserver.image;

import static org.assertj.core.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.exception.InvalidFileException;
import org.semiproject.pillbadeumserver.image.service.PhotoImagePreparation;
import org.springframework.mock.web.MockMultipartFile;

class PhotoImagePreparationTests {
  final PhotoImagePreparation preparation = new PhotoImagePreparation();

  @Test
  void realImageIsResizedAndSentAsJpeg() throws Exception {
    var file = image("png", 3000, 1500);
    String result = preparation.dataUrl(file);
    assertThat(result).startsWith("data:image/jpeg;base64,");
    var decoded =
        ImageIO.read(
            new ByteArrayInputStream(
                Base64.getDecoder().decode(result.substring(result.indexOf(',') + 1))));
    assertThat(decoded.getWidth()).isEqualTo(2048);
    assertThat(decoded.getHeight()).isEqualTo(1024);
  }

  @Test
  void declaredFormatMustMatchDecodedBytes() throws Exception {
    var original = image("png", 10, 10);
    assertThatThrownBy(
            () ->
                preparation.dataUrl(
                    new MockMultipartFile("image", "wrong.jpg", "image/jpeg", original.getBytes())))
        .isInstanceOf(InvalidFileException.class);
    assertThatThrownBy(
            () ->
                preparation.dataUrl(
                    new MockMultipartFile("image", "wrong.png", "image/gif", original.getBytes())))
        .isInstanceOf(InvalidFileException.class);
    assertThatThrownBy(
            () ->
                preparation.dataUrl(
                    new MockMultipartFile("image", "fake.png", "image/png", new byte[] {1, 2, 3})))
        .isInstanceOf(InvalidFileException.class);
  }

  @Test
  void realWebpIsDecodedWithRegisteredImageIoReader() {
    // 본 테스트용 2x2 흰 픽셀 WEBP이며 사용자 사진이 아님.
    byte[] bytes =
        Base64.getDecoder().decode("UklGRiQAAABXRUJQVlA4IBgAAAAwAQCdASoCAAIAAUAmJaQAA3AA/vz0AAA=");
    assertThat(
            preparation.dataUrl(new MockMultipartFile("image", "test.webp", "image/webp", bytes)))
        .startsWith("data:image/jpeg;base64,");
  }

  @Test
  void emptyAndOversizedFilesAreRejectedBeforeDecode() {
    assertThatThrownBy(() -> preparation.dataUrl(new MockMultipartFile("image", new byte[0])))
        .isInstanceOf(InvalidFileException.class);
    assertThatThrownBy(
            () ->
                preparation.dataUrl(
                    new MockMultipartFile(
                        "image", "large.png", "image/png", new byte[10 * 1024 * 1024 + 1])))
        .isInstanceOf(InvalidFileException.class);
  }

  static MockMultipartFile image(String format, int w, int h) throws IOException {
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), format, out);
    return new MockMultipartFile("image", "test." + format, "image/" + format, out.toByteArray());
  }
}
