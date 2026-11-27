package backend.image;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.drew.imaging.tiff.TiffProcessingException;
import com.drew.imaging.tiff.TiffReader;
import com.drew.lang.ByteArrayReader;
import com.drew.lang.RandomAccessReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.ExifTiffHandler;
import com.drew.metadata.exif.GpsDirectory;

// 사진의 촬영 정보(EXIF)에서 이 앱이 쓰는 값, 촬영 위치와 시각만 읽는다. (JM-111, 기획서 6.2절)
//
// EXIF = TIFF 형식의 목록(IFD)들. 목록의 칸(태그) 하나 = 번호 + 형식 + 개수 + 값(또는 값이 있는 자리)
// 이 앱이 쓰는 칸은 일곱 개뿐이다
//   EXIF 목록: 촬영 시각 0x9003, 초 아래 자리 0x9291, 시간대 0x9011
//   GPS 목록: 1 북위/남위, 2 위도, 3 동경/서경, 4 경도
//
// 전에는 metadata-extractor의 ImageMetadataReader로 사진 정보를 통째로 읽었다 (EXIF 말고도 XMP, ICC 등 열 가지가 넘는 읽기).
// 그 읽기는 칸마다 값을 새로 베끼고 목록마다 디렉터리를 새로 만드는데, 이 앱이 쓰지 않는 칸까지 모두 그렇게 한다.
// 그래서 EXIF를 이상하게 만든 사진은 파일이 작아도 메모리를 아주 많이 쓸 수 있다.
// 여기서는 EXIF 한 덩어리만 꺼내 라이브러리의 TIFF 읽기(TiffReader)를 직접 부르고,
// 칸을 받는 손잡이(NeededTags)가 일곱 칸만 베끼게 한다. 나머지 칸은 베끼지 않고 건너뛴다.
// 위치와 시각을 계산하는 일은 전처럼 라이브러리(GpsDirectory, ExifSubIFDDirectory)가 한다 → 값은 전과 같다.
final class PhotoMetadataReader {

	// 읽은 결과
	// metadata: EXIF 목록(IFD0), EXIF 목록, GPS 목록만 든 Metadata. 각 목록에는 위의 일곱 칸만 있다
	// blocks: 사진에 들어 있던 정보 덩어리의 이름 (값은 없다). 로그에 남겨 기기, 올린 방법마다 무엇이 지워져 오는지 본다
	//   예: 아이폰 보관함에서 고른 사진 [Exif IFD0, Exif SubIFD, GPS, Makernote, Exif Thumbnail, ICC Profile]
	//       브라우저에서 바로 찍은 사진 [JFIF, Exif IFD0, Exif SubIFD, ICC Profile, Photoshop] (트러블슈팅 2026-10-30)
	record Read(Metadata metadata, List<String> blocks) {
	}

	// 값을 베낄 칸의 가장 큰 값 크기. 일곱 칸은 길어야 24바이트다 (위도, 경도 = 분수 3개 x 8바이트)
	static final int MAX_VALUE_BYTES = 64;
	// 로그에 남기는 이름의 가짓수. 조각이 아주 많은 사진도 로그 한 줄이 길어지지 않게 한다 (PngChunks와 같다)
	static final int MAX_BLOCKS = 10;

	private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
	// JPEG 조각 종류: SOS(그림 데이터 시작, 여기부터는 조각 구조가 아니다), EOI(끝)
	private static final int SOS = 0xDA;
	private static final int EOI = 0xD9;

	private PhotoMetadataReader() {
	}

	// 서명으로 JPEG나 PNG인 것을 확인한 사진을 받는다 (PNG는 PngChunks가 쓰지 않는 조각을 버린 뒤).
	// EXIF가 없으면 빈 Metadata
	static Read read(byte[] photo) {
		// 사진 안에서 만난 차례대로의 이름. EXIF 덩어리 자리에는 EXIF_PLACE를 두었다가, 아래에서 읽은 목록의 이름으로 바꾼다
		// LinkedHashSet: 넣은 차례를 지키고 같은 이름은 한 번만 담는다 (조각이 아주 많아도 이름 가짓수만큼만 쥔다)
		Set<String> found = new LinkedHashSet<>();
		byte[] tiff = startsWith(photo, 0, PNG_SIGNATURE) ? pngExif(photo, found) : jpegExif(photo, found);

		Metadata metadata = new Metadata();
		List<String> exifNames = new ArrayList<>();
		if (tiff != null) {
			NeededTags handler = new NeededTags(metadata);
			boolean broken = false;
			try {
				new TiffReader().processTiff(new ByteArrayReader(tiff), handler, 0);
			} catch (TiffProcessingException | IOException e) {
				// TIFF가 깨져서 중간에 멈췄다. 전에 쓰던 ExifReader.extract처럼 그때까지 읽은 값은 그대로 쓴다
				broken = true;
			}
			for (Directory directory : metadata.getDirectories()) {
				exifNames.add(directory.getName());
			}
			if (handler.makernote) {
				exifNames.add("Makernote");
			}
			if (handler.thumbnail) {
				exifNames.add("Exif Thumbnail");
			}
			if (broken) {
				exifNames.add("broken EXIF");
			}
		}

		Set<String> blocks = new LinkedHashSet<>();
		for (String name : found) {
			if (name.equals(EXIF_PLACE)) {
				blocks.addAll(exifNames);
			} else {
				blocks.add(name);
			}
		}
		return new Read(metadata, blocks.stream().limit(MAX_BLOCKS).toList());
	}

	// EXIF 덩어리를 만난 자리 표시 (로그 이름이 아니다)
	private static final String EXIF_PLACE = "\0exif";

	// JPEG = FF D8(시작) + 조각들. 조각 하나 = FF + 종류 1바이트 + 길이 2바이트(길이 칸 포함) + 내용
	// 그림 데이터(SOS)가 나오기 전까지 조각을 훑으며 이름을 모으고, "Exif\0\0"로 시작하는 첫 APP1 조각의 TIFF를 돌려준다.
	// 조각과 조각 사이의 다른 바이트는 metadata-extractor처럼 건너뛴다 (FF와 그 뒤의 0이 아닌 바이트를 조각 표시로 본다)
	// 조각의 길이가 파일 끝을 넘으면 거기서 멈춘다 (전에도 이런 파일은 정보 없이 올라갔다)
	private static byte[] jpegExif(byte[] jpeg, Set<String> found) {
		byte[] tiff = null;
		int pos = 2;
		while (pos + 1 < jpeg.length) {
			int marker = jpeg[pos + 1] & 0xFF;
			if ((jpeg[pos] & 0xFF) != 0xFF || marker == 0xFF || marker == 0x00) {
				pos++;
				continue;
			}
			if (marker == SOS || marker == EOI || pos + 4 > jpeg.length) {
				break;
			}
			int length = ((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF);
			int start = pos + 4;
			int end = pos + 2 + length;
			if (length < 2 || end > jpeg.length) {
				break;
			}
			String name = segmentName(marker, jpeg, start, end);
			if ("Exif".equals(name)) {
				// EXIF 조각은 이름 대신, read에서 읽은 목록의 이름(Exif IFD0 등)으로 바꿀 자리를 둔다
				found.add(EXIF_PLACE);
				if (tiff == null) {
					tiff = Arrays.copyOfRange(jpeg, start + 6, end);
				}
			} else if (name != null) {
				found.add(name);
			}
			pos = end;
		}
		return tiff;
	}

	// JPEG의 정보 조각(APP0 ~ APP15, 주석) 이름. 그림을 펼치는 데 쓰는 조각(양자화 표, 허프만 표 등)은 null
	// 이름은 전에 쓰던 metadata-extractor의 디렉터리 이름에 맞췄다 (로그를 전과 견줄 수 있게)
	private static String segmentName(int marker, byte[] jpeg, int start, int end) {
		if (marker == 0xFE) {
			return "Comment";
		}
		if (marker < 0xE0 || marker > 0xEF) {
			return null;
		}
		String head = new String(jpeg, start, Math.min(end - start, 35), StandardCharsets.ISO_8859_1);
		return switch (marker) {
			case 0xE0 -> head.startsWith("JFIF\0") ? "JFIF" : head.startsWith("JFXX\0") ? "JFXX" : "APP0";
			case 0xE1 -> head.startsWith("Exif\0\0") ? "Exif"
					: head.startsWith("http://ns.adobe.com/xap/1.0/") ? "XMP"
					: head.startsWith("http://ns.adobe.com/xmp/extension/") ? "XMP Extension" : "APP1";
			case 0xE2 -> head.startsWith("ICC_PROFILE\0") ? "ICC Profile" : head.startsWith("MPF\0") ? "MPF" : "APP2";
			case 0xEC -> head.startsWith("Ducky") ? "Ducky" : "APP12";
			case 0xED -> head.startsWith("Photoshop 3.0") ? "Photoshop" : "APP13";
			case 0xEE -> head.startsWith("Adobe") ? "Adobe JPEG" : "APP14";
			default -> "APP" + (marker - 0xE0);
		};
	}

	// PNG = 서명 8바이트 + 조각들. 조각 하나 = 길이 4 + 종류 4 + 내용 + CRC 4
	// 첫 eXIf 조각의 내용(TIFF)을 돌려준다. PNG의 eXIf에는 JPEG와 달리 "Exif\0\0" 표시가 없다
	private static byte[] pngExif(byte[] png, Set<String> found) {
		int pos = PNG_SIGNATURE.length;
		while (pos + 12 <= png.length) {
			long length = ((png[pos] & 0xFFL) << 24) | ((png[pos + 1] & 0xFFL) << 16)
					| ((png[pos + 2] & 0xFFL) << 8) | (png[pos + 3] & 0xFFL);
			long end = pos + 12 + length;
			String type = new String(png, pos + 4, 4, StandardCharsets.ISO_8859_1);
			if (end > png.length || "IEND".equals(type)) {
				return null;
			}
			if ("eXIf".equals(type)) {
				found.add(EXIF_PLACE);
				return Arrays.copyOfRange(png, pos + 8, pos + 8 + (int) length);
			}
			pos = (int) end;
		}
		return null;
	}

	private static boolean startsWith(byte[] data, int from, byte[] prefix) {
		return data.length - from >= prefix.length && Arrays.equals(data, from, from + prefix.length, prefix, 0, prefix.length);
	}

	// TiffReader가 목록과 칸을 하나씩 읽을 때마다 부르는 손잡이
	// metadata-extractor의 EXIF 손잡이(ExifTiffHandler)를 이어받아, 들어갈 목록과 베낄 칸만 좁힌다.
	// 값을 실제로 읽어 담는 일은 그대로 라이브러리가 한다.
	// 시험에서 쓰려고 private이 아니라 같은 패키지에서 부를 수 있게 둔다.
	static final class NeededTags extends ExifTiffHandler {

		// 한 번씩만 들어간다. 같은 칸이 여러 번 나오거나 값이 여러 개여도 목록을 더 만들지 않는다
		private boolean exifEntered;
		private boolean gpsEntered;
		// 읽지는 않지만 있었는지만 남기는 것: 제조사 칸(카메라 회사가 남기는 정보), IFD1(작은 미리보기 그림)
		boolean makernote;
		boolean thumbnail;

		NeededTags(Metadata metadata) {
			super(metadata, null, 0);
		}

		// 하위 목록으로 들어갈지. IFD0에서 EXIF 목록(0x8769)과 GPS 목록(0x8825)으로 한 번씩만 들어간다.
		// 그 밖의 하위 목록(0x014A 하위 목록들, 호환성 목록 등)은 들어가지 않는다 → 디렉터리는 많아야 셋
		@Override
		public boolean tryEnterSubIfd(int tagId) {
			if (!(_currentDirectory instanceof ExifIFD0Directory)) {
				return false;
			}
			if (tagId == ExifIFD0Directory.TAG_EXIF_SUB_IFD_OFFSET && !exifEntered) {
				exifEntered = true;
				pushDirectory(ExifSubIFDDirectory.class);
				return true;
			}
			if (tagId == ExifIFD0Directory.TAG_GPS_INFO_OFFSET && !gpsEntered) {
				gpsEntered = true;
				pushDirectory(GpsDirectory.class);
				return true;
			}
			return false;
		}

		// 목록 끝의 "다음 목록"으로 가지 않는다. IFD0 뒤의 IFD1(미리보기 그림)은 있었다는 것만 남긴다
		@Override
		public boolean hasFollowerIfd() {
			if (_currentDirectory instanceof ExifIFD0Directory) {
				thumbnail = true;
			}
			return false;
		}

		// 칸 하나를 어떻게 할지. true = "이 칸은 처리했다" → 라이브러리가 값을 베끼지 않고 넘어간다
		// false = 라이브러리가 값을 읽어 담는다 (쓰는 일곱 칸이고 값이 64바이트 이하일 때만)
		// 라이브러리가 따로 읽던 칸(제조사 칸, ICC, XMP, IPTC 등)도 여기서 모두 건너뛴다
		@Override
		public boolean customProcessTag(int tagOffset, Set<Integer> processedIfdOffsets, int tiffHeaderOffset,
				RandomAccessReader reader, int tagId, int byteCount) {
			if (_currentDirectory instanceof ExifSubIFDDirectory && tagId == ExifSubIFDDirectory.TAG_MAKERNOTE) {
				makernote = true;
			}
			return !(byteCount <= MAX_VALUE_BYTES && isNeeded(tagId));
		}

		private boolean isNeeded(int tagId) {
			if (_currentDirectory instanceof GpsDirectory) {
				return tagId == GpsDirectory.TAG_LATITUDE_REF || tagId == GpsDirectory.TAG_LATITUDE
						|| tagId == GpsDirectory.TAG_LONGITUDE_REF || tagId == GpsDirectory.TAG_LONGITUDE;
			}
			if (_currentDirectory instanceof ExifSubIFDDirectory) {
				return tagId == ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL
						|| tagId == ExifSubIFDDirectory.TAG_SUBSECOND_TIME_ORIGINAL
						|| tagId == ExifSubIFDDirectory.TAG_TIME_ZONE_ORIGINAL;
			}
			return false;
		}

		// 깨진 칸마다 라이브러리가 남기는 오류 문구는 이 앱이 쓰지 않아서 쌓지 않는다 (칸이 아주 많으면 문구만 수만 개가 된다)
		@Override
		public void error(String message) {
		}

		@Override
		public void warn(String message) {
		}
	}
}
