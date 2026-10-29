package backend.image;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

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
	//
	// JPEG 파일은 FF D8(시작)로 시작하고, 그 뒤에 "조각(segment)"들이 이어진다.
	// EXIF는 FF E1(APP1) 조각에 "Exif" + TIFF 형식으로 들어간다. 여기서는 방향 값 하나만 넣은 최소한의 EXIF를 만든다.
	public static byte[] withExifOrientation(byte[] jpeg, int orientation) {
		byte[] tiff = {
				'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08, // TIFF 머리말: 큰 수부터 저장(MM), 첫 목록 위치 = 8
				0x00, 0x01, // 항목 1개
				0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, // 항목: Orientation(0x0112), 숫자 형식(SHORT), 1개
				0x00, (byte) orientation, 0x00, 0x00, // 값
				0x00, 0x00, 0x00, 0x00 // 다음 목록 없음
		};
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
