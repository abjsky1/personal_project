package backend.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

import com.drew.imaging.ImageMetadataReader;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.ExifThumbnailDirectory;
import com.drew.metadata.exif.GpsDirectory;

// PhotoMetadataReader 단위 테스트 (JM-111)
// 1. 위치와 촬영 시각은 전에 쓰던 라이브러리의 전체 읽기(ImageMetadataReader)와 같아야 한다
// 2. 쓰지 않는 칸, 목록은 베끼지도 만들지도 않는다
// 3. 로그에 남기는 이름은 전의 디렉터리 이름에 맞춘다
class PhotoMetadataReaderTest {

	private static final TimeZone KOREA = TimeZone.getTimeZone("Asia/Seoul");

	// EXIF 값 형식 번호
	private static final int BYTE = 1;
	private static final int ASCII = 2;
	private static final int SHORT = 3;
	private static final int LONG = 4;
	private static final int RATIONAL = 5;
	private static final int UNDEFINED = 7;
	private static final int SLONG = 9;
	private static final int IFD = 13;

	// ---- 전과 같은 값 ----

	@Test
	void 전에_쓰던_라이브러리_전체_읽기와_같은_위치와_촬영_시각을_읽는다() throws Exception {
		List<byte[]> photos = List.of(
				TestImages.withGps(TestImages.jpeg(200, 100), 37.566535, 126.977969, "2026:10:29 12:30:00"),
				// 남위, 서경 (S, W는 음수가 된다)
				TestImages.withGps(TestImages.jpeg(200, 100), -33.4489, -70.6693, "2026:01:02 03:04:05"),
				TestImages.withPngGps(TestImages.rgbPng(200, 100), 35.179554, 129.075642, "2026:11:27 08:00:00"),
				// 휴대폰처럼 바이트 순서가 작은 자리부터(II)이고, 제조사 칸, 호환성 목록, GPS 칸 아홉 개, 미리보기 목록이 있는 EXIF
				TestImages.withExifTiff(TestImages.jpeg(200, 100), phoneLike()),
				// 초 아래 자리(0x9291)와 시간대(0x9011)가 있는 촬영 시각
				TestImages.withExifTiff(TestImages.jpeg(200, 100), timeWithSubsecondAndZone()),
				// JFIF 조각 뒤에 EXIF 조각이 오는 JPEG (브라우저나 편집 앱이 다시 저장한 사진에 흔하다)
				afterJfif(TestImages.jpeg(200, 100), 0xE1, exifPayload(TestImages.withGps(TestImages.jpeg(10, 10), 37.5, 127.0, "2026:10:29 12:30:00"))),
				// 조각 사이의 채움 바이트(FF FF)
				withFillBytes(TestImages.withGps(TestImages.jpeg(200, 100), 37.4, 126.9, "2026:10:29 12:30:00")),
				// eXIf가 그림 데이터(IDAT) 뒤에 있는 PNG
				beforeIend(TestImages.rgbPng(200, 100), exifChunk(TestImages.withPngGps(TestImages.rgbPng(10, 10), 33.5, 126.5, "2026:10:29 12:30:00"))),
				// 하위 목록 자리를 IFD 형식(13)과 부호 있는 LONG(9)으로 적은 EXIF
				TestImages.withExifTiff(TestImages.jpeg(200, 100), pointersAsIfdAndSlong()),
				TestImages.withExifOrientation(TestImages.jpeg(200, 100), 6),
				TestImages.jpeg(200, 100),
				TestImages.rgbPng(100, 100));

		int withLocation = 0;
		for (byte[] photo : photos) {
			Metadata all = ImageMetadataReader.readMetadata(new ByteArrayInputStream(photo));
			Metadata needed = PhotoMetadataReader.read(photo).metadata();

			assertThat(location(needed)).isEqualTo(location(all));
			assertThat(takenAt(needed)).isEqualTo(takenAt(all));
			if (location(needed) != null) {
				withLocation++;
			}
		}
		// 비교가 둘 다 비어 있는 것끼리만 이뤄지지 않았는지 (위치가 있는 사진 여덟)
		assertThat(withLocation).isEqualTo(8);
	}

	@Test
	void 초_아래_자리와_시간대가_있으면_그대로_반영한다() {
		Metadata needed = PhotoMetadataReader.read(TestImages.withExifTiff(TestImages.jpeg(200, 100), timeWithSubsecondAndZone())).metadata();

		// 2026-10-29 12:30:00.123 +05:30 = UTC 07:00:00.123 (한국 시간이 아니라 사진의 시간대로 읽는다)
		assertThat(takenAt(needed).toInstant().toString()).isEqualTo("2026-10-29T07:00:00.123Z");
	}

	@Test
	void EXIF_조각이_여럿이면_첫_조각만_읽는다() throws Exception {
		// 첫 EXIF 조각에는 촬영 시각만, 둘째 조각에는 위치와 시각. 전에는 둘째 조각의 위치까지 읽었다 (전과 다른 드문 경우)
		byte[] photo = TestImages.withGps(TestImages.jpeg(200, 100), 37.566535, 126.977969, "2026:01:01 00:00:00");
		byte[] timeOnly = exifPayload(TestImages.withExifTiff(TestImages.jpeg(10, 10), timeWithSubsecondAndZone()));
		photo = TestImages.withSegment(photo, 0xE1, timeOnly);

		Metadata needed = PhotoMetadataReader.read(photo).metadata();
		Metadata all = ImageMetadataReader.readMetadata(new ByteArrayInputStream(photo));

		assertThat(location(needed)).isNull();
		assertThat(takenAt(needed).toInstant().toString()).isEqualTo("2026-10-29T07:00:00.123Z");
		assertThat(location(all)).isNotNull();
	}

	@Test
	void 그림_데이터_뒤의_EXIF_조각은_읽지_않는다() {
		// EOI(FF D9) 바로 앞, 즉 그림 데이터(SOS) 뒤에 EXIF 조각처럼 보이는 바이트를 넣는다. 전에 쓰던 라이브러리도 SOS에서 멈춘다
		byte[] jpeg = TestImages.jpeg(100, 100);
		byte[] fake = exifSegment(exifPayload(TestImages.withGps(TestImages.jpeg(10, 10), 37.5, 127.0, "2026:10:29 12:30:00")));
		byte[] photo = new byte[jpeg.length + fake.length];
		System.arraycopy(jpeg, 0, photo, 0, jpeg.length - 2);
		System.arraycopy(fake, 0, photo, jpeg.length - 2, fake.length);
		System.arraycopy(jpeg, jpeg.length - 2, photo, jpeg.length - 2 + fake.length, 2);

		PhotoMetadataReader.Read read = PhotoMetadataReader.read(photo);

		assertThat(location(read.metadata())).isNull();
		assertThat(read.blocks()).containsExactly("JPEG", "JFIF");
	}

	// ---- 쓰지 않는 칸, 목록 ----

	@Test
	void IFD0에서_EXIF와_GPS_목록으로_한_번씩만_들어가고_다른_하위_목록은_만들지_않는다() {
		// IFD0: 하위 목록 칸(0x014A, 값 3개가 모두 IFD0을 가리킴), GPS 칸 둘(첫째는 북위 10도 동경 20도, 둘째는 30도 40도)
		int ifd0 = 8, list = ifdEnd(ifd0, 3), gpsA = list + 12, gpsB = ifdEnd(gpsA, 4), values = ifdEnd(gpsB, 4);
		byte[] tiff = new Tiff(values + 96)
				.ifd(ifd0, 0, tag(0x014A, LONG, 3, list), tag(0x8825, LONG, 1, gpsA), tag(0x8825, LONG, 1, gpsB))
				.ints(list, ifd0, ifd0, ifd0)
				.ifd(gpsA, 0, tag(1, ASCII, 2, 'N' << 24), tag(2, RATIONAL, 3, values), tag(3, ASCII, 2, 'E' << 24),
						tag(4, RATIONAL, 3, values + 24))
				.ifd(gpsB, 0, tag(1, ASCII, 2, 'N' << 24), tag(2, RATIONAL, 3, values + 48), tag(3, ASCII, 2, 'E' << 24),
						tag(4, RATIONAL, 3, values + 72))
				.ints(values, 10, 1, 0, 1, 0, 1, 20, 1, 0, 1, 0, 1, 30, 1, 0, 1, 0, 1, 40, 1, 0, 1, 0, 1)
				.build();

		PhotoMetadataReader.Read read = PhotoMetadataReader.read(TestImages.withExifTiff(TestImages.jpeg(100, 100), tiff));

		assertThat(read.blocks()).containsExactly("JPEG", "Exif IFD0", "GPS");
		assertThat(read.metadata().getDirectoryCount()).isEqualTo(2);
		assertThat(location(read.metadata())).isEqualTo(new GeoLocation(10, 20));
	}

	@Test
	void 하위_목록_칸이_아주_많아도_디렉터리는_IFD0_하나뿐이다() {
		// 0x014A 칸 200개가 모두 값 16개(IFD0 자리)를 가리킨다 (값은 모두 3,200개로, 상한 1만 개 안)
		int ifd0 = 8, list = ifdEnd(ifd0, 200);
		int[][] entries = new int[200][];
		for (int i = 0; i < entries.length; i++) {
			entries[i] = tag(0x014A, LONG, 16, list);
		}
		Tiff tiff = new Tiff(list + 64).ifd(ifd0, 0, entries);
		for (int i = 0; i < 16; i++) {
			tiff.ints(list + 4 * i, ifd0);
		}

		PhotoMetadataReader.Read read = PhotoMetadataReader.read(TestImages.withExifTiff(TestImages.jpeg(100, 100), tiff.build()));

		assertThat(read.metadata().getDirectoryCount()).isEqualTo(1);
		assertThat(read.blocks()).containsExactly("JPEG", "Exif IFD0");
	}

	@Test
	void 하위_목록_자리_값을_1만_개보다_많이_보면_깨진_EXIF로_보고_멈춘다() {
		// EXIF 목록 칸 하나에 값이 10,001개 (모두 IFD0 자리). 첫 값으로 EXIF 목록에 한 번 들어가고, 상한을 넘으면 멈춘다
		int count = PhotoMetadataReader.MAX_POINTER_VALUES + 1;
		int ifd0 = 8, list = ifdEnd(ifd0, 1);
		Tiff tiff = new Tiff(list + 4 * count).ifd(ifd0, 0, tag(0x8769, LONG, count, list));
		for (int i = 0; i < count; i++) {
			tiff.ints(list + 4 * i, ifd0);
		}

		PhotoMetadataReader.Read read = PhotoMetadataReader.read(TestImages.withExifTiff(TestImages.jpeg(100, 100), tiff.build()));

		assertThat(read.blocks()).containsExactly("JPEG", "Exif IFD0", "Exif SubIFD", "broken EXIF");
		assertThat(read.metadata().getDirectoryCount()).isEqualTo(2);
	}

	@Test
	void 쓰는_칸_말고는_베끼지_않는다() {
		// IFD0: 제조사 이름(0x010F, 로그의 제조사 칸 이름을 가리는 데만 씀), 분수 3개 칸(0x1000)
		// EXIF: 노출 시간(0x829A), 촬영 시각 / GPS: 위도, 경도와 고도(6)
		int ifd0 = 8, exif = ifdEnd(ifd0, 4), gps = ifdEnd(exif, 2), make = ifdEnd(gps, 5);
		int rational3 = make + 8, exposure = rational3 + 24, date = exposure + 8, lat = date + 20, lon = lat + 24, alt = lon + 24;
		byte[] tiff = new Tiff(alt + 8)
				.ifd(ifd0, 0, tag(0x010F, ASCII, 6, make), tag(0x1000, RATIONAL, 3, rational3),
						tag(0x8769, LONG, 1, exif), tag(0x8825, LONG, 1, gps))
				.ifd(exif, 0, tag(0x829A, RATIONAL, 1, exposure), tag(0x9003, ASCII, 20, date))
				.ifd(gps, 0, tag(1, ASCII, 2, 'N' << 24), tag(2, RATIONAL, 3, lat), tag(3, ASCII, 2, 'E' << 24),
						tag(4, RATIONAL, 3, lon), tag(6, RATIONAL, 1, alt))
				.bytes(make, "Apple\0".getBytes(StandardCharsets.US_ASCII))
				.ints(rational3, 1, 2, 3, 4, 5, 6)
				.ints(exposure, 1, 120)
				.bytes(date, "2026:10:29 12:30:00\0".getBytes(StandardCharsets.US_ASCII))
				.ints(lat, 37, 1, 33, 1, 0, 1)
				.ints(lon, 126, 1, 58, 1, 0, 1)
				.ints(alt, 38, 1)
				.build();

		Metadata needed = PhotoMetadataReader.read(TestImages.withExifTiff(TestImages.jpeg(100, 100), tiff)).metadata();

		assertThat(tagIds(needed.getFirstDirectoryOfType(ExifIFD0Directory.class))).containsExactly(0x010F);
		assertThat(tagIds(needed.getFirstDirectoryOfType(ExifSubIFDDirectory.class))).containsExactly(0x9003);
		assertThat(tagIds(needed.getFirstDirectoryOfType(GpsDirectory.class))).containsExactly(1, 2, 3, 4);
		assertThat(location(needed)).isNotNull();
		assertThat(takenAt(needed)).isNotNull();
	}

	@Test
	void 값이_64바이트까지는_읽고_넘으면_쓰는_칸이어도_읽지_않는다() {
		// 촬영 시각 칸이 64글자(뒤는 0)면 읽고, 위도가 분수 9개(72바이트)면 읽지 않는다
		int ifd0 = 8, exif = ifdEnd(ifd0, 2), gps = ifdEnd(exif, 1), date = ifdEnd(gps, 4), lat = date + 64, lon = lat + 72;
		byte[] tiff = new Tiff(lon + 24)
				.ifd(ifd0, 0, tag(0x8769, LONG, 1, exif), tag(0x8825, LONG, 1, gps))
				.ifd(exif, 0, tag(0x9003, ASCII, PhotoMetadataReader.MAX_VALUE_BYTES, date))
				.ifd(gps, 0, tag(1, ASCII, 2, 'N' << 24), tag(2, RATIONAL, 9, lat), tag(3, ASCII, 2, 'E' << 24),
						tag(4, RATIONAL, 3, lon))
				.bytes(date, "2026:10:29 12:30:00\0".getBytes(StandardCharsets.US_ASCII))
				.ints(lat, 37, 1, 33, 1, 0, 1)
				.ints(lon, 126, 1, 58, 1, 0, 1)
				.build();

		Metadata needed = PhotoMetadataReader.read(TestImages.withExifTiff(TestImages.jpeg(100, 100), tiff)).metadata();

		assertThat(takenAt(needed)).isNotNull();
		assertThat(needed.getFirstDirectoryOfType(GpsDirectory.class).containsTag(GpsDirectory.TAG_LATITUDE)).isFalse();
		assertThat(location(needed)).isNull();
	}

	@Test
	void 촬영_시각이_65바이트면_읽지_않는다() {
		int ifd0 = 8, exif = ifdEnd(ifd0, 1), date = ifdEnd(exif, 1);
		byte[] tiff = new Tiff(date + 65)
				.ifd(ifd0, 0, tag(0x8769, LONG, 1, exif))
				.ifd(exif, 0, tag(0x9003, ASCII, PhotoMetadataReader.MAX_VALUE_BYTES + 1, date))
				.bytes(date, "2026:10:29 12:30:00\0".getBytes(StandardCharsets.US_ASCII))
				.build();

		assertThat(takenAt(PhotoMetadataReader.read(TestImages.withExifTiff(TestImages.jpeg(100, 100), tiff)).metadata())).isNull();
	}

	@Test
	void PNG의_eXIf가_JPEG의_EXIF_조각보다_크면_읽지_않는다() {
		// 시각이 든 보통 TIFF 뒤를 0으로 채워 상한보다 1바이트 크게 만든다
		byte[] small = timeWithSubsecondAndZone();
		byte[] big = Arrays.copyOf(small, PhotoMetadataReader.MAX_TIFF_BYTES + 1);
		byte[] atLimit = Arrays.copyOf(small, PhotoMetadataReader.MAX_TIFF_BYTES);

		PhotoMetadataReader.Read tooBig = PhotoMetadataReader.read(TestImages.withChunk(TestImages.rgbPng(100, 100), "eXIf", big));
		PhotoMetadataReader.Read fits = PhotoMetadataReader.read(TestImages.withChunk(TestImages.rgbPng(100, 100), "eXIf", atLimit));

		assertThat(tooBig.blocks()).containsExactly("PNG", "eXIf too large");
		assertThat(takenAt(tooBig.metadata())).isNull();
		// 상한과 같은 크기까지는 읽는다 (JPEG의 EXIF 조각 하나에 들어가는 크기)
		assertThat(takenAt(fits.metadata())).isNotNull();
	}

	@Test
	void TIFF가_중간에_깨져도_그때까지_읽은_값은_쓰고_깨졌다는_것을_남긴다() {
		// IFD0: EXIF 목록(촬영 시각) 다음에, 데이터 끝 바로 앞을 가리키는 GPS 목록 자리 → GPS 목록을 읽다가 데이터가 모자란다
		int ifd0 = 8, exif = ifdEnd(ifd0, 2), date = ifdEnd(exif, 1), total = date + 20;
		byte[] tiff = new Tiff(total)
				.ifd(ifd0, 0, tag(0x8769, LONG, 1, exif), tag(0x8825, LONG, 1, total - 1))
				.ifd(exif, 0, tag(0x9003, ASCII, 20, date))
				.bytes(date, "2026:10:29 12:30:00\0".getBytes(StandardCharsets.US_ASCII))
				.build();

		PhotoMetadataReader.Read read = PhotoMetadataReader.read(TestImages.withExifTiff(TestImages.jpeg(100, 100), tiff));

		assertThat(takenAt(read.metadata())).isNotNull();
		assertThat(read.blocks()).containsExactly("JPEG", "Exif IFD0", "Exif SubIFD", "GPS", "broken EXIF");
	}

	@Test
	void 바이트_순서_표시가_틀린_TIFF는_예외_없이_깨졌다는_것만_남긴다() {
		byte[] tiff = {'X', 'X', 0, 42, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0};

		PhotoMetadataReader.Read read = PhotoMetadataReader.read(TestImages.withExifTiff(TestImages.jpeg(100, 100), tiff));

		assertThat(read.blocks()).containsExactly("JPEG", "broken EXIF");
		assertThat(read.metadata().getDirectoryCount()).isZero();
	}

	// ---- 로그에 남기는 이름 ----

	@Test
	void 휴대폰_사진의_정보_덩어리_이름을_전과_같게_남긴다() {
		PhotoMetadataReader.Read read = PhotoMetadataReader.read(TestImages.withExifTiff(TestImages.jpeg(100, 100), phoneLike()));

		// 호환성 목록, 제조사 칸, 미리보기 목록은 읽지 않고 이름만 (디렉터리는 셋)
		assertThat(read.blocks()).containsExactly("JPEG", "Exif IFD0", "Exif SubIFD", "GPS", "Interoperability",
				"Samsung Makernote", "Exif Thumbnail");
		assertThat(read.metadata().getDirectoryCount()).isEqualTo(3);
		assertThat(read.metadata().getFirstDirectoryOfType(ExifThumbnailDirectory.class)).isNull();
	}

	@Test
	void 아이폰_제조사_칸은_머리로_알아보고_모르는_제조사_칸은_Makernote로_남긴다() {
		PhotoMetadataReader.Read apple = PhotoMetadataReader.read(TestImages.withExifTiff(TestImages.jpeg(100, 100),
				withMakernote("Apple iOS\0\0\1MM\0\0".getBytes(StandardCharsets.ISO_8859_1))));
		PhotoMetadataReader.Read other = PhotoMetadataReader.read(TestImages.withExifTiff(TestImages.jpeg(100, 100),
				withMakernote("OLYMP\0\1\0\0\0\0\0\0\0\0\0".getBytes(StandardCharsets.ISO_8859_1))));

		assertThat(apple.blocks()).containsExactly("JPEG", "Exif IFD0", "Exif SubIFD", "Apple Makernote", "Exif Thumbnail");
		assertThat(other.blocks()).contains("Makernote");
		// 제조사 칸 자체는 베끼지 않는다
		assertThat(apple.metadata().getFirstDirectoryOfType(ExifSubIFDDirectory.class).containsTag(0x927C)).isFalse();
	}

	@Test
	void JPEG의_정보_조각_이름을_사진_안의_차례대로_남긴다() {
		// SOI → JFIF → XMP → ICC → Photoshop → 주석 → EXIF (withSegment는 맨 앞에 넣으므로 거꾸로 넣는다)
		byte[] photo = TestImages.withGps(TestImages.jpeg(100, 100), 37.566535, 126.977969, "2026:10:29 12:30:00");
		photo = TestImages.withSegment(photo, 0xFE, "hello".getBytes(StandardCharsets.US_ASCII));
		photo = TestImages.withSegment(photo, 0xED, "Photoshop 3.0\0".getBytes(StandardCharsets.ISO_8859_1));
		photo = TestImages.withSegment(photo, 0xE2, "ICC_PROFILE\0\1\1".getBytes(StandardCharsets.ISO_8859_1));
		photo = TestImages.withSegment(photo, 0xE1, "http://ns.adobe.com/xap/1.0/\0<x:xmpmeta/>".getBytes(StandardCharsets.ISO_8859_1));
		photo = TestImages.withSegment(photo, 0xE0, jfif());

		PhotoMetadataReader.Read read = PhotoMetadataReader.read(photo);

		assertThat(read.blocks()).containsExactly("JPEG", "JFIF", "XMP", "ICC Profile", "Photoshop", "JpegComment",
				"Exif IFD0", "Exif SubIFD", "GPS");
		assertThat(location(read.metadata())).isNotNull();
	}

	@Test
	void PNG는_eXIf_조각의_목록_이름을_남긴다() {
		byte[] photo = TestImages.withPngGps(TestImages.rgbPng(100, 100), 37.566535, 126.977969, "2026:10:29 12:30:00");

		assertThat(PhotoMetadataReader.read(photo).blocks()).containsExactly("PNG", "Exif IFD0", "Exif SubIFD", "GPS");
	}

	@Test
	void EXIF가_없는_사진은_빈_결과다() {
		// 자바가 만든 JPEG에는 JFIF 조각만 있다
		PhotoMetadataReader.Read jpeg = PhotoMetadataReader.read(TestImages.jpeg(100, 100));
		PhotoMetadataReader.Read png = PhotoMetadataReader.read(TestImages.rgbPng(100, 100));

		assertThat(jpeg.metadata().getDirectoryCount()).isZero();
		assertThat(jpeg.blocks()).containsExactly("JPEG", "JFIF");
		assertThat(png.metadata().getDirectoryCount()).isZero();
		assertThat(png.blocks()).containsExactly("PNG");
	}

	@Test
	void 로그에_남기는_이름은_12가지까지다() {
		// 서로 다른 APPn 조각 13가지 (APP3 ~ APP15)와 자바가 만든 JFIF 조각
		byte[] photo = TestImages.jpeg(100, 100);
		for (int marker = 0xE3; marker <= 0xEF; marker++) {
			photo = TestImages.withSegment(photo, marker, new byte[] {1, 2, 3});
		}

		assertThat(PhotoMetadataReader.read(photo).blocks()).hasSize(PhotoMetadataReader.MAX_BLOCKS);
	}

	// ---- 도움 ----

	private static GeoLocation location(Metadata metadata) {
		GpsDirectory gps = metadata.getFirstDirectoryOfType(GpsDirectory.class);
		return gps == null ? null : gps.getGeoLocation();
	}

	private static Date takenAt(Metadata metadata) {
		ExifSubIFDDirectory exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
		return exif == null ? null : exif.getDateOriginal(KOREA);
	}

	private static List<Integer> tagIds(Directory directory) {
		return directory.getTags().stream().map(t -> t.getTagType()).toList();
	}

	// IFD0 → EXIF 목록: 촬영 시각 2026:10:29 12:30:00, 시간대 +05:30, 초 아래 자리 123
	private static byte[] timeWithSubsecondAndZone() {
		int ifd0 = 8, exif = ifdEnd(ifd0, 1), date = ifdEnd(exif, 3), zone = date + 20;
		return new Tiff(zone + 8)
				.ifd(ifd0, 0, tag(0x8769, LONG, 1, exif))
				.ifd(exif, 0, tag(0x9003, ASCII, 20, date), tag(0x9011, ASCII, 7, zone),
						tag(0x9291, ASCII, 4, ('1' << 24) | ('2' << 16) | ('3' << 8)))
				.bytes(date, "2026:10:29 12:30:00\0".getBytes(StandardCharsets.US_ASCII))
				.bytes(zone, "+05:30\0".getBytes(StandardCharsets.US_ASCII))
				.build();
	}

	// 하위 목록 자리를 IFD 형식(13)과 SLONG(9)으로 적은 EXIF: 촬영 시각과 북위 37.5 동경 127
	private static byte[] pointersAsIfdAndSlong() {
		int ifd0 = 8, exif = ifdEnd(ifd0, 2), gps = ifdEnd(exif, 1), date = ifdEnd(gps, 4), lat = date + 20, lon = lat + 24;
		return new Tiff(lon + 24)
				.ifd(ifd0, 0, tag(0x8769, IFD, 1, exif), tag(0x8825, SLONG, 1, gps))
				.ifd(exif, 0, tag(0x9003, ASCII, 20, date))
				.ifd(gps, 0, tag(1, ASCII, 2, 'N' << 24), tag(2, RATIONAL, 3, lat), tag(3, ASCII, 2, 'E' << 24),
						tag(4, RATIONAL, 3, lon))
				.bytes(date, "2026:10:29 12:30:00\0".getBytes(StandardCharsets.US_ASCII))
				.ints(lat, 37, 1, 30, 1, 0, 1)
				.ints(lon, 127, 1, 0, 1, 0, 1)
				.build();
	}

	// 휴대폰(삼성) 사진 비슷한 EXIF. 바이트 순서가 작은 자리부터(II)
	// IFD0: 제조사, 모델, 방향, EXIF 목록, GPS 목록, 다음 목록 = IFD1(미리보기)
	// EXIF 목록: 노출 시간, 촬영 시각, 시간대, 초 아래 자리, 제조사 칸(빈 목록), 호환성 목록 / GPS: 칸 아홉 개
	private static byte[] phoneLike() {
		int ifd0 = 8, exif = ifdEnd(ifd0, 5), interop = ifdEnd(exif, 6), gps = ifdEnd(interop, 1), ifd1 = ifdEnd(gps, 9);
		int make = ifdEnd(ifd1, 3), model = make + 8, exposure = model + 10, date = exposure + 8, zone = date + 20;
		int makernote = zone + 8, lat = makernote + 16, lon = lat + 24, alt = lon + 24, time = alt + 8, day = time + 24;
		return new Tiff(day + 12, ByteOrder.LITTLE_ENDIAN)
				.ifd(ifd0, ifd1, tag(0x010F, ASCII, 8, make), tag(0x0110, ASCII, 9, model), tag(0x0112, SHORT, 1, 6),
						tag(0x8769, LONG, 1, exif), tag(0x8825, LONG, 1, gps))
				.ifd(exif, 0, tag(0x829A, RATIONAL, 1, exposure), tag(0x9003, ASCII, 20, date), tag(0x9011, ASCII, 7, zone),
						tag(0x9291, ASCII, 4, '4' | ('5' << 8) | ('6' << 16)), tag(0x927C, UNDEFINED, 16, makernote),
						tag(0xA005, LONG, 1, interop))
				.ifd(interop, 0, tag(0x0001, ASCII, 4, 'R' | ('9' << 8) | ('8' << 16)))
				.ifd(gps, 0, tag(0, BYTE, 4, 2 | (2 << 8)), tag(1, ASCII, 2, 'N'), tag(2, RATIONAL, 3, lat), tag(3, ASCII, 2, 'E'),
						tag(4, RATIONAL, 3, lon), tag(5, BYTE, 1, 0), tag(6, RATIONAL, 1, alt), tag(7, RATIONAL, 3, time),
						tag(0x001D, ASCII, 11, day))
				.ifd(ifd1, 0, tag(0x0103, SHORT, 1, 6), tag(0x0201, LONG, 1, make), tag(0x0202, LONG, 1, 0))
				.bytes(make, "samsung\0".getBytes(StandardCharsets.US_ASCII))
				.bytes(model, "SM-S928N\0".getBytes(StandardCharsets.US_ASCII))
				.ints(exposure, 1, 120)
				.bytes(date, "2026:10:29 12:30:00\0".getBytes(StandardCharsets.US_ASCII))
				.bytes(zone, "+09:00\0".getBytes(StandardCharsets.US_ASCII))
				.ints(lat, 37, 1, 33, 1, 59526, 1000)
				.ints(lon, 126, 1, 58, 1, 406884, 10000)
				.ints(alt, 38, 1)
				.ints(time, 3, 1, 30, 1, 0, 1)
				.bytes(day, "2026:10:29\0".getBytes(StandardCharsets.US_ASCII))
				.build();
	}

	// IFD0 → EXIF 목록에 제조사 칸 하나, IFD0 다음 목록 = IFD1 (제조사 이름은 없다)
	private static byte[] withMakernote(byte[] head16) {
		int ifd0 = 8, exif = ifdEnd(ifd0, 1), ifd1 = ifdEnd(exif, 1), makernote = ifdEnd(ifd1, 1);
		return new Tiff(makernote + 16)
				.ifd(ifd0, ifd1, tag(0x8769, LONG, 1, exif))
				.ifd(exif, 0, tag(0x927C, UNDEFINED, 16, makernote))
				.ifd(ifd1, 0, tag(0x0103, SHORT, 1, 6 << 16))
				.bytes(makernote, head16)
				.build();
	}

	// 규칙에 맞는 JFIF 조각 내용: "JFIF\0" + 판 1.1 + 단위 0 + 밀도 1 x 1 + 미리보기 0 x 0
	private static byte[] jfif() {
		return new byte[] {'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0};
	}

	// JPEG의 EXIF 조각 내용("Exif\0\0" + TIFF)만 꺼낸다 (withExifTiff가 맨 앞에 넣은 조각)
	private static byte[] exifPayload(byte[] jpegWithExif) {
		int length = ((jpegWithExif[4] & 0xFF) << 8) | (jpegWithExif[5] & 0xFF);
		return Arrays.copyOfRange(jpegWithExif, 6, 6 + length - 2);
	}

	// EXIF 조각 전체(FF E1 + 길이 + 내용)
	private static byte[] exifSegment(byte[] payload) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(0xFF);
		out.write(0xE1);
		out.write((payload.length + 2) >> 8);
		out.write((payload.length + 2) & 0xFF);
		out.writeBytes(payload);
		return out.toByteArray();
	}

	// 자바가 만든 JPEG의 첫 조각(JFIF) 바로 뒤에 조각 하나를 넣는다
	private static byte[] afterJfif(byte[] jpeg, int marker, byte[] payload) {
		int at = 4 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(jpeg, 0, at);
		byte[] segment = exifSegment(payload);
		segment[1] = (byte) marker;
		out.writeBytes(segment);
		out.write(jpeg, at, jpeg.length - at);
		return out.toByteArray();
	}

	// 시작 표시(FF D8) 뒤, 첫 조각 앞에 채움 바이트 FF 둘을 넣는다
	private static byte[] withFillBytes(byte[] jpeg) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(jpeg, 0, 2);
		out.write(0xFF);
		out.write(0xFF);
		out.write(jpeg, 2, jpeg.length - 2);
		return out.toByteArray();
	}

	// withPngGps가 IHDR 뒤에 넣은 eXIf 조각 전체(길이 + 종류 + 내용 + CRC)를 꺼낸다
	private static byte[] exifChunk(byte[] pngWithExif) {
		int length = ByteBuffer.wrap(pngWithExif, 33, 4).getInt();
		return Arrays.copyOfRange(pngWithExif, 33, 33 + 12 + length);
	}

	// PNG의 끝 표시(IEND, 마지막 12바이트) 바로 앞에 조각을 넣는다 → 그림 데이터(IDAT)보다 뒤에 온다
	private static byte[] beforeIend(byte[] png, byte[] chunk) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(png, 0, png.length - 12);
		out.writeBytes(chunk);
		out.write(png, png.length - 12, 12);
		return out.toByteArray();
	}

	// 목록 칸 하나: 번호, 형식, 개수, 값(4바이트 이하, TIFF의 바이트 순서로) 또는 값이 있는 자리
	private static int[] tag(int id, int type, int count, int valueOrOffset) {
		return new int[] {id, type, count, valueOrOffset};
	}

	// at 자리에서 시작하는 칸 n개짜리 목록이 끝나는 자리 (칸 수 2 + 칸 12씩 + 다음 목록 자리 4)
	private static int ifdEnd(int at, int entries) {
		return at + 2 + 12 * entries + 4;
	}

	// 시험용 TIFF 바이트. 바이트 순서는 큰 자리부터(MM)가 기본이고, 작은 자리부터(II)도 된다
	// 자리는 TIFF 맨 앞에서 센다. 첫 목록(IFD0)은 8에 둔다
	private static final class Tiff {
		private final ByteBuffer bytes;

		Tiff(int size) {
			this(size, ByteOrder.BIG_ENDIAN);
		}

		Tiff(int size, ByteOrder order) {
			bytes = ByteBuffer.allocate(size).order(order);
			byte mark = (byte) (order == ByteOrder.BIG_ENDIAN ? 'M' : 'I');
			bytes.put(mark).put(mark).putShort((short) 42).putInt(8);
		}

		Tiff ifd(int at, int next, int[]... entries) {
			bytes.position(at);
			bytes.putShort((short) entries.length);
			for (int[] e : entries) {
				bytes.putShort((short) e[0]).putShort((short) e[1]).putInt(e[2]).putInt(e[3]);
			}
			bytes.putInt(next);
			return this;
		}

		Tiff ints(int at, int... values) {
			bytes.position(at);
			for (int v : values) {
				bytes.putInt(v);
			}
			return this;
		}

		Tiff bytes(int at, byte[] value) {
			bytes.position(at);
			bytes.put(value);
			return this;
		}

		byte[] build() {
			return bytes.array();
		}
	}
}
