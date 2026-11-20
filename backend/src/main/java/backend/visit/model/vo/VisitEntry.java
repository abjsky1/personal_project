package backend.visit.model.vo;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import backend.visit.model.entity.ClientApp;

// 방문의 첫 줄에만 남기는 "들어온 곳" (사용자 흐름, JM-92). 사람을 알아볼 수 있는 값이 들어가지 않게 만들 때 줄인다:
// 다른 사이트의 주소는 도메인만, utm 값은 정한 글자만 50자까지, 링크 표시(fbclid 등)는 이름만 두고 값은 버린다.
// record의 짧은 생성자(아래 VisitEntry { ... })가 값을 받을 때마다 이 규칙을 거치게 해서, 다른 길로 만들어도 규칙을 건너뛸 수 없다
public record VisitEntry(ClientApp app, String refHost, String utmSource, String utmMedium, String utmCampaign, String clickId) {

	// 주소에 붙어 오는 링크 표시의 이름. 앞에서부터 처음 있는 것 하나만 남긴다.
	// fbclid: 페이스북, 인스타그램의 링크를 눌렀을 때 / gclid: 구글 광고 / igsh, igshid: 인스타그램에서 공유한 링크
	public static final List<String> CLICK_IDS = List.of("fbclid", "gclid", "igsh", "igshid");

	// 도메인에 쓰는 글자만 (소문자, 숫자, 점, 붙임표, 밑줄), 100자까지, 점이 하나 이상. 그 밖이면 남기지 않는다
	// IP 주소(192.168.0.12 같은 것)와 점 없는 이름(localhost)은 사이트가 아니라서 버린다 (JM-92 검토)
	private static final Pattern HOST = Pattern.compile("[a-z0-9._-]{1,100}");
	private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
	// utm 값에 남길 글자: 글자(한글 포함), 숫자, 빈칸, 그리고 . _ ~ + - 만. 나머지(< > " ' = @ 같은 것, 보이지 않는 글자, 줄바꿈)는 지운다.
	// 나중에 화면이나 표 파일로 보여 줄 때 HTML이나 수식으로 읽히지 않게 하고, 같은 값이 보이지 않는 글자로 갈라지지 않게 한다
	private static final Pattern UTM_NOT_ALLOWED = Pattern.compile("[^\\p{L}\\p{N} ._~+\\-]");
	private static final int UTM_LENGTH = 50;

	public VisitEntry {
		refHost = hostOf(refHost);
		utmSource = utmOf(utmSource);
		utmMedium = utmOf(utmMedium);
		utmCampaign = utmOf(utmCampaign);
		// List.of로 만든 목록은 null을 물으면 예외를 던져서 먼저 null을 거른다
		clickId = clickId != null && CLICK_IDS.contains(clickId) ? clickId : null;
	}

	private static String hostOf(String host) {
		if (host == null) {
			return null;
		}
		String lower = host.toLowerCase(Locale.ROOT);
		boolean site = HOST.matcher(lower).matches() && lower.indexOf('.') >= 0 && !IPV4.matcher(lower).matches();
		return site ? lower : null;
	}

	// 남길 글자만 두고 앞뒤 빈칸을 뺀 뒤 50자까지. 비면 남기지 않는다.
	// 50자는 글자 단위로 자른다 (두 칸을 쓰는 글자를 반으로 자르지 않는다. MySQL VARCHAR(50)도 글자 수로 센다)
	private static String utmOf(String value) {
		if (value == null) {
			return null;
		}
		String clean = UTM_NOT_ALLOWED.matcher(value).replaceAll("").strip();
		if (clean.isEmpty()) {
			return null;
		}
		return clean.codePointCount(0, clean.length()) <= UTM_LENGTH ? clean
				: clean.substring(0, clean.offsetByCodePoints(0, UTM_LENGTH));
	}
}
