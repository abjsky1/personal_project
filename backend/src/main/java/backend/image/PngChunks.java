package backend.image;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;

// PNG 파일에서 그림을 펼치는 데 필요한 조각과 촬영 정보 조각만 남긴다. (JM-108, 기획서 6.2절)
//
// PNG 파일 = 서명 8바이트 + 조각(chunk)들. 조각 하나 = 길이 4바이트 + 종류 4바이트 + 내용(길이만큼) + CRC 4바이트
// 조각에는 그림 말고도 글자(tEXt, zTXt, iTXt), 색 정보(iCCP, gAMA 등), 만든 시각(tIME) 같은 덧붙인 정보가 들어간다.
// 이 앱은 그런 정보를 쓰지 않는다. 그런데 사진을 읽는 도구는 압축한 조각을 풀어 메모리에 쥔다.
// 자바의 PNG 읽기는 조각 하나를 256 KB까지 풀지만 조각 수에는 한도가 없고, metadata-extractor는 조각 하나를 크기 한도 없이 푼다.
// 그래서 작은 파일 하나가 서버 메모리를 다 쓰게 할 수 있다.
// 사진을 읽기 전에 이런 조각을 버린다. 조각마다 앞 8바이트(길이와 종류)만 보고 건너뛰므로 압축은 풀지 않는다.
@Slf4j
final class PngChunks {

	// 남기는 조각 (끝 표시 IEND는 아래에서 따로 다룬다)
	// IHDR(가로, 세로, 색 형식), PLTE(팔레트), tRNS(투명색), IDAT(그림 데이터): 그림을 펼치는 데 쓴다
	// eXIf: 촬영 위치와 시각 (metadata-extractor는 PNG의 GPS와 촬영 시각을 이 조각에서만 읽는다)
	// 나머지(글자, 색 정보, 만든 시각 등)는 자바가 그림을 펼칠 때 쓰지 않아서, 버려도 저장하는 사진은 바이트까지 같다 (JM-108에서 재어 확인)
	private static final Set<String> KEEP = Set.of("IHDR", "PLTE", "tRNS", "IDAT", "eXIf");

	// 끝 표시 IEND는 내용이 없어서 늘 같은 12바이트다: 길이 0, 종류 "IEND", CRC AE 42 60 82
	// CRC가 빠졌거나 길이가 틀린 IEND로 끝나는 PNG도 있어서, 자바의 PNG 읽기는 종류가 IEND면 길이와 CRC를 보지 않고 받는다.
	// 여기서도 종류만 보고, 남긴 파일에는 이 12바이트를 넣는다. 그대로 베끼면 틀린 길이를 따라가 깨진 파일로 거절하게 된다
	private static final String END = "IEND";
	private static final byte[] END_CHUNK = {0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82};

	private static final int SIGNATURE_LENGTH = 8;
	// 무슨 조각인지 알려면 있어야 하는 앞부분: 길이 4 + 종류 4
	private static final int HEADER_LENGTH = 8;
	// 조각에서 내용을 뺀 부분: 길이 4 + 종류 4 + CRC 4
	private static final int CHUNK_OVERHEAD = 12;
	// 로그에 남기는 버린 조각 종류의 가짓수. 종류가 아주 많은 파일도 로그 한 줄이 길어지지 않게 한다
	private static final int LOGGED_TYPES = 10;

	private PngChunks() {
	}

	// 서명으로 PNG인 것을 확인한 파일을 받는다.
	// 버릴 조각이 없고 IEND도 규칙대로면 받은 배열을 그대로 돌려주고(대부분의 PNG, 베끼지 않는다), 아니면 남길 조각만 담은 새 배열을 돌려준다.
	// 조각의 길이가 파일 끝을 넘으면(깨진 파일) INVALID_IMAGE
	static byte[] keepNeeded(byte[] png) {
		// 1. 조각을 훑으며 남길 바이트 수를 센다. IEND에서 멈춘다 (IEND 뒤의 바이트는 PNG의 일부가 아니라서 버린다)
		int keptBytes = SIGNATURE_LENGTH;
		int droppedChunks = 0;
		Set<String> droppedTypes = new TreeSet<>();
		// IEND가 시작하는 자리. IEND가 없으면 -1 (그런 파일은 다음 단계에서 자바가 읽지 못해 거절된다. 고치기 전과 같다)
		int endAt = -1;
		int pos = SIGNATURE_LENGTH;
		while (pos + HEADER_LENGTH <= png.length) {
			String type = typeAt(png, pos);
			if (END.equals(type)) {
				endAt = pos;
				keptBytes += END_CHUNK.length;
				break;
			}
			// 조각 하나(12바이트)도 들어가지 않는 끝부분은 조각이 아니라서 버린다
			if (pos + CHUNK_OVERHEAD > png.length) {
				break;
			}
			int end = chunkEnd(png, pos);
			if (KEEP.contains(type)) {
				keptBytes += end - pos;
			} else {
				droppedChunks++;
				if (droppedTypes.size() < LOGGED_TYPES) {
					droppedTypes.add(forLog(type));
				}
			}
			pos = end;
		}
		// 버린 것이 없으면 keptBytes가 파일 길이와 같다. IEND가 있으면 그 12바이트가 규칙대로인지도 본다
		if (keptBytes == png.length && (endAt < 0 || Arrays.equals(png, endAt, png.length, END_CHUNK, 0, END_CHUNK.length))) {
			return png;
		}
		// 무엇을 얼마나 버렸는지 남긴다. 보통 PNG도 색 정보 조각 등이 있어 자주 남는다.
		// 조각 수나 바이트가 아주 크면 일부러 만든 파일일 수 있어, 나중에 로그로 찾아볼 수 있게 한다
		// bytes: 원래 파일 크기 → 남긴 파일 크기 (조각을 버리지 않고 IEND만 고쳐 쓴 때는 count=0)
		log.info("PNG chunks dropped: count={}, types={}, bytes={} -> {}", droppedChunks, droppedTypes, png.length, keptBytes);

		// 2. 남길 조각만 새 배열에 베낀다. 크기를 1에서 셌으므로 맞는 크기로 한 번만 만든다
		byte[] kept = new byte[keptBytes];
		System.arraycopy(png, 0, kept, 0, SIGNATURE_LENGTH);
		int written = SIGNATURE_LENGTH;
		int beforeEnd = (endAt < 0) ? keptBytes : keptBytes - END_CHUNK.length;
		pos = SIGNATURE_LENGTH;
		// IEND 앞의 남길 조각을 같은 순서로 베낀다. 1에서 확인한 조각들만 다시 지나므로, 여기서는 깨진 조각을 만나지 않는다
		while (written < beforeEnd) {
			int end = chunkEnd(png, pos);
			if (KEEP.contains(typeAt(png, pos))) {
				System.arraycopy(png, pos, kept, written, end - pos);
				written += end - pos;
			}
			pos = end;
		}
		if (endAt >= 0) {
			System.arraycopy(END_CHUNK, 0, kept, written, END_CHUNK.length);
		}
		return kept;
	}

	// pos에서 시작하는 조각이 끝나는 자리 (= 다음 조각이 시작하는 자리)
	// 길이는 부호 없는 4바이트라 long으로 읽는다. int로 읽으면 0x80000000 이상이 음수가 되어 앞으로 되돌아갈 수 있다.
	private static int chunkEnd(byte[] png, int pos) {
		long length = ((png[pos] & 0xFFL) << 24) | ((png[pos + 1] & 0xFFL) << 16)
				| ((png[pos + 2] & 0xFFL) << 8) | (png[pos + 3] & 0xFFL);
		long end = pos + CHUNK_OVERHEAD + length;
		if (end > png.length) {
			log.warn("Broken PNG chunk: offset={}, length={}, fileBytes={}", pos, length, png.length);
			throw new BusinessException(ErrorCode.INVALID_IMAGE);
		}
		return (int) end;
	}

	// pos에서 시작하는 조각의 종류 (길이 다음의 4바이트)
	// ISO_8859_1: 바이트 하나를 글자 하나로 그대로 바꾼다. 영문자가 아닌 바이트도 다른 글자로 뭉개지지 않아 비교가 정확하다
	private static String typeAt(byte[] png, int pos) {
		return new String(png, pos + 4, 4, StandardCharsets.ISO_8859_1);
	}

	// 로그에 남길 종류 이름. 종류는 올린 사람이 마음대로 넣을 수 있는 4바이트라, 영문자 4개(PNG 규칙)가 아니면 "?"로 바꾼다.
	// 줄바꿈 같은 글자가 그대로 로그에 들어가면 가짜 로그 줄을 만들 수 있어서다.
	// 시험에서 부를 수 있게 private이 아니라 같은 패키지에서 부를 수 있게 둔다.
	static String forLog(String type) {
		for (int i = 0; i < type.length(); i++) {
			char c = type.charAt(i);
			if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
				return "?";
			}
		}
		return type;
	}
}
