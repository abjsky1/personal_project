package backend.image;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.geometry.Positions;

// 올린 사진을 저장하기 전에 다듬는다. (기획서 6.2절)
// 순서: 1. 진짜 사진인지 검사 → 2. 화소 수 검사 → 3. 방향 바로잡기(회전 보정) → 4. 크기 줄이기 + JPEG로 새로 저장(EXIF 제거)
//
// EXIF: 사진 파일 안에 숨어 있는 정보(촬영 시각, 카메라 기종, GPS 위치, 사진 방향 등)
// → 그대로 공개하면 누구나 사진을 내려받아 작성자의 위치를 알 수 있어서, 공개하는 사진에는 절대 남기지 않는다.
@Slf4j
@Component
public class ImageProcessor {

	// 저장할 큰 사진: 긴 변을 1280px까지 줄인다 (상세 화면용)
	private static final int MAIN_MAX_SIZE = 1280;
	// 목록용 썸네일: 가운데를 잘라 600 × 600 정사각형으로 만든다 (목록 카드가 정사각형이라서)
	private static final int THUMBNAIL_SIZE = 600;
	// JPEG 화질 (0~1). 0.85면 눈으로 차이가 거의 없으면서 용량은 크게 줄어든다.
	private static final double JPEG_QUALITY = 0.85;

	// 파일 맨 앞 몇 바이트(매직 바이트): 파일 형식마다 정해진 "서명" 같은 값
	// 확장자(.jpg)나 브라우저가 알려 준 형식은 바꿔치기할 수 있지만, 파일 내용의 시작 부분은 실제 형식을 보여 준다.
	private static final int[] JPEG_SIGNATURE = {0xFF, 0xD8, 0xFF};
	private static final int[] PNG_SIGNATURE = {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

	// 최대 화소 수 (application.properties의 app.image.max-pixels)
	private final long maxPixels;

	public ImageProcessor(@Value("${app.image.max-pixels}") long maxPixels) {
		this.maxPixels = maxPixels;
	}

	public ProcessedImage process(MultipartFile file) {
		byte[] original = readBytes(file);

		// 1. 파일 내용이 진짜 JPEG·PNG인지 검사한다.
		if (!startsWith(original, JPEG_SIGNATURE) && !startsWith(original, PNG_SIGNATURE)) {
			// 어떤 형식이 얼마나 들어오는지(예: 아이폰 HEIC) 나중에 로그로 세어 볼 수 있게 남긴다.
			log.warn("Unsupported image: contentType={}, bytes={}", file.getContentType(), original.length);
			throw new BusinessException(ErrorCode.INVALID_IMAGE);
		}

		// 2. 사진 전체를 펼치기 전에 가로·세로 크기만 먼저 읽어서, 너무 큰 사진을 거절한다.
		checkPixelCount(original);

		try {
			// 3. 사진을 펼치면서 EXIF의 방향 정보를 반영한다.
			// 휴대폰은 세로 사진도 가로로 저장하고 "90도 돌려서 보여 줘"라는 방향 정보만 EXIF에 적어 둔다.
			// EXIF를 지우기 전에 이 방향대로 실제로 돌려 두지 않으면, 세로 사진이 눕혀져 보인다.
			BufferedImage upright = Thumbnails.of(new ByteArrayInputStream(original)).scale(1.0).asBufferedImage();
			BufferedImage rgb = removeTransparency(upright);

			// 4. 크기를 줄여 JPEG로 새로 저장한다.
			// 그림(픽셀)만 새 파일로 쓰고 EXIF는 옮겨 적지 않으므로, 이 과정에서 위치 정보 등이 모두 사라진다.
			byte[] main = toJpeg(fitWithin(rgb, MAIN_MAX_SIZE));
			byte[] thumbnail = toJpeg(Thumbnails.of(rgb).size(THUMBNAIL_SIZE, THUMBNAIL_SIZE).crop(Positions.CENTER));
			return new ProcessedImage(main, thumbnail);
		} catch (IOException e) {
			// 형식은 맞지만 읽을 수 없는 사진 (손상된 파일, 인쇄용 CMYK JPEG 등)
			log.warn("Unreadable image: {}", e.getMessage());
			throw new BusinessException(ErrorCode.INVALID_IMAGE);
		}
	}

	// 파일 앞부분이 서명과 같은지 비교한다.
	// & 0xFF: 자바의 byte는 -128~127이라 0xFF(255) 같은 값과 비교하려면 0~255로 바꿔야 한다.
	private boolean startsWith(byte[] data, int[] signature) {
		if (data.length < signature.length) {
			return false;
		}
		for (int i = 0; i < signature.length; i++) {
			if ((data[i] & 0xFF) != signature[i]) {
				return false;
			}
		}
		return true;
	}

	// 사진 파일의 머리 부분(헤더)만 읽어 가로 × 세로를 확인한다. 사진 전체를 메모리에 펼치지 않는다.
	private void checkPixelCount(byte[] data) {
		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
			Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext()) {
				throw new BusinessException(ErrorCode.INVALID_IMAGE);
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(input);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				// long으로 곱하는 이유: int끼리 곱하면 21억이 넘을 때 숫자가 깨진다(오버플로).
				if ((long) width * height > maxPixels) {
					log.warn("Image too large: {}x{}", width, height);
					throw new BusinessException(ErrorCode.IMAGE_TOO_LARGE);
				}
			} finally {
				// 사진을 읽는 도구가 잡고 있던 자원을 돌려준다.
				reader.dispose();
			}
		} catch (IOException e) {
			log.warn("Unreadable image header: {}", e.getMessage());
			throw new BusinessException(ErrorCode.INVALID_IMAGE);
		}
	}

	// PNG의 투명한 부분을 흰색으로 채운다. JPEG는 투명을 저장할 수 없어서, 그대로 두면 투명한 곳이 검게 나온다.
	private BufferedImage removeTransparency(BufferedImage image) {
		if (!image.getColorModel().hasAlpha()) {
			return image;
		}
		BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = rgb.createGraphics();
		graphics.setColor(Color.WHITE);
		graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
		graphics.drawImage(image, 0, 0, null);
		graphics.dispose();
		return rgb;
	}

	// 긴 변이 maxSize보다 크면 비율을 유지한 채 줄이고, 원래 작으면 그대로 둔다. (작은 사진을 키우면 흐려지기만 한다)
	private Thumbnails.Builder<BufferedImage> fitWithin(BufferedImage image, int maxSize) {
		if (Math.max(image.getWidth(), image.getHeight()) > maxSize) {
			return Thumbnails.of(image).size(maxSize, maxSize);
		}
		return Thumbnails.of(image).scale(1.0);
	}

	// 정해 둔 크기·자르기 설정대로 JPEG 파일 내용(바이트)을 만든다.
	private byte[] toJpeg(Thumbnails.Builder<BufferedImage> builder) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		builder.outputFormat("jpg").outputQuality(JPEG_QUALITY).toOutputStream(output);
		return output.toByteArray();
	}

	private byte[] readBytes(MultipartFile file) {
		try {
			return file.getBytes();
		} catch (IOException e) {
			throw new UncheckedIOException("업로드 파일 읽기 실패", e);
		}
	}
}
