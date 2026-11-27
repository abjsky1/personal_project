package backend.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;

// PNG에서 쓰지 않는 조각을 버리는 PngChunks만 떼어서 검사한다 (JM-108)
class PngChunksTest {

	// 글자(tEXt, zTXt, iTXt), 색 정보(iCCP, gAMA, cHRM, sRGB), 해상도(pHYs), 만든 시각(tIME): 이 앱이 쓰지 않는 조각
	private static final List<String> UNUSED = List.of("tEXt", "zTXt", "iTXt", "iCCP", "gAMA", "cHRM", "sRGB", "pHYs", "tIME");

	@Test
	void 쓰지_않는_조각을_버리면_조각을_넣기_전의_파일과_바이트까지_같다() {
		byte[] png = TestImages.rgbPng(100, 100);
		// 시험용 사진이 그림 조각만으로 되어 있는지 먼저 확인한다
		assertThat(TestImages.chunkTypes(png)).containsExactly("IHDR", "IDAT", "IEND");
		byte[] withUnused = png;
		for (String type : UNUSED) {
			withUnused = TestImages.withChunk(withUnused, type, new byte[] {1, 2, 3});
		}

		assertThat(PngChunks.keepNeeded(withUnused)).isEqualTo(png);
	}

	@Test
	void 촬영_정보_조각_eXIf는_남긴다() {
		byte[] withGps = TestImages.withPngGps(TestImages.rgbPng(100, 100), 37.566535, 126.977969, "2026:10:29 12:30:00");
		byte[] withText = TestImages.withChunk(withGps, "tEXt", "Software\0editor".getBytes(StandardCharsets.ISO_8859_1));

		byte[] kept = PngChunks.keepNeeded(withText);

		assertThat(TestImages.chunkTypes(kept)).containsExactly("IHDR", "eXIf", "IDAT", "IEND");
		assertThat(kept).isEqualTo(withGps);
	}

	@Test
	void 팔레트와_투명색_조각은_남긴다() {
		// 8비트 팔레트 PNG는 PLTE를, 투명색을 넣은 RGB PNG는 tRNS를 가진다. 펼치는 데 쓰는 조각이라 남긴다
		byte[] palette = TestImages.palettePng(100, 100);
		byte[] transparentColor = TestImages.withTransparentColor(TestImages.rgbPng(100, 100));
		assertThat(TestImages.chunkTypes(palette)).contains("PLTE");
		assertThat(TestImages.chunkTypes(transparentColor)).contains("tRNS");

		assertThat(PngChunks.keepNeeded(TestImages.withChunk(palette, "iCCP", new byte[] {1}))).isEqualTo(palette);
		assertThat(PngChunks.keepNeeded(TestImages.withChunk(transparentColor, "gAMA", new byte[] {1}))).isEqualTo(transparentColor);
	}

	@Test
	void 버릴_조각이_없으면_받은_배열을_베끼지_않고_그대로_돌려준다() {
		for (byte[] png : new byte[][] {TestImages.rgbPng(100, 100), TestImages.palettePng(100, 100),
				TestImages.transparentPng(100, 100), TestImages.withPngGps(TestImages.rgbPng(10, 10), 37.5, 127.0, "2026:10:29 12:30:00")}) {
			assertThat(PngChunks.keepNeeded(png)).isSameAs(png);
		}
	}

	@Test
	void IEND_뒤에_붙은_바이트는_버린다() {
		byte[] png = TestImages.rgbPng(100, 100);
		ByteArrayOutputStream withTrailer = new ByteArrayOutputStream();
		withTrailer.writeBytes(png);
		withTrailer.writeBytes("hidden data after the image".getBytes(StandardCharsets.US_ASCII));

		assertThat(PngChunks.keepNeeded(withTrailer.toByteArray())).isEqualTo(png);
	}

	@Test
	void CRC가_빠졌거나_길이가_틀린_IEND는_규칙대로_쓴_IEND로_바꾼다() {
		// 자바의 PNG 읽기는 종류가 IEND면 길이와 CRC를 보지 않아서, 이런 IEND로 끝나는 PNG도 받는다 (고치기 전에도 올라가던 사진)
		byte[] png = TestImages.rgbPng(100, 100);
		// 시험용 사진은 규칙대로 쓴 IEND 12바이트(길이 0, "IEND", CRC AE 42 60 82)로 끝난다
		assertThat(Arrays.copyOfRange(png, png.length - 12, png.length)).containsExactly(
				0, 0, 0, 0, 'I', 'E', 'N', 'D', 0xAE, 0x42, 0x60, 0x82);
		byte[] noCrc = Arrays.copyOf(png, png.length - 4);
		byte[] halfCrc = Arrays.copyOf(png, png.length - 2);
		byte[] wrongLength = png.clone();
		ByteBuffer.wrap(wrongLength, png.length - 12, 4).putInt(0x7FFFFFFF);

		for (byte[] broken : new byte[][] {noCrc, halfCrc, wrongLength}) {
			assertThat(PngChunks.keepNeeded(broken)).isEqualTo(png);
		}
	}

	@Test
	void 조각의_길이가_파일_끝을_넘으면_INVALID_IMAGE로_거절한다() {
		byte[] png = TestImages.rgbPng(100, 100);
		// 길이 1,000으로 적었지만 내용은 3바이트뿐인 조각, 부호 없는 길이 0xFFFFFFFF(int로 읽으면 -1)인 조각
		for (int length : new int[] {1_000, 0xFFFFFFFF}) {
			byte[] broken = TestImages.insertAfterHeader(png, ByteBuffer.allocate(11)
					.putInt(length).put("tEXt".getBytes(StandardCharsets.US_ASCII)).put(new byte[] {1, 2, 3}).array());
			// 1,000바이트 뒤가 파일 안이면 이 시험은 아무것도 보지 못한다. 파일이 그보다 짧은지 먼저 확인한다
			assertThat(broken.length).isLessThan(1_000);

			assertThatThrownBy(() -> PngChunks.keepNeeded(broken))
					.as("길이 %d", Integer.toUnsignedLong(length))
					.isInstanceOf(BusinessException.class)
					.extracting(e -> ((BusinessException) e).getErrorCode())
					.isEqualTo(ErrorCode.INVALID_IMAGE);
		}
	}

	@Test
	void 남길_조각이_하나도_없으면_서명만_남는다() {
		// 그림 조각 없이 글자 조각만 있는 파일. 서명만 남아 다음 단계(머리 읽기)에서 읽을 수 없는 사진이 된다
		byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
		ByteArrayOutputStream textOnly = new ByteArrayOutputStream();
		textOnly.writeBytes(signature);
		textOnly.writeBytes(ByteBuffer.allocate(4).putInt(3).array());
		textOnly.writeBytes("tEXt".getBytes(StandardCharsets.US_ASCII));
		textOnly.writeBytes(new byte[] {1, 2, 3, 0, 0, 0, 0});

		assertThat(PngChunks.keepNeeded(textOnly.toByteArray())).isEqualTo(signature);
	}

	@Test
	void 로그에는_영문자_4개인_종류만_그대로_남기고_나머지는_물음표로_바꾼다() {
		assertThat(PngChunks.forLog("tEXt")).isEqualTo("tEXt");
		assertThat(PngChunks.forLog("iCCP")).isEqualTo("iCCP");
		// 줄바꿈, 숫자, 공백, 영문자가 아닌 바이트
		assertThat(PngChunks.forLog("a\nbc")).isEqualTo("?");
		assertThat(PngChunks.forLog("ab12")).isEqualTo("?");
		assertThat(PngChunks.forLog("ab c")).isEqualTo("?");
		assertThat(PngChunks.forLog("abÿc")).isEqualTo("?");
	}
}
