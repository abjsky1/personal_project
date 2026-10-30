package backend.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;

// ImageProcessor 하나만 떼어서 검사하는 테스트 (단위 테스트 — Spring·DB 없이 빠르게 돈다)
class ImageProcessorTest {

	// 실제 설정과 같은 한도(5,000만 화소)
	private final ImageProcessor processor = new ImageProcessor(50_000_000);

	@Test
	void 큰_사진은_긴_변을_1280으로_줄이고_썸네일은_600_정사각형으로_만든다() throws IOException {
		ProcessedImage result = processor.process(upload(TestImages.jpeg(2000, 1000)));

		// 비율(2:1)을 유지한 채 긴 변이 1280
		assertSize(result.main(), 1280, 640);
		// 썸네일은 가운데를 잘라 정사각형
		assertSize(result.thumbnail(), 600, 600);
	}

	@Test
	void 작은_사진은_키우지_않는다() throws IOException {
		ProcessedImage result = processor.process(upload(TestImages.jpeg(800, 600)));

		assertSize(result.main(), 800, 600);
	}

	@Test
	void EXIF_방향_정보대로_사진을_세운다() throws IOException {
		// 가로 200 × 세로 100으로 저장됐지만 "90도 돌려서 보여 줘"(6)가 붙은 사진 = 실제로는 세로 사진
		byte[] rotated = TestImages.withExifOrientation(TestImages.jpeg(200, 100), 6);

		ProcessedImage result = processor.process(upload(rotated));

		// 실제로 돌려서 저장했으므로 세로 사진(100 × 200)이 된다.
		assertSize(result.main(), 100, 200);
	}

	@Test
	void 저장할_사진에는_EXIF가_남지_않는다() {
		byte[] withExif = TestImages.withExifOrientation(TestImages.jpeg(200, 100), 6);
		// 처리 전에는 EXIF가 들어 있다 (테스트용 사진이 제대로 만들어졌는지 먼저 확인)
		assertThat(TestImages.containsExif(withExif)).isTrue();

		ProcessedImage result = processor.process(upload(withExif));

		// 큰 사진과 썸네일 모두 EXIF가 없어야 한다 → 위치 정보 등이 공개되지 않는다.
		assertThat(TestImages.containsExif(result.main())).isFalse();
		assertThat(TestImages.containsExif(result.thumbnail())).isFalse();
	}

	@Test
	void 원본의_EXIF에서_GPS_위치와_촬영_시각을_읽는다() {
		// 서울시청 근처 좌표, 한국 시간 2026-10-29 12:30에 찍은 사진
		byte[] photo = TestImages.withGps(TestImages.jpeg(200, 100), 37.566535, 126.977969, "2026:10:29 12:30:00");

		PhotoMetadata metadata = processor.process(upload(photo)).metadata();

		// isCloseTo: 도·분·초로 바꿨다가 되돌리는 과정의 아주 작은 오차는 허용한다.
		assertThat(metadata.latitude()).isCloseTo(37.566535, within(0.000001));
		assertThat(metadata.longitude()).isCloseTo(126.977969, within(0.000001));
		// 한국 시간 12:30 = 세계 표준시(UTC) 03:30
		assertThat(metadata.takenAt()).isEqualTo(Instant.parse("2026-10-29T03:30:00Z"));
	}

	@Test
	void GPS가_있던_사진도_저장할_파일에는_위치가_남지_않는다() {
		byte[] photo = TestImages.withGps(TestImages.jpeg(200, 100), 37.566535, 126.977969, "2026:10:29 12:30:00");
		assertThat(TestImages.containsExif(photo)).isTrue();

		ProcessedImage result = processor.process(upload(photo));

		// 위치는 metadata로 따로 꺼내 두었고, 공개되는 파일 두 개에는 EXIF가 없다.
		assertThat(result.metadata().hasLocation()).isTrue();
		assertThat(TestImages.containsExif(result.main())).isFalse();
		assertThat(TestImages.containsExif(result.thumbnail())).isFalse();
	}

	@Test
	void EXIF가_없는_사진은_위치와_촬영_시각이_비어_있다() {
		PhotoMetadata metadata = processor.process(upload(TestImages.jpeg(200, 100))).metadata();

		assertThat(metadata.hasLocation()).isFalse();
		assertThat(metadata.takenAt()).isNull();
	}

	@Test
	void 투명한_PNG는_흰_배경의_JPEG로_바꾼다() throws IOException {
		ProcessedImage result = processor.process(upload(TestImages.transparentPng(100, 100)));

		BufferedImage image = read(result.main());
		// JPEG 파일은 FF D8로 시작한다.
		assertThat(result.main()[0] & 0xFF).isEqualTo(0xFF);
		assertThat(result.main()[1] & 0xFF).isEqualTo(0xD8);
		// 투명했던 곳이 검은색이 아니라 흰색(에 가까운 색)이어야 한다. (JPEG 압축 때문에 정확히 255가 아닐 수 있다)
		int rgb = image.getRGB(50, 50);
		assertThat((rgb >> 16) & 0xFF).isGreaterThan(240);
	}

	@Test
	void 사진이_아닌_파일은_INVALID_IMAGE로_거절한다() {
		// 형식은 image/jpeg라고 알려 왔지만 내용은 사진이 아니다 (확장자만 바꾼 파일 같은 경우)
		MockMultipartFile fake = new MockMultipartFile("image", "fake.jpg", "image/jpeg", "not an image".getBytes());

		assertThatThrownBy(() -> processor.process(fake))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.INVALID_IMAGE);
	}

	@Test
	void 화소_수가_한도를_넘으면_IMAGE_TOO_LARGE로_거절한다() {
		// 한도를 1만 화소로 낮춘 처리기로, 2만 화소(200 × 100) 사진을 올린다.
		ImageProcessor strict = new ImageProcessor(10_000);

		assertThatThrownBy(() -> strict.process(upload(TestImages.jpeg(200, 100))))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.IMAGE_TOO_LARGE);
	}

	private MockMultipartFile upload(byte[] data) {
		return new MockMultipartFile("image", "photo.jpg", "image/jpeg", data);
	}

	private BufferedImage read(byte[] data) throws IOException {
		return ImageIO.read(new ByteArrayInputStream(data));
	}

	private void assertSize(byte[] data, int width, int height) throws IOException {
		BufferedImage image = read(data);
		assertThat(image.getWidth()).isEqualTo(width);
		assertThat(image.getHeight()).isEqualTo(height);
	}
}
