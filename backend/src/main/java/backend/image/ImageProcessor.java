package backend.image;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.StreamSupport;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.ImageInputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;

import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.geometry.Positions;

// 올린 사진을 저장하기 전에 다듬는다. (기획서 6.2절)
// 순서: 1. 진짜 사진인지 검사 → 2. PNG면 쓰지 않는 조각 버리기 → 3. 화소 수와 펼친 크기 검사 → 4. 위치, 촬영 시각 꺼내기
//       → 5. 펼치기(큰 사진은 긴 변 2560으로 줄이면서) + 방향 바로잡기(회전 보정)
//       → 6. 크기 줄이기 + JPEG로 새로 저장(EXIF 제거)
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
	// 펼칠 때의 최대 크기: 긴 변이 이보다 긴 사진은 펼치면서 바로 2560px로 줄인다. (JM-94)
	// 원래 크기로 다 펼친 뒤 돌리면, 큰 세로 사진은 원본 크기 그림이 한 장 더 생겨 메모리와 시간을 많이 쓴다.
	// 2560 = 저장할 큰 사진(1280)의 두 배. 마지막에 한 번 더 반으로 줄여 1280을 만든다.
	private static final int READ_MAX_SIZE = 2560;
	// 한 변의 최대 길이 (JM-105). JPEG가 담을 수 있는 가장 긴 변과 같다.
	// PNG는 펼칠 때 한 줄짜리 버퍼를 세 개 더 만든다. 50,000,000 × 1 같은 사진은 한 줄이 곧 사진 전체라 버퍼만 4억 5천만 바이트가 된다.
	private static final int MAX_SIDE = 65_535;
	// 자바 그리기(Java2D)가 바로 줄일 수 있는 형식 (JM-105, 재어서 확인)
	// JPEG(3BYTE_BGR), 투명 PNG(4BYTE_ABGR), 8비트 회색 PNG(BYTE_GRAY), 8비트 팔레트 PNG(BYTE_INDEXED)
	private static final Set<Integer> DIRECT_TYPES = Set.of(BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_4BYTE_ABGR,
			BufferedImage.TYPE_BYTE_GRAY, BufferedImage.TYPE_BYTE_INDEXED);
	// 그 밖의 형식(16비트 PNG, 회색+투명 PNG, 1 ~ 4비트 팔레트 PNG, CMYK JPEG 등)은 Thumbnailator가 줄이려고 그릴 때
	// 자바가 원본 전체를 화소당 4바이트 그림으로 바꾸고, 바꾸는 동안 같은 크기의 버퍼를 하나 더 만든다 → 화소당 8바이트를 더 쓴다
	private static final int CONVERSION_BYTES_PER_PIXEL = 8;

	// 파일 맨 앞 몇 바이트(매직 바이트): 파일 형식마다 정해진 "서명" 같은 값
	// 확장자(.jpg)나 브라우저가 알려 준 형식은 바꿔치기할 수 있지만, 파일 내용의 시작 부분은 실제 형식을 보여 준다.
	private static final int[] JPEG_SIGNATURE = {0xFF, 0xD8, 0xFF};
	private static final int[] PNG_SIGNATURE = {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

	// 촬영 시각을 읽을 때 쓰는 시간대
	private static final TimeZone KOREA = TimeZone.getTimeZone("Asia/Seoul");

	// 최대 화소 수 (application.properties의 app.image.max-pixels)
	private final long maxPixels;
	// 펼친 그림의 최대 바이트 (application.properties의 app.image.max-decoded-bytes, JM-105)
	private final long maxDecodedBytes;

	public ImageProcessor(@Value("${app.image.max-pixels}") long maxPixels,
			@Value("${app.image.max-decoded-bytes}") long maxDecodedBytes) {
		this.maxPixels = maxPixels;
		this.maxDecodedBytes = maxDecodedBytes;
	}

	// synchronized: 사진 처리를 한 번에 한 장씩만 한다. (다른 요청은 앞 사진이 끝날 때까지 줄 서서 기다린다)
	// 사진을 펼치면 화소 수만큼 메모리를 쓴다. 큰 사진(약 5,000만 화소)은 긴 변 2560으로 줄여 읽어도
	// 읽는 동안 한 번은 원본 크기로 펼쳐져 150 MB쯤 든다.
	// 메모리 2GB 서버에서 큰 사진 여러 장을 동시에 펼치면 메모리가 모자라 서버가 멈출 수 있어서 막는다.
	// 보통 사진은 1초 안에 끝나므로 사용자가 적은 지금은 기다림이 거의 없다.
	public synchronized ProcessedImage process(MultipartFile file) {
		byte[] original = readBytes(file);

		// 1. 파일 내용이 진짜 JPEG, PNG인지 검사한다.
		if (!startsWith(original, JPEG_SIGNATURE) && !startsWith(original, PNG_SIGNATURE)) {
			// 어떤 형식이 얼마나 들어오는지(예: 아이폰 HEIC) 나중에 로그로 세어 볼 수 있게 남긴다.
			log.warn("Unsupported image: contentType={}, bytes={}", file.getContentType(), original.length);
			throw new BusinessException(ErrorCode.INVALID_IMAGE);
		}

		// 2. PNG면 그림을 펼치는 데 필요한 조각과 촬영 정보 조각(eXIf)만 남긴다 (JM-108, PngChunks). 아래 단계는 모두 남긴 파일로 한다.
		// 글자나 색 정보 같은 조각은 이 앱이 쓰지 않는데, 압축한 조각은 풀면 수백 MB가 되기도 한다.
		// 3의 자바 PNG 읽기와 4의 metadata-extractor가 이런 조각을 한도 없이 풀다 메모리가 모자라지 않게, 읽기 전에 버린다.
		if (startsWith(original, PNG_SIGNATURE)) {
			original = PngChunks.keepNeeded(original);
		}

		// 3. 사진 전체를 펼치기 전에 머리 부분만 먼저 읽어서, 화소가 너무 많거나 펼치면 너무 큰 사진을 거절한다.
		// 통과하면 긴 변의 길이를 받아 둔다 (5에서 줄여 읽을지 정할 때 쓴다).
		int longSide = checkPixelCount(original);

		// 4. EXIF를 지우기 전에, 원본에서 위치와 촬영 시각을 꺼내 둔다. (EXIF를 지운 뒤에는 읽을 수 없다)
		PhotoMetadata metadata = readMetadata(original);

		try {
			// 5. 사진을 펼치면서 EXIF의 방향 정보를 반영한다.
			// 휴대폰은 세로 사진도 가로로 저장하고 "90도 돌려서 보여 줘"라는 방향 정보만 EXIF에 적어 둔다.
			// EXIF를 지우기 전에 이 방향대로 실제로 돌려 두지 않으면, 세로 사진이 눕혀져 보인다.
			// 큰 사진은 펼치면서 긴 변 2560으로 줄이고, 줄인 그림을 돌린다 (readUpright).
			BufferedImage upright = readUpright(original, longSide);
			BufferedImage rgb = removeTransparency(upright);

			// 6. 크기를 줄여 JPEG로 새로 저장한다.
			// 그림(픽셀)만 새 파일로 쓰고 EXIF는 옮겨 적지 않으므로, 이 과정에서 위치 정보 등이 모두 사라진다.
			byte[] main = toJpeg(fitWithin(rgb, MAIN_MAX_SIZE));
			byte[] thumbnail = toJpeg(Thumbnails.of(rgb).size(THUMBNAIL_SIZE, THUMBNAIL_SIZE).crop(Positions.CENTER));
			return new ProcessedImage(main, thumbnail, metadata);
		} catch (IOException e) {
			// 자바의 PNG 읽기(PNGImageReader)는 펼치다 메모리가 모자라면 OutOfMemoryError를 IOException으로 감싸서 던진다 (JM-105에서 찾음).
			// 사진이 잘못된 것이 아니라 서버 메모리가 모자란 것이라, "읽을 수 없는 사진"(400)으로 바꾸지 않는다.
			// JPEG에서 메모리가 모자랄 때와 똑같이 그대로 던진다 → 에러 화면(500), 스택 트레이스가 있는 에러 로그, 에러 경보
			if (e.getCause() instanceof OutOfMemoryError outOfMemory) {
				throw outOfMemory;
			}
			// 형식은 맞지만 읽을 수 없는 사진 (손상된 파일 등). 메시지만으로는 까닭을 모를 때가 있어 원인(cause)도 남긴다.
			log.warn("Unreadable image: {}, cause={}", e.getMessage(), String.valueOf(e.getCause()));
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

	// 사진 파일의 머리 부분(헤더)만 읽어 가로 × 세로와 펼쳤을 때의 크기를 확인한다. 사진 전체를 메모리에 펼치지 않는다.
	// 1. 화소 수(가로 × 세로)가 한도를 넘으면 IMAGE_TOO_LARGE
	// 2. 한 변이 65,535를 넘거나, 펼친 크기(가로 × 세로 × 화소 하나의 바이트)가 한도를 넘으면 IMAGE_DECODED_TOO_LARGE (JM-105)
	//    화소 수가 같아도 형식에 따라 펼친 크기가 다르다. 5,000만 화소 JPEG는 1억 5천만 바이트지만,
	//    같은 화소의 투명 PNG는 2억, 16비트 투명 PNG는 4억 바이트라 운영 서버의 힙(512 MB)에서는 펼치다 메모리가 모자란다.
	// 한도 안이면 긴 변의 길이(가로와 세로 중 긴 쪽)를 돌려준다.
	private int checkPixelCount(byte[] data) {
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

				if (Math.max(width, height) > MAX_SIDE) {
					log.warn("Image side too long: {}x{}", width, height);
					throw new BusinessException(ErrorCode.IMAGE_DECODED_TOO_LARGE);
				}

				ImageTypeSpecifier type = decodeType(reader);
				if (type == null) {
					// 펼칠 형식이 없다 = 자바가 펼칠 수 없는 사진 (예: 색 채널이 2개인 JPEG). 펼치는 단계에서도 어차피 실패한다
					log.warn("Unsupported image type: {}x{}", width, height);
					throw new BusinessException(ErrorCode.INVALID_IMAGE);
				}
				int bytesPerPixel = bytesPerPixel(type);
				if ((long) width * height * bytesPerPixel > maxDecodedBytes) {
					log.warn("Image too large when decoded: {}x{}, {} bytes per pixel (type {})",
							width, height, bytesPerPixel, type.getBufferedImageType());
					throw new BusinessException(ErrorCode.IMAGE_DECODED_TOO_LARGE);
				}
				return Math.max(width, height);
			} finally {
				// 사진을 읽는 도구가 잡고 있던 자원을 돌려준다.
				reader.dispose();
			}
		} catch (IOException e) {
			log.warn("Unreadable image header: {}", e.getMessage());
			throw new BusinessException(ErrorCode.INVALID_IMAGE);
		}
	}

	// 자바가 이 사진을 펼칠 때 쓰는 형식. 따로 정하지 않으면 getImageTypes의 첫째로 펼친다 (Thumbnailator도 따로 정하지 않는다)
	// 펼칠 형식이 하나도 없으면 null
	// PNG는 형식을 정하려고 머리 뒤의 조각(투명색 tRNS, 팔레트 PLTE)까지 읽어서, 깨진 파일이면 IOException이 아닌 예외
	// (IndexOutOfBoundsException 등)도 난다. 펼칠 때는 자바가 이런 예외를 IIOException으로 감싸 주지만 여기서는 감싸 주지 않으므로,
	// 직접 IIOException으로 감싸 "읽을 수 없는 사진"(400)으로 보낸다. 그대로 두면 에러 화면(500)과 에러 경보가 된다 (JM-105)
	private static ImageTypeSpecifier decodeType(ImageReader reader) throws IOException {
		try {
			Iterator<ImageTypeSpecifier> types = reader.getImageTypes(0);
			return types.hasNext() ? types.next() : null;
		} catch (RuntimeException e) {
			throw new IIOException("Unreadable image type: " + e, e);
		}
	}

	// 펼친 그림에서 화소 하나가 차지하는 바이트 (JM-105)
	// getPixelSize: 화소 하나의 비트 수. 8비트가 1바이트이고, 8로 나누어떨어지지 않으면 올린다 (팔레트 PNG의 1, 2, 4비트 → 1바이트)
	// 예: JPEG 24비트 → 3, 투명 PNG 32비트 → 4, 16비트 투명 PNG 64비트 → 8, 인쇄용 CMYK JPEG 32비트 → 4
	// 자바 그리기가 바로 줄이지 못하는 형식이면, 줄이는 동안 생기는 복사본 몫(화소당 8바이트)을 더한다 (DIRECT_TYPES)
	//   예: 16비트 투명 PNG 8 + 8 = 16, 회색+투명 PNG 2 + 8 = 10, CMYK JPEG 4 + 8 = 12
	private static int bytesPerPixel(ImageTypeSpecifier type) {
		int decoded = (type.getColorModel().getPixelSize() + 7) / 8;
		if (DIRECT_TYPES.contains(type.getBufferedImageType())) {
			return decoded;
		}
		return decoded + CONVERSION_BYTES_PER_PIXEL;
	}

	// EXIF에서 GPS 위치와 촬영 시각을 꺼낸다. (metadata-extractor 라이브러리)
	// 사진에 정보가 없거나 읽다가 문제가 생겨도 글은 올라가야 하므로, 예외를 던지지 않고 비어 있는 결과를 돌려준다.
	private PhotoMetadata readMetadata(byte[] original) {
		try {
			// Metadata: 사진 안의 정보 묶음. 그 안에 GPS, EXIF 같은 "디렉터리"(정보 종류별 칸)가 들어 있다.
			Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(original));

			// GPS 칸에서 위도, 경도를 꺼낸다. 칸이 없거나, 값이 0,0(위치를 못 잡은 사진에 가끔 들어가는 값)이면 위치 없음
			GpsDirectory gps = metadata.getFirstDirectoryOfType(GpsDirectory.class);
			GeoLocation location = (gps == null) ? null : gps.getGeoLocation();
			boolean hasLocation = location != null && !location.isZero();

			// EXIF 칸에서 촬영 시각(DateTimeOriginal)을 꺼낸다.
			// EXIF의 시각에는 시간대가 없는 경우가 많아서(예: "2026:10:29 12:30:00"), 한국 시간으로 보고 읽는다.
			ExifSubIFDDirectory exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
			Date takenAt = (exif == null) ? null : exif.getDateOriginal(KOREA);

			// 사진에 어떤 정보가 들어 있었는지 남긴다. 값(좌표, 시각)은 남기지 않고, 정보의 종류와 있음/없음만 남긴다.
			// → 기기, 브라우저마다 무엇이 지워진 채로 올라오는지(예: 아이폰 Safari) 로그로 확인하는 운영 지표
			// directories 예: [JPEG, Exif IFD0, Exif SubIFD, GPS, ...] — 이름만 있는 목록이라 개인정보가 아니다.
			log.info("Photo metadata: directories={}, gps={}, takenAt={}",
					directoryNames(metadata), hasLocation, takenAt != null);

			return new PhotoMetadata(
					hasLocation ? location.getLatitude() : null,
					hasLocation ? location.getLongitude() : null,
					takenAt == null ? null : takenAt.toInstant());
		} catch (ImageProcessingException | IOException e) {
			log.warn("Unreadable photo metadata: {}", e.getMessage());
			return PhotoMetadata.EMPTY;
		}
	}

	// 사진 안에 들어 있는 정보 칸(디렉터리)의 이름 목록
	// getDirectories()는 목록(List)이 아니라 반복만 할 수 있는 Iterable이라, stream으로 바꿔서 이름만 모은다.
	private List<String> directoryNames(Metadata metadata) {
		return StreamSupport.stream(metadata.getDirectories().spliterator(), false)
				.map(Directory::getName)
				.toList();
	}

	// 사진을 펼치고 EXIF 방향대로 돌린 그림을 돌려준다. (Thumbnailator가 방향 정보를 읽어 돌린다)
	// 긴 변이 2560보다 길면 size(목표, 목표): 비율을 지킨 채 목표 안에 들어가게 먼저 줄이고, 줄인 그림을 돌린다.
	//   → 원본 크기 그림은 읽을 때 한 장만 생기고, 돌릴 때는 작은 그림(약 490만 화소)만 새로 만든다.
	//   목표는 보통 2560이고, 긴 변이 10240 이상인 아주 넓은 사진만 조금 크게 잡는다 (readTarget, JM-105)
	// 그보다 작으면 scale(1.0): 크기는 그대로 두고 돌리기만 한다. (size는 작은 사진을 2560까지 키워 버린다)
	// 테스트에서 읽은 크기를 확인하려고 private이 아니라 같은 패키지에서 부를 수 있게 둔다.
	BufferedImage readUpright(byte[] original, int longSide) throws IOException {
		Thumbnails.Builder<? extends InputStream> builder = Thumbnails.of(new ByteArrayInputStream(original));
		if (longSide > READ_MAX_SIZE) {
			int target = readTarget(longSide);
			return builder.size(target, target).asBufferedImage();
		}
		return builder.scale(1.0).asBufferedImage();
	}

	// 줄여 읽을 때 목표로 삼는 긴 변 (JM-105)
	// Thumbnailator는 크게 줄일 때 반씩 여러 번 줄이는데, 그동안 쓸 임시 그림을 하나 만든다.
	// 임시 그림의 긴 변 = "목표 × 2의 거듭제곱" 가운데 원본보다 짧은 가장 긴 값
	//   목표 2560, 긴 변 10241: 2560, 5120, 10240 가운데 10240 → 임시 그림이 원본만 하다 (원본 크기 그림이 한 장 더)
	//   목표 2561, 긴 변 10241: 2561, 5122 가운데 5122 (10244는 원본보다 길다) → 임시 그림이 원본 넓이의 4분의 1
	// 그래서 긴 변이 10240(2560 × 4) 이상이면 "긴 변 ÷ 2의 거듭제곱"(올림)을 목표로 삼아, 임시 그림을 원본 넓이의 4분의 1로 만든다.
	//   factor: (긴 변 ÷ 2560) 이하인 가장 큰 2의 거듭제곱 (10241 → 4, 20480 → 8). 목표는 2560 ~ 5120이 된다.
	// 10240보다 짧은 사진(휴대폰 사진 등)은 그대로 2560이다. 이 사진들까지 목표를 키우면 읽은 그림과 돌린 그림이 커져서
	// 메모리를 오히려 더 쓴다 (11-20 시험: 48MP, 50MP 세로 사진이 운영과 같은 조건에서 실패)
	static int readTarget(int longSide) {
		if (longSide < READ_MAX_SIZE * 4) {
			return READ_MAX_SIZE;
		}
		int factor = Integer.highestOneBit(longSide / READ_MAX_SIZE);
		return (longSide + factor - 1) / factor;
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

	// 정해 둔 크기, 자르기 설정대로 JPEG 파일 내용(바이트)을 만든다.
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
