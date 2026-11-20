package backend.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;

// ImageProcessor 하나만 떼어서 검사하는 테스트 (단위 테스트 — Spring, DB 없이 빠르게 돈다)
class ImageProcessorTest {

	// 실제 설정과 같은 한도 (5,000만 화소, 펼친 크기 1억 5천만 바이트)
	private final ImageProcessor processor = new ImageProcessor(50_000_000, 150_000_000);

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
	void 긴_변이_2560보다_긴_사진은_펼칠_때_2560으로_줄여서_세운다() throws IOException {
		// 가로 2600 × 세로 1950으로 저장된 세로 사진(방향 6). 원래 크기로 펼치지 않고 읽으면서 줄인다. (JM-94)
		byte[] photo = TestImages.withExifOrientation(TestImages.jpeg(2600, 1950), 6);

		BufferedImage upright = processor.readUpright(photo, 2600);

		// 긴 변 2600 → 2560으로 줄인 뒤(2560 × 1920) 세웠으므로 1920 × 2560
		assertThat(upright.getWidth()).isEqualTo(1920);
		assertThat(upright.getHeight()).isEqualTo(2560);
	}

	@Test
	void 긴_변이_2560_이하인_사진은_원래_크기로_펼친다() throws IOException {
		BufferedImage upright = processor.readUpright(TestImages.jpeg(2000, 1500), 2000);

		// 작은 사진을 2560까지 키우지 않는다
		assertThat(upright.getWidth()).isEqualTo(2000);
		assertThat(upright.getHeight()).isEqualTo(1500);
	}

	@Test
	void 올린_사진은_헤더에서_읽은_긴_변으로_줄여_읽기를_거친다() throws IOException {
		// process가 줄여 읽기(readUpright)를 건너뛰고 원래 크기로 펼치게 되돌아가면 이 테스트가 실패한다.
		// spy: 진짜 처리기를 그대로 쓰면서, 어떤 메서드가 어떤 값으로 불렸는지 기록해 두는 감시용 사본
		ImageProcessor watched = spy(processor);
		byte[] photo = TestImages.withExifOrientation(TestImages.jpeg(2600, 1950), 6);

		watched.process(upload(photo));

		// 짧은 변(1950)이 아니라 긴 변(2600)을 넘겨야 2560보다 긴 사진으로 보고 줄여 읽는다
		verify(watched).readUpright(any(), eq(2600));
	}

	@Test
	void 줄여_읽은_큰_세로_사진도_세워서_1280으로_저장한다() throws IOException {
		byte[] photo = TestImages.withExifOrientation(TestImages.jpeg(2600, 1950), 6);

		ProcessedImage result = processor.process(upload(photo));

		// 2560 × 1920으로 읽고 세운 그림(1920 × 2560)을 긴 변 1280으로 줄인다
		assertSize(result.main(), 960, 1280);
		assertSize(result.thumbnail(), 600, 600);
		assertThat(TestImages.containsExif(result.main())).isFalse();
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

		// isCloseTo: 도, 분, 초로 바꿨다가 되돌리는 과정의 아주 작은 오차는 허용한다.
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
	void 긴_변이_2560보다_긴_투명한_PNG도_흰_배경으로_줄여_저장한다() throws IOException {
		// 줄여 읽는 길(size)에서도 투명한 부분이 검게 바뀌지 않는지 본다
		ProcessedImage result = processor.process(upload(TestImages.transparentPng(2600, 1300)));

		BufferedImage image = read(result.main());
		assertThat(image.getWidth()).isEqualTo(1280);
		assertThat(image.getHeight()).isEqualTo(640);
		int rgb = image.getRGB(640, 320);
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
		ImageProcessor strict = new ImageProcessor(10_000, 150_000_000);

		assertThatThrownBy(() -> strict.process(upload(TestImages.jpeg(200, 100))))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.IMAGE_TOO_LARGE);
	}

	// ---- 펼친 크기 검사 (JM-105) ----

	@Test
	void 화소는_한도_안이어도_펼치면_너무_큰_PNG는_펼치지_않고_거절한다() throws IOException {
		// 7071 × 7071 = 4,999만 화소라 화소 한도는 지나가지만, 투명 PNG는 화소당 4바이트라 펼치면 약 2억 바이트다.
		// 머리만 있고 그림 데이터가 없는 파일이라, 펼치려 했다면 INVALID_IMAGE가 났을 것이다.
		ImageProcessor watched = spy(processor);
		byte[] png = TestImages.pngHeaderOnly(7071, 7071, 8, 6);

		assertThatThrownBy(() -> watched.process(upload(png)))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.IMAGE_DECODED_TOO_LARGE);
		// 펼치기(readUpright)는 부르지도 않았다
		verify(watched, never()).readUpright(any(), anyInt());
	}

	@Test
	void 화소_수가_같아도_JPEG는_화소당_3바이트_투명_PNG는_4바이트로_센다() throws IOException {
		// 200 × 200 = 4만 화소. 펼친 크기가 JPEG는 12만 바이트, 투명 PNG는 16만 바이트 → 한도를 15만 바이트로 낮춰 둘을 가른다
		ImageProcessor strict = new ImageProcessor(50_000_000, 150_000);

		assertSize(strict.process(upload(TestImages.jpeg(200, 200))).main(), 200, 200);
		assertThatThrownBy(() -> strict.process(upload(TestImages.transparentPng(200, 200))))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.IMAGE_DECODED_TOO_LARGE);
	}

	@Test
	void 투명색이_있는_RGB_PNG는_자바가_펼치는_대로_화소당_4바이트로_센다() throws IOException {
		// 100 × 100 = 1만 화소. 투명색(tRNS)이 없으면 3바이트(3만 바이트), 있으면 4바이트(4만 바이트)로 펼쳐진다 → 한도 3만 5천 바이트
		// 펼치는 형식이 아니라 파일의 원래 형식(getRawImageType, RGB 3바이트)으로 세면 이 테스트가 실패한다
		ImageProcessor strict = new ImageProcessor(50_000_000, 35_000);
		byte[] rgb = TestImages.rgbPng(100, 100);

		assertSize(strict.process(upload(rgb)).main(), 100, 100);
		assertThatThrownBy(() -> strict.process(upload(TestImages.withTransparentColor(rgb))))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.IMAGE_DECODED_TOO_LARGE);
	}

	@Test
	void 자바_그리기가_바로_줄이지_못하는_형식은_복사본_몫_8바이트를_더_센다() throws IOException {
		// 100 × 100 = 1만 화소, 한도 5만 바이트
		ImageProcessor strict = new ImageProcessor(50_000_000, 50_000);

		// 바로 줄이는 형식: 투명 PNG 4바이트(4만), 8비트 팔레트 PNG 1바이트(1만) → 받는다
		assertSize(strict.process(upload(TestImages.transparentPng(100, 100))).main(), 100, 100);
		assertSize(strict.process(upload(TestImages.palettePng(100, 100))).main(), 100, 100);
		// 바로 줄이지 못하는 형식: 16비트 투명 PNG 8 + 8(16만), 회색+투명 PNG 2 + 8(10만), 1비트 팔레트 PNG 1 + 8(9만) → 거절
		for (byte[] png : new byte[][] {TestImages.transparentPng16(100, 100), TestImages.grayAlphaPng(100, 100),
				TestImages.onebitPalettePng(100, 100)}) {
			assertThatThrownBy(() -> strict.process(upload(png)))
					.isInstanceOf(BusinessException.class)
					.extracting(e -> ((BusinessException) e).getErrorCode())
					.isEqualTo(ErrorCode.IMAGE_DECODED_TOO_LARGE);
		}
	}

	@Test
	void 한_변이_65535보다_긴_사진은_펼치지_않고_거절한다() {
		// 65,536 × 1 = 화소 6만 남짓이라 화소와 펼친 크기는 한도 안이지만, 한 줄이 너무 길다
		assertThatThrownBy(() -> processor.process(upload(TestImages.pngHeaderOnly(65_536, 1, 8, 6))))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.IMAGE_DECODED_TOO_LARGE);
		// 65,535까지는 한 변 검사를 지나간다 (그림 데이터가 없는 파일이라 펼치는 단계에서 INVALID_IMAGE)
		assertThatThrownBy(() -> processor.process(upload(TestImages.pngHeaderOnly(65_535, 1, 8, 6))))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.INVALID_IMAGE);
	}

	@Test
	void 깨진_PNG는_색_형식과_상관없이_INVALID_IMAGE로_거절한다() {
		// 머리만 있고 그림 데이터가 없는 PNG. 회색(0), RGB(2), 팔레트(3)는 형식을 정하려고 뒤의 조각까지 읽다가
		// IOException이 아닌 예외(IndexOutOfBoundsException)가 난다. 그대로 새면 에러 화면(500)이 된다
		for (int colorType : new int[] {0, 2, 3, 4, 6}) {
			byte[] png = TestImages.pngHeaderOnly(100, 100, 8, colorType);

			assertThatThrownBy(() -> processor.process(upload(png)))
					.as("색 형식 %d", colorType)
					.isInstanceOf(BusinessException.class)
					.extracting(e -> ((BusinessException) e).getErrorCode())
					.isEqualTo(ErrorCode.INVALID_IMAGE);
		}
	}

	@Test
	void PNG를_펼치다_메모리가_모자라면_읽을_수_없는_사진으로_바꾸지_않고_그대로_던진다() throws IOException {
		// 자바의 PNG 읽기는 펼치다 메모리가 모자라면 OutOfMemoryError를 IIOException으로 감싸서 던진다 (JM-105에서 찾음).
		// 진짜로 메모리를 다 쓰게 하는 대신, 펼치기(readUpright)가 그 예외를 던지게 바꿔 둔다.
		ImageProcessor watched = spy(processor);
		doThrow(new IIOException("Caught exception during read: ", new OutOfMemoryError("Java heap space")))
				.when(watched).readUpright(any(), anyInt());

		// 400(INVALID_IMAGE)이 아니라, JPEG에서 메모리가 모자랄 때처럼 OutOfMemoryError 그대로 나간다
		assertThatThrownBy(() -> watched.process(upload(TestImages.transparentPng(100, 100))))
				.isInstanceOf(OutOfMemoryError.class);
	}

	// ---- 아주 넓은 사진을 줄여 읽는 목표 (JM-105) ----

	@Test
	void 긴_변이_10240보다_짧으면_목표는_2560이고_그_이상이면_긴_변을_2의_거듭제곱으로_나눈_값이다() {
		// 휴대폰 사진(긴 변 4032 ~ 8160)은 지금처럼 2560
		assertThat(ImageProcessor.readTarget(4032)).isEqualTo(2560);
		assertThat(ImageProcessor.readTarget(8160)).isEqualTo(2560);
		assertThat(ImageProcessor.readTarget(10239)).isEqualTo(2560);
		// 10240 이상 20480 미만은 4로 나눠 올린다: 10240 → 2560, 10241 → 2561 (2560.25), 12000 → 3000, 20479 → 5120
		assertThat(ImageProcessor.readTarget(10240)).isEqualTo(2560);
		assertThat(ImageProcessor.readTarget(10241)).isEqualTo(2561);
		assertThat(ImageProcessor.readTarget(12000)).isEqualTo(3000);
		assertThat(ImageProcessor.readTarget(20479)).isEqualTo(5120);
		// 그보다 길면 8, 16으로 나눈다: 20481 → 2561 (2560.125), 50000 → 3125
		assertThat(ImageProcessor.readTarget(20481)).isEqualTo(2561);
		assertThat(ImageProcessor.readTarget(50000)).isEqualTo(3125);
	}

	@Test
	void 긴_변이_10240_이상인_넓은_사진은_맞춘_목표로_줄여_읽는다() throws IOException {
		// 긴 변 10241 → 목표 2561. 2560으로 읽으면 Thumbnailator가 원본만 한 임시 그림을 한 장 더 만든다
		BufferedImage upright = processor.readUpright(TestImages.jpeg(10241, 100), 10241);

		assertThat(upright.getWidth()).isEqualTo(2561);
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
