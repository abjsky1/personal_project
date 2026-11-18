package backend.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// 사람(브라우저)과 프로그램을 가르는 낱말 (조회수와 방문 지역 통계에 쓴다, JM-65, JM-72, JM-86)
class VisitorsTest {

	@Test
	void 휴대폰과_PC_브라우저_인스타그램_앱은_사람이다() {
		assertThat(Visitors.isPerson("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148")).isTrue();
		assertThat(Visitors.isPerson("Mozilla/5.0 (Linux; Android 15; SM-S928N) AppleWebKit/537.36 Chrome/142.0 Mobile Safari/537.36"
				+ " SamsungBrowser/28.0")).isTrue();
		assertThat(Visitors.isPerson("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Mobile/15E148 Instagram 400.0.0")).isTrue();
		assertThat(Visitors.isPerson("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/142.0 Safari/537.36")).isTrue();
	}

	@Test
	void 검색_엔진과_프로그램과_점검_도구는_사람이_아니다() {
		assertThat(Visitors.isPerson("Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)")).isFalse();
		assertThat(Visitors.isPerson("Go-http-client/1.1")).isFalse();
		assertThat(Visitors.isPerson("okhttp/4.12.0")).isFalse();
		assertThat(Visitors.isPerson("Java/17.0.21")).isFalse();
		assertThat(Visitors.isPerson("Apache-HttpClient/5.4")).isFalse();
		assertThat(Visitors.isPerson("Mozilla/5.0 zgrab/0.x")).isFalse();
		assertThat(Visitors.isPerson("Expanse, a Palo Alto Networks company, searches across the global IPv4 space")).isFalse();
		assertThat(Visitors.isPerson("Mozilla/5.0 (compatible; SemrushBot/7~bl)")).isFalse();
		assertThat(Visitors.isPerson("")).isFalse();
		assertThat(Visitors.isPerson(null)).isFalse();
	}
}
