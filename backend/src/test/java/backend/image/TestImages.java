package backend.image;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

// 테스트에서 쓸 사진 파일을 코드로 만드는 도구
// 실제 사진 파일을 저장소에 넣어 두지 않고, 필요한 크기·형식의 사진을 그때그때 만든다.
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
		byte[] tiff = buildTiff(orientation, latitude, longitude, dateTime);
		byte[] exifHeader = {'E', 'x', 'i', 'f', 0x00, 0x00};
		int segmentLength = 2 + exifHeader.length + tiff.length;

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

	// TIFF 구조: 머리말 → 첫 목록(IFD0) → EXIF 목록(촬영 시각) → GPS 목록(위도·경도) → 긴 값들이 들어가는 칸
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

	// GPS 좌표는 십진수(37.566535)가 아니라 도·분·초 세 개의 분수로 저장한다. (37도 33분 59.53초)
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
