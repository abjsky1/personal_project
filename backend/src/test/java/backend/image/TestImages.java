package backend.image;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

// 테스트에서 쓸 사진 파일을 코드로 만드는 도구
// 실제 사진 파일을 저장소에 넣어 두지 않고, 필요한 크기, 형식의 사진을 그때그때 만든다.
public final class TestImages {

	private TestImages() {
	}

	// 가로 width × 세로 height 크기의 JPEG 파일 내용
	public static byte[] jpeg(int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(Color.ORANGE);
		graphics.fillRect(0, 0, width, height);
		graphics.dispose();
		return write(image, "jpg");
	}

	// 전체가 투명한 PNG 파일 내용
	public static byte[] transparentPng(int width, int height) {
		return write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png");
	}

	// 전체가 투명한 16비트 PNG 파일 내용 (색 채널 하나가 16비트라, 펼치면 화소 하나가 8바이트다)
	// ComponentColorModel: 화소를 채널(빨강, 초록, 파랑, 투명도)마다 숫자 하나로 담는 방식. TYPE_USHORT = 채널 하나에 16비트
	public static byte[] transparentPng16(int width, int height) {
		ComponentColorModel colorModel = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB),
				true, false, Transparency.TRANSLUCENT, DataBuffer.TYPE_USHORT);
		BufferedImage image = new BufferedImage(colorModel, colorModel.createCompatibleWritableRaster(width, height),
				false, null);
		return write(image, "png");
	}

	// 투명도가 없는 RGB PNG 파일 내용 (색 형식 2). 자바는 화소당 3바이트(TYPE_3BYTE_BGR)로 펼친다
	public static byte[] rgbPng(int width, int height) {
		return write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png");
	}

	// RGB PNG에 투명색(tRNS) 조각을 끼워 넣는다. "이 색인 화소는 투명으로 본다"는 표시라,
	// 자바는 이런 PNG를 투명도가 있는 화소당 4바이트 그림(TYPE_4BYTE_ABGR)으로 펼친다.
	// tRNS는 그림 데이터(IDAT) 앞에 와야 해서 IHDR 바로 뒤에 넣는다 (withChunk)
	public static byte[] withTransparentColor(byte[] rgbPng) {
		// RGB 투명색: 빨강, 초록, 파랑을 각각 2바이트로
		return withChunk(rgbPng, "tRNS", new byte[] {0, 1, 0, 2, 0, 3});
	}

	// PNG의 머리 조각(IHDR) 바로 뒤에 조각 하나를 끼워 넣는다. 여러 번 부르면 나중에 넣은 조각이 앞에 온다
	// 그림 데이터(IDAT)보다 앞에 와야 하는 조각(tRNS 등)도 있어서 맨 앞쪽에 넣는다
	public static byte[] withChunk(byte[] png, String type, byte[] data) {
		ByteArrayOutputStream chunk = new ByteArrayOutputStream();
		writeChunk(chunk, type, data);
		return insertAfterHeader(png, chunk.toByteArray());
	}

	// PNG의 IHDR 조각 바로 뒤(서명 8바이트 + IHDR 조각 25바이트 = 33번째 바이트)에 바이트를 그대로 끼워 넣는다
	// 조각 규칙에 맞지 않는 바이트(길이를 속인 조각 등)로 깨진 PNG를 만들 때도 쓴다
	public static byte[] insertAfterHeader(byte[] png, byte[] bytes) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(png, 0, 33);
		out.writeBytes(bytes);
		out.write(png, 33, png.length - 33);
		return out.toByteArray();
	}

	// PNG에 촬영 정보 조각(eXIf)으로 GPS 위치와 촬영 시각을 넣는다. dateTimeOriginal 형식은 EXIF 규칙대로 "2026:10:29 12:30:00"
	// 내용은 JPEG의 EXIF와 같은 TIFF 형식이다 (JPEG와 달리 앞에 "Exif\0\0" 표시가 없다)
	public static byte[] withPngGps(byte[] png, double latitude, double longitude, String dateTimeOriginal) {
		return withChunk(png, "eXIf", buildTiff(null, latitude, longitude, dateTimeOriginal));
	}

	// PNG 파일 안 조각의 종류를 차례대로 모은다 (시험에서 어떤 조각이 남았는지 볼 때)
	public static List<String> chunkTypes(byte[] png) {
		List<String> types = new ArrayList<>();
		int pos = 8;
		while (pos + 12 <= png.length) {
			int length = ByteBuffer.wrap(png, pos, 4).getInt();
			types.add(new String(png, pos + 4, 4, StandardCharsets.ISO_8859_1));
			pos += 12 + length;
		}
		return types;
	}

	// 회색 + 투명도 PNG 파일 내용 (색 형식 4). 자바는 화소당 2바이트의 TYPE_CUSTOM 그림으로 펼친다
	public static byte[] grayAlphaPng(int width, int height) {
		ComponentColorModel colorModel = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY),
				true, false, Transparency.TRANSLUCENT, DataBuffer.TYPE_BYTE);
		BufferedImage image = new BufferedImage(colorModel, colorModel.createCompatibleWritableRaster(width, height),
				false, null);
		return write(image, "png");
	}

	// 1비트 팔레트 PNG 파일 내용 (색 형식 3, 두 색). 자바는 TYPE_BYTE_BINARY(화소 8개를 1바이트에 담는 그림)로 펼친다
	// 두 색을 회색이 아닌 색으로 둬야 팔레트 PNG로 저장된다 (검정과 흰색이면 회색 PNG로 저장된다)
	public static byte[] onebitPalettePng(int width, int height) {
		IndexColorModel colors = new IndexColorModel(1, 2,
				new byte[] {(byte) 200, 10}, new byte[] {(byte) 150, 20}, new byte[] {90, (byte) 250});
		return write(new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY, colors), "png");
	}

	// 8비트 팔레트 PNG 파일 내용 (색 형식 3, 256색). 자바는 TYPE_BYTE_INDEXED(화소당 1바이트)로 펼친다
	public static byte[] palettePng(int width, int height) {
		return write(new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED), "png");
	}

	// 머리 부분(IHDR)만 있고 그림 데이터가 없는 PNG 파일 내용
	// 큰 사진을 실제로 만들지 않고도 "머리만 읽고 거절하는지"를 시험할 수 있다. 펼치려 하면 그림 데이터가 없어서 실패한다.
	// PNG 파일 = 서명 8바이트 + 조각(chunk)들. 조각 하나 = 길이 4 + 종류 4 + 내용 + CRC 4 (종류와 내용으로 계산한 검사 값)
	// IHDR 내용 13바이트: 가로 4, 세로 4, 채널 하나의 비트 수 1, 색 형식 1, 압축, 필터, 인터레이스 각 1 (모두 0)
	// 색 형식: 0 회색, 2 RGB, 3 팔레트, 4 회색 + 투명도, 6 RGB + 투명도
	public static byte[] pngHeaderOnly(int width, int height, int bitDepth, int colorType) {
		ByteBuffer header = ByteBuffer.allocate(13)
				.putInt(width).putInt(height)
				.put((byte) bitDepth).put((byte) colorType)
				.put((byte) 0).put((byte) 0).put((byte) 0);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
		writeChunk(out, "IHDR", header.array());
		writeChunk(out, "IEND", new byte[0]);
		return out.toByteArray();
	}

	private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
		byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
		CRC32 crc = new CRC32();
		crc.update(typeBytes);
		crc.update(data);
		out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
		out.writeBytes(typeBytes);
		out.writeBytes(data);
		out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
	}

	// JPEG에 EXIF 방향 정보(Orientation)를 넣는다. 휴대폰 카메라가 만드는 파일처럼 만들기 위해서다.
	// orientation 6 = "오른쪽으로 90도 돌려서 보여 줘" (휴대폰을 세워서 찍은 사진에 흔히 붙는 값)
	public static byte[] withExifOrientation(byte[] jpeg, int orientation) {
		return withExif(jpeg, orientation, null, null, null);
	}

	// JPEG에 GPS 위치와 촬영 시각을 넣는다. dateTimeOriginal 형식은 EXIF 규칙대로 "2026:10:29 12:30:00"
	public static byte[] withGps(byte[] jpeg, double latitude, double longitude, String dateTimeOriginal) {
		return withExif(jpeg, null, latitude, longitude, dateTimeOriginal);
	}

	// JPEG 파일은 FF D8(시작)로 시작하고, 그 뒤에 "조각(segment)"들이 이어진다.
	// EXIF는 FF E1(APP1) 조각에 "Exif" + TIFF 형식으로 들어간다.
	// TIFF 안에는 "목록(IFD)"이 있고, 목록의 항목 하나 = 태그 번호(무슨 정보인지) + 형식 + 개수 + 값(또는 값이 있는 위치)이다.
	// 여기서는 필요한 항목만 넣은 최소한의 EXIF를 만든다. (null인 값은 넣지 않는다)
	private static byte[] withExif(byte[] jpeg, Integer orientation, Double latitude, Double longitude, String dateTime) {
		return withExifTiff(jpeg, buildTiff(orientation, latitude, longitude, dateTime));
	}

	// JPEG에 TIFF 내용을 그대로 담은 EXIF 조각을 넣는다 (시험마다 칸을 직접 짠 TIFF를 넣을 때, JM-111)
	public static byte[] withExifTiff(byte[] jpeg, byte[] tiff) {
		byte[] exifHeader = {'E', 'x', 'i', 'f', 0x00, 0x00};
		int segmentLength = 2 + exifHeader.length + tiff.length;
		// JPEG 조각의 길이 칸은 2바이트라 65,535까지다. 넘으면 길이가 틀린 조각이 되므로 시험을 잘못 짠 것이다
		if (segmentLength > 0xFFFF) {
			throw new IllegalArgumentException("EXIF segment too long for JPEG: " + segmentLength);
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		// 1. 시작 표시 FF D8
		out.write(0xFF);
		out.write(0xD8);
		// 2. EXIF 조각: FF E1 + 길이(2바이트) + "Exif\0\0" + TIFF
		out.write(0xFF);
		out.write(0xE1);
		out.write(segmentLength >> 8);
		out.write(segmentLength & 0xFF);
		out.writeBytes(exifHeader);
		out.writeBytes(tiff);
		// 3. 원래 JPEG의 나머지. 자바가 만든 JPEG의 첫 조각(JFIF, FF E0)은 건너뛴다.
		// 휴대폰 사진처럼 EXIF 조각이 맨 앞에 오게 하려는 것이다. (JFIF와 EXIF가 함께 있으면 읽는 쪽에서 오류가 나기도 한다)
		int jfifLength = ((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF);
		int rest = 4 + jfifLength;
		out.write(jpeg, rest, jpeg.length - rest);
		return out.toByteArray();
	}

	// JPEG의 시작 표시(FF D8) 바로 뒤에 조각 하나를 끼워 넣는다 (XMP, ICC 같은 정보 조각, JM-111)
	// marker: 조각 종류 (예: 0xE1 = APP1, 0xE2 = APP2). 여러 번 부르면 나중에 넣은 조각이 앞에 온다
	public static byte[] withSegment(byte[] jpeg, int marker, byte[] payload) {
		int segmentLength = 2 + payload.length;
		if (segmentLength > 0xFFFF) {
			throw new IllegalArgumentException("segment too long for JPEG: " + segmentLength);
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(jpeg, 0, 2);
		out.write(0xFF);
		out.write(marker);
		out.write(segmentLength >> 8);
		out.write(segmentLength & 0xFF);
		out.writeBytes(payload);
		out.write(jpeg, 2, jpeg.length - 2);
		return out.toByteArray();
	}

	// TIFF 구조: 머리말 → 첫 목록(IFD0) → EXIF 목록(촬영 시각) → GPS 목록(위도, 경도) → 긴 값들이 들어가는 칸
	// 숫자는 큰 자리부터 저장한다(MM). ByteBuffer의 기본 순서와 같다.
	private static byte[] buildTiff(Integer orientation, Double latitude, Double longitude, String dateTime) {
		boolean hasDate = dateTime != null;
		boolean hasGps = latitude != null && longitude != null;
		int ifd0Entries = (orientation != null ? 1 : 0) + (hasDate ? 1 : 0) + (hasGps ? 1 : 0);

		// 각 부분이 TIFF 시작에서 몇 번째 바이트에 오는지 미리 계산한다. (목록 = 개수 2 + 항목 12씩 + 다음 목록 위치 4)
		int ifd0 = 8;
		int exifIfd = ifd0 + 2 + 12 * ifd0Entries + 4;
		int gpsIfd = exifIfd + (hasDate ? 2 + 12 + 4 : 0);
		int dateData = gpsIfd + (hasGps ? 2 + 12 * 4 + 4 : 0);
		int latitudeData = dateData + (hasDate ? 20 : 0);
		int longitudeData = latitudeData + 24;
		int total = hasGps ? longitudeData + 24 : latitudeData;

		ByteBuffer tiff = ByteBuffer.allocate(total);
		// 머리말: "MM" + 42(TIFF 표시) + 첫 목록 위치
		tiff.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(ifd0);

		// 첫 목록(IFD0): 방향, EXIF 목록 위치, GPS 목록 위치 (태그 번호가 작은 것부터)
		tiff.putShort((short) ifd0Entries);
		if (orientation != null) {
			entry(tiff, 0x0112, SHORT, 1, orientation << 16); // SHORT 값은 4바이트 칸의 앞 2바이트에 들어간다
		}
		if (hasDate) {
			entry(tiff, 0x8769, LONG, 1, exifIfd);
		}
		if (hasGps) {
			entry(tiff, 0x8825, LONG, 1, gpsIfd);
		}
		tiff.putInt(0); // 다음 목록 없음

		if (hasDate) {
			// EXIF 목록: 촬영 시각(DateTimeOriginal, 0x9003). 20글자(끝의 0 포함)라 값이 있는 위치를 적는다.
			tiff.putShort((short) 1);
			entry(tiff, 0x9003, ASCII, 20, dateData);
			tiff.putInt(0);
		}

		if (hasGps) {
			// GPS 목록: 북위/남위(N/S), 위도, 동경/서경(E/W), 경도
			// N, E 같은 한 글자는 끝의 0까지 2바이트라 4바이트 칸 안에 바로 넣는다.
			tiff.putShort((short) 4);
			entry(tiff, 0x0001, ASCII, 2, (latitude >= 0 ? 'N' : 'S') << 24);
			entry(tiff, 0x0002, RATIONAL, 3, latitudeData);
			entry(tiff, 0x0003, ASCII, 2, (longitude >= 0 ? 'E' : 'W') << 24);
			entry(tiff, 0x0004, RATIONAL, 3, longitudeData);
			tiff.putInt(0);
		}

		if (hasDate) {
			tiff.put((dateTime + "\0").getBytes(StandardCharsets.US_ASCII));
		}
		if (hasGps) {
			putDegreesMinutesSeconds(tiff, latitude);
			putDegreesMinutesSeconds(tiff, longitude);
		}
		return tiff.array();
	}

	// EXIF 값 형식 번호
	private static final int ASCII = 2;
	private static final int SHORT = 3;
	private static final int LONG = 4;
	private static final int RATIONAL = 5; // 분수 (분자 4바이트 + 분모 4바이트)

	// 목록 항목 하나 (12바이트): 태그 번호 2 + 형식 2 + 개수 4 + 값 또는 값이 있는 위치 4
	private static void entry(ByteBuffer tiff, int tag, int type, int count, int valueOrOffset) {
		tiff.putShort((short) tag).putShort((short) type).putInt(count).putInt(valueOrOffset);
	}

	// GPS 좌표는 십진수(37.566535)가 아니라 도, 분, 초 세 개의 분수로 저장한다. (37도 33분 59.53초)
	private static void putDegreesMinutesSeconds(ByteBuffer tiff, double value) {
		double abs = Math.abs(value);
		int degrees = (int) abs;
		double minutesWithFraction = (abs - degrees) * 60;
		int minutes = (int) minutesWithFraction;
		double seconds = (minutesWithFraction - minutes) * 60;
		tiff.putInt(degrees).putInt(1);
		tiff.putInt(minutes).putInt(1);
		tiff.putInt((int) Math.round(seconds * 10000)).putInt(10000);
	}

	// 파일 내용 안에 EXIF 조각 표시("Exif\0\0")가 있는지 확인한다.
	public static boolean containsExif(byte[] data) {
		byte[] marker = {'E', 'x', 'i', 'f', 0x00, 0x00};
		outer:
		for (int i = 0; i <= data.length - marker.length; i++) {
			for (int j = 0; j < marker.length; j++) {
				if (data[i + j] != marker[j]) {
					continue outer;
				}
			}
			return true;
		}
		return false;
	}

	private static byte[] write(BufferedImage image, String format) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(image, format, out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
