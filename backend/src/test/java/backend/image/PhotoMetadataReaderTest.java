package backend.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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
class PhotoMetadataReaderTest {

	private static final TimeZone KOREA = TimeZone.getTimeZone("Asia/Seoul");

	// EXIF 값 형식 번호
	private static final int ASCII = 2;
	private static final int SHORT = 3;
	private static final int LONG = 4;
	private static final int RATIONAL = 5;
	private static final int UNDEFINED = 7;

	@Test
	void 전에_쓰던_라이브러리_전체_읽기와_같은_위치와_촬영_시각을_읽는다() throws Exception {
		List<byte[]> photos = List.of(
				TestImages.withGps(TestImages.jpeg(200, 100), 37.566535, 126.977969, "2026:10:29 12:30:00"),
				// 남위, 서경 (S, W는 음수가 된다)
				TestImages.withGps(TestImages.jpeg(200, 100), -33.4489, -70.6693, "2026:01:02 03:04:05"),
				TestImages.withPngGps(TestImages.rgbPng(200, 100), 35.179554, 129.075642, "2026:11:27 08:00:00"),
				// 초 아래 자리(0x9291)와 시간대(0x9011)가 있는 촬영 시각
				TestImages.withExifTiff(TestImages.jpeg(200, 100), timeWithSubsecondAndZone()),
				// JFIF 조각 뒤에 EXIF 조각이 오는 JPEG (브라우저나 편집 앱이 다시 저장한 사진에 흔하다)
				afterJfif(TestImages.jpeg(200, 100), 0xE1,
						exifPayload(TestImages.withGps(TestImages.jpeg(10, 10), 37.5, 127.0, "2026:10:29 12:30:00"))),
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
		// 비교가 둘 다 비어 있는 것끼리만 이뤄지지 않았는지 (위치가 있는 사진 넷)
		assertThat(withLocation).isEqualTo(4);
	}

	@Test
	void 초_아래_자리와_시간대가_있으면_그대로_반영한다() {
		Metadata needed = PhotoMetadataReader.read(TestImages.withExifTiff(TestImages.jpeg(200, 100), timeWithSubsecondAndZone())).metadata();

		// 2026-10-29 12:30:00.123 +05:30 = UTC 07:00:00.123 (한국 시간이 아니라 사진의 시간대로 읽는다)
		assertThat(takenAt(needed).toInstant().toString()).isEqualTo("2026-10-29T07:00:00.123Z");
	}

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

		assertThat(read.blocks()).containsExactly("Exif IFD0", "GPS");
		assertThat(read.metadata().getDirectoryCount()).isEqualTo(2);
		assertThat(location(read.metadata())).isEqualTo(new GeoLocation(10, 20));
	}

	@Test
	void 하위_목록_칸이_아주_많아도_디렉터리는_IFD0_하나뿐이다() {
		// 0x014A 칸 200개가 모두 값 16개(IFD0 자리)를 가리킨다. 전에는 값마다 디렉터리를 하나씩 만들었다 (3,200개)
		int ifd0 = 8, list = ifdEnd(ifd0, 200);
		int[][] entries = new int[200][];
		for (int i = 0; i < entries.length; i++) {
			entries[i] = tag(0x014A, LONG, 16, list);
		}
		Tiff tiff = new Tiff(list + 64).ifd(ifd0, 0, entries);
		for (int i = 0; i < 16; i++) {
			tiff.ints(list + 4 * i, ifd0);
		}
		byte[] photo = TestImages.withExifTiff(TestImages.jpeg(100, 100), tiff.build());

		PhotoMetadataReader.Read read = PhotoMetadataReader.read(photo);

		assertThat(read.metadata().getDirectoryCount()).isEqualTo(1);
		assertThat(read.blocks()).containsExactly("Exif IFD0");
	}

	@Test
	void 쓰는_일곱_칸_말고는_베끼지_않는다() {
		// IFD0: 제조사 이름(0x010F), 분수 3개 칸(0x1000) / EXIF: 노출 시간(0x829A), 촬영 시각 / GPS: 위도, 경도와 고도(6)
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

		assertThat(tagIds(needed.getFirstDirectoryOfType(ExifIFD0Directory.class))).isEmpty();
		assertThat(tagIds(needed.getFirstDirectoryOfType(ExifSubIFDDirectory.class))).containsExactly(0x9003);
		assertThat(tagIds(needed.getFirstDirectoryOfType(GpsDirectory.class))).containsExactly(1, 2, 3, 4);
		assertThat(location(needed)).isNotNull();
		assertThat(takenAt(needed)).isNotNull();
	}

	@Test
	void 값이_64바이트를_넘는_칸은_쓰는_칸이어도_읽지_않는다() {
		// 위도가 분수 9개(72바이트), 촬영 시각이 100글자
		int ifd0 = 8, exif = ifdEnd(ifd0, 2), gps = ifdEnd(exif, 1), date = ifdEnd(gps, 4), lat = date + 100, lon = lat + 72;
		byte[] tiff = new Tiff(lon + 24)
				.ifd(ifd0, 0, tag(0x8769, LONG, 1, exif), tag(0x8825, LONG, 1, gps))
				.ifd(exif, 0, tag(0x9003, ASCII, 100, date))
				.ifd(gps, 0, tag(1, ASCII, 2, 'N' << 24), tag(2, RATIONAL, 9, lat), tag(3, ASCII, 2, 'E' << 24),
						tag(4, RATIONAL, 3, lon))
				.bytes(date, "2026:10:29 12:30:00\0".getBytes(StandardCharsets.US_ASCII))
				.ints(lat, 37, 1, 33, 1, 0, 1)
				.ints(lon, 126, 1, 58, 1, 0, 1)
				.build();

		Metadata needed = PhotoMetadataReader.read(TestImages.withExifTiff(TestImages.jpeg(100, 100), tiff)).metadata();

		assertThat(needed.getFirstDirectoryOfType(GpsDirectory.class).containsTag(GpsDirectory.TAG_LATITUDE)).isFalse();
		assertThat(needed.getFirstDirectoryOfType(ExifSubIFDDirectory.class).containsTag(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)).isFalse();
		assertThat(location(needed)).isNull();
		assertThat(takenAt(needed)).isNull();
	}

	@Test
	void 미리보기_목록과_제조사_칸은_읽지_않고_있었다는_것만_남긴다() {
		// IFD0 → EXIF 목록(제조사 칸 0x927C) / IFD0의 다음 목록 = IFD1(미리보기 그림)
		int ifd0 = 8, exif = ifdEnd(ifd0, 1), ifd1 = ifdEnd(exif, 1), makernote = ifdEnd(ifd1, 1);
		byte[] tiff = new Tiff(makernote + 16)
				.ifd(ifd0, ifd1, tag(0x8769, LONG, 1, exif))
				.ifd(exif, 0, tag(0x927C, UNDEFINED, 16, makernote))
				.ifd(ifd1, 0, tag(0x0103, SHORT, 1, 6 << 16))
				.bytes(makernote, "Apple iOS\0\0\1MM\0\0".getBytes(StandardCharsets.ISO_8859_1))
				.build();

		PhotoMetadataReader.Read read = PhotoMetadataReader.read(TestImages.withExifTiff(TestImages.jpeg(100, 100), tiff));

		assertThat(read.blocks()).containsExactly("Exif IFD0", "Exif SubIFD", "Makernote", "Exif Thumbnail");
		assertThat(read.metadata().getFirstDirectoryOfType(ExifThumbnailDirectory.class)).isNull();
		assertThat(read.metadata().getFirstDirectoryOfType(ExifSubIFDDirectory.class).containsTag(0x927C)).isFalse();
		assertThat(read.metadata().getDirectoryCount()).isEqualTo(2);
	}

	@Test
	void JPEG의_정보_조각_이름을_사진_안의_차례대로_남긴다() {
		// SOI → JFIF → XMP → ICC → Photoshop → EXIF (withSegment는 맨 앞에 넣으므로 거꾸로 넣는다)
		byte[] photo = TestImages.withGps(TestImages.jpeg(100, 100), 37.566535, 126.977969, "2026:10:29 12:30:00");
		photo = TestImages.withSegment(photo, 0xED, "Photoshop 3.0\0".getBytes(StandardCharsets.ISO_8859_1));
		photo = TestImages.withSegment(photo, 0xE2, "ICC_PROFILE\0\1\1".getBytes(StandardCharsets.ISO_8859_1));
		photo = TestImages.withSegment(photo, 0xE1, "http://ns.adobe.com/xap/1.0/\0<x:xmpmeta/>".getBytes(StandardCharsets.ISO_8859_1));
		photo = TestImages.withSegment(photo, 0xE0, jfif());

		PhotoMetadataReader.Read read = PhotoMetadataReader.read(photo);

		assertThat(read.blocks()).containsExactly("JFIF", "XMP", "ICC Profile", "Photoshop", "Exif IFD0", "Exif SubIFD", "GPS");
		assertThat(location(read.metadata())).isNotNull();
	}

	@Test
	void PNG는_eXIf_조각의_목록_이름을_남긴다() {
		byte[] photo = TestImages.withPngGps(TestImages.rgbPng(100, 100), 37.566535, 126.977969, "2026:10:29 12:30:00");

		assertThat(PhotoMetadataReader.read(photo).blocks()).containsExactly("Exif IFD0", "Exif SubIFD", "GPS");
	}

	@Test
	void EXIF가_없는_사진은_빈_결과다() {
		// 자바가 만든 JPEG에는 JFIF 조각만 있다
		PhotoMetadataReader.Read jpeg = PhotoMetadataReader.read(TestImages.jpeg(100, 100));
		PhotoMetadataReader.Read png = PhotoMetadataReader.read(TestImages.rgbPng(100, 100));

		assertThat(jpeg.metadata().getDirectoryCount()).isZero();
		assertThat(jpeg.blocks()).containsExactly("JFIF");
		assertThat(png.metadata().getDirectoryCount()).isZero();
		assertThat(png.blocks()).isEmpty();
	}

	@Test
	void 깨진_TIFF는_예외_없이_깨졌다는_것만_남긴다() {
		// 바이트 순서 표시가 MM도 II도 아니다
		byte[] tiff = {'X', 'X', 0, 42, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0};

		PhotoMetadataReader.Read read = PhotoMetadataReader.read(TestImages.withExifTiff(TestImages.jpeg(100, 100), tiff));

		assertThat(read.blocks()).containsExactly("broken EXIF");
		assertThat(read.metadata().getDirectoryCount()).isZero();
	}

	@Test
	void 로그에_남기는_이름은_10가지까지다() {
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

	// 규칙에 맞는 JFIF 조각 내용: "JFIF\0" + 판 1.1 + 단위 0 + 밀도 1 x 1 + 미리보기 0 x 0
	private static byte[] jfif() {
		return new byte[] {'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0};
	}

	// JPEG의 EXIF 조각 내용("Exif\0\0" + TIFF)만 꺼낸다 (withExifTiff가 맨 앞에 넣은 조각)
	private static byte[] exifPayload(byte[] jpegWithExif) {
		int length = ((jpegWithExif[4] & 0xFF) << 8) | (jpegWithExif[5] & 0xFF);
		byte[] payload = new byte[length - 2];
		System.arraycopy(jpegWithExif, 6, payload, 0, payload.length);
		return payload;
	}

	// 자바가 만든 JPEG의 첫 조각(JFIF) 바로 뒤에 조각 하나를 넣는다
	private static byte[] afterJfif(byte[] jpeg, int marker, byte[] payload) {
		int at = 4 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(jpeg, 0, at);
		out.write(0xFF);
		out.write(marker);
		out.write((payload.length + 2) >> 8);
		out.write((payload.length + 2) & 0xFF);
		out.writeBytes(payload);
		out.write(jpeg, at, jpeg.length - at);
		return out.toByteArray();
	}

	// 목록 칸 하나: 번호, 형식, 개수, 값(4바이트 이하) 또는 값이 있는 자리
	private static int[] tag(int id, int type, int count, int valueOrOffset) {
		return new int[] {id, type, count, valueOrOffset};
	}

	// at 자리에서 시작하는 칸 n개짜리 목록이 끝나는 자리 (칸 수 2 + 칸 12씩 + 다음 목록 자리 4)
	private static int ifdEnd(int at, int entries) {
		return at + 2 + 12 * entries + 4;
	}

	// 시험용 TIFF 바이트 (큰 자리부터, MM). 자리는 TIFF 맨 앞에서 센다. 첫 목록(IFD0)은 8에 둔다
	private static final class Tiff {
		private final ByteBuffer bytes;

		Tiff(int size) {
			bytes = ByteBuffer.allocate(size);
			bytes.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8);
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
