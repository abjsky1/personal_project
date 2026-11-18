// 방문 통계(JM-86)의 값 객체를 두는 패키지
package backend.visit.model.vo;

import java.util.List;
import java.util.Locale;

// IP 위치 데이터(DB-IP)의 영어 시/도 이름을 우리말 시/도 이름으로 바꾼다 (JM-86)
// 이름은 사진 위치 통계(JM-85)의 행정동 경계 2026-10-01판과 같게 맞춘다: 광주와 전남은 합쳐진 전남광주통합특별시로 센다
// DB-IP 무료판은 시/도 코드 없이 영어 이름만 주고, 같은 곳도 표기가 여러 가지다 (Gyeonggi-do, North Chungcheong, Jeonbuk State 등).
// 그래서 소문자로 바꾸고 영문자만 남긴 뒤, 아래 표의 낱말이 들어 있는지 앞에서부터 본다 (북도, 남도처럼 긴 낱말이 먼저)
public final class Sido {

	public static final String ABROAD = "해외";
	public static final String UNKNOWN = "알 수 없음";

	private record Entry(String key, String name) {
	}

	private static final List<Entry> ENTRIES = List.of(
			new Entry("chungcheongbuk", "충청북도"), new Entry("northchungcheong", "충청북도"), new Entry("chungbuk", "충청북도"),
			new Entry("chungcheongnam", "충청남도"), new Entry("southchungcheong", "충청남도"), new Entry("chungnam", "충청남도"),
			new Entry("jeollabuk", "전북특별자치도"), new Entry("northjeolla", "전북특별자치도"), new Entry("jeonbuk", "전북특별자치도"),
			new Entry("jeollanam", "전남광주통합특별시"), new Entry("southjeolla", "전남광주통합특별시"), new Entry("jeonnam", "전남광주통합특별시"),
			new Entry("gyeongsangbuk", "경상북도"), new Entry("northgyeongsang", "경상북도"), new Entry("gyeongbuk", "경상북도"),
			new Entry("gyeongsangnam", "경상남도"), new Entry("southgyeongsang", "경상남도"), new Entry("gyeongnam", "경상남도"),
			new Entry("seoul", "서울특별시"), new Entry("busan", "부산광역시"), new Entry("daegu", "대구광역시"),
			new Entry("incheon", "인천광역시"), new Entry("gwangju", "전남광주통합특별시"), new Entry("daejeon", "대전광역시"),
			new Entry("ulsan", "울산광역시"), new Entry("sejong", "세종특별자치시"), new Entry("gyeonggi", "경기도"),
			new Entry("gangwon", "강원특별자치도"), new Entry("jeju", "제주특별자치도"));

	// 객체를 만들 일이 없는 도구 클래스라 생성자를 막아 둔다
	private Sido() {
	}

	// countryCode: 나라 코드(KR 등), subdivision: 영어 시/도 이름
	// 나라를 모르면 알 수 없음, 한국이 아니면 해외, 한국인데 시/도를 모르면 알 수 없음
	public static String of(String countryCode, String subdivision) {
		if (countryCode == null || countryCode.isBlank()) {
			return UNKNOWN;
		}
		if (!"KR".equalsIgnoreCase(countryCode)) {
			return ABROAD;
		}
		if (subdivision == null) {
			return UNKNOWN;
		}
		String letters = subdivision.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
		return ENTRIES.stream().filter(entry -> letters.contains(entry.key())).map(Entry::name).findFirst().orElse(UNKNOWN);
	}
}
