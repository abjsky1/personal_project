package backend.common.web;

import java.util.regex.Pattern;

// 요청을 보낸 쪽이 사람(브라우저)인지 가려낸다. 조회수를 셀 때 쓴다 (공지 JM-65, 글 JM-72).
// 검색 엔진, 링크 미리보기, 화면 캡처 도구, 점검 스크립트 같은 요청은 User-Agent에 아래 낱말이 들어 있다.
// 방문 지역 통계(JM-86)를 세면서, 서버 로그로 트래픽을 셀 때 쓰던 목록과 맞췄다: 프로그램이 보내는 요청(go-http, okhttp, java/,
// node-fetch, axios, httpclient, libwww, fasthttp)과 보안 점검 도구(scan, zgrab, censys, expanse, nmap, masscan, nuclei, l9explore),
// 검색 광고 도구(semrush, ahrefs, dataprovider)
// 같은 사람이 다시 열어도 사람으로 본다: 가려 내려면 쿠키가 필요한데, 처리방침(9절)에서 추적용 쿠키를 쓰지 않는다고 했다.
public final class Visitors {

	private static final Pattern NOT_PERSON = Pattern.compile(
			"bot|crawl|spider|slurp|preview|facebookexternalhit|headless|curl|wget|python|go-http|okhttp|java/|node-fetch|axios"
					+ "|httpclient|libwww|fasthttp|scan|zgrab|censys|expanse|nmap|masscan|nuclei|l9explore|semrush|ahrefs|dataprovider",
			Pattern.CASE_INSENSITIVE);

	// 객체를 만들 일이 없는 도구 클래스라 생성자를 막아 둔다
	private Visitors() {
	}

	// User-Agent가 없거나 비어 있어도 사람이 아닌 것으로 본다 (브라우저는 항상 보낸다)
	public static boolean isPerson(String userAgent) {
		return userAgent != null && !userAgent.isBlank() && !NOT_PERSON.matcher(userAgent).find();
	}
}
