package backend.visit.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import backend.visit.model.entity.ClientApp;
import backend.visit.model.entity.Device;
import backend.visit.model.entity.Screen;
import backend.visit.model.vo.VisitEntry;

// 사용자 흐름(JM-92)의 값 규칙: 주소 → 화면, User-Agent → 앱 종류와 기기, 들어온 곳을 줄이는 규칙
class PageFlowValuesTest {

	@Test
	void 주소의_경로로_화면과_번호를_찾는다() {
		assertThat(Screen.match("/")).isEqualTo(new Screen.Match(Screen.FEED, null));
		assertThat(Screen.match("/posts/7")).isEqualTo(new Screen.Match(Screen.POST, 7L));
		assertThat(Screen.match("/posts/new")).isEqualTo(new Screen.Match(Screen.POST_NEW, null));
		assertThat(Screen.match("/notice")).isEqualTo(new Screen.Match(Screen.NOTICE_LIST, null));
		assertThat(Screen.match("/notice/3")).isEqualTo(new Screen.Match(Screen.NOTICE, 3L));
		assertThat(Screen.match("/feedback")).isEqualTo(new Screen.Match(Screen.FEEDBACK_LIST, null));
		assertThat(Screen.match("/feedback/new")).isEqualTo(new Screen.Match(Screen.FEEDBACK_NEW, null));
		assertThat(Screen.match("/feedback/12")).isEqualTo(new Screen.Match(Screen.FEEDBACK, 12L));
		assertThat(Screen.match("/privacy")).isEqualTo(new Screen.Match(Screen.PRIVACY, null));
		assertThat(Screen.match("/terms")).isEqualTo(new Screen.Match(Screen.TERMS, null));
	}

	// 화면이 아닌 주소와, long에 들어가지 않는 번호(19자리)는 화면으로 보지 않는다
	@Test
	void 화면이_아닌_주소는_찾지_않는다() {
		for (String path : new String[] { null, "", "/posts", "/posts/", "/posts/abc", "/posts/7/memo", "/admin", "/admin/stats",
				"/images/a.jpg", "/notice/3/", "/posts/1234567890123456789" }) {
			assertThat(Screen.match(path)).as(String.valueOf(path)).isNull();
		}
	}

	// 앱 안의 브라우저도 User-Agent에 Safari, Chrome이 들어 있어서 앱을 먼저 찾는다
	@Test
	void User_Agent로_앱_종류를_찾는다() {
		String ios = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148";
		String android = "Mozilla/5.0 (Linux; Android 15; SM-S928N) AppleWebKit/537.36 (KHTML, like Gecko) ";
		String windows = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) ";

		assertThat(ClientApp.of(ios + " Instagram 357.0.0.25.101 (iPhone15,3; iOS 18_1; ko_KR; ko)")).isEqualTo(ClientApp.INSTAGRAM);
		assertThat(ClientApp.of(ios + " KAKAOTALK 10.9.0")).isEqualTo(ClientApp.KAKAOTALK);
		assertThat(ClientApp.of(ios + " NAVER(inapp; search; 2000; 12.10.0)")).isEqualTo(ClientApp.NAVER);
		assertThat(ClientApp.of(ios + " [FBAN/FBIOS;FBAV/490.0.0.40.107]")).isEqualTo(ClientApp.FACEBOOK);
		assertThat(ClientApp.of(ios + " Safari Line/14.19.0")).isEqualTo(ClientApp.LINE);
		assertThat(ClientApp.of(ios + " DaumApps/8.5.0")).isEqualTo(ClientApp.DAUM);
		assertThat(ClientApp.of(android + "SamsungBrowser/28.0 Chrome/130.0.0.0 Mobile Safari/537.36")).isEqualTo(ClientApp.SAMSUNG);
		assertThat(ClientApp.of(windows + "Chrome/138.0.0.0 Whale/4.33.325.17 Safari/537.36")).isEqualTo(ClientApp.WHALE);
		assertThat(ClientApp.of(windows + "Chrome/142.0.0.0 Safari/537.36 Edg/142.0.0.0")).isEqualTo(ClientApp.EDGE);
		assertThat(ClientApp.of(android + "Chrome/142.0.0.0 Mobile Safari/537.36 EdgA/142.0.0.0")).isEqualTo(ClientApp.EDGE);
		assertThat(ClientApp.of("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:145.0) Gecko/20100101 Firefox/145.0")).isEqualTo(ClientApp.FIREFOX);
		assertThat(ClientApp.of(ios + " FxiOS/145.0 Safari/605.1.15")).isEqualTo(ClientApp.FIREFOX);
		assertThat(ClientApp.of(windows + "Chrome/142.0.0.0 Safari/537.36")).isEqualTo(ClientApp.CHROME);
		assertThat(ClientApp.of(ios + " CriOS/142.0.7444.77 Mobile/15E148 Safari/604.1")).isEqualTo(ClientApp.CHROME);
		assertThat(ClientApp.of(ios + " Version/18.1 Safari/604.1")).isEqualTo(ClientApp.SAFARI);
		assertThat(ClientApp.of("Opera/9.80")).isEqualTo(ClientApp.OTHER);
		assertThat(ClientApp.of(null)).isEqualTo(ClientApp.OTHER);
	}

	@Test
	void User_Agent로_휴대폰인지_찾는다() {
		assertThat(Device.of("Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) Mobile/15E148")).isEqualTo(Device.MOBILE);
		assertThat(Device.of("Mozilla/5.0 (Linux; Android 15; SM-X910N) AppleWebKit/537.36 Chrome/142.0 Safari/537.36")).isEqualTo(Device.MOBILE);
		assertThat(Device.of("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/142.0.0.0 Safari/537.36")).isEqualTo(Device.DESKTOP);
		assertThat(Device.of("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Version/18.1 Safari/605.1.15")).isEqualTo(Device.DESKTOP);
		assertThat(Device.of(null)).isEqualTo(Device.DESKTOP);
	}

	// 도메인은 소문자로, 도메인에 쓰지 않는 글자가 있거나 100자를 넘으면 버린다. utm은 빈칸과 제어 문자를 빼고 50자까지
	@Test
	void 들어온_곳의_값은_만들_때_줄인다() {
		VisitEntry entry = new VisitEntry(ClientApp.CHROME, "M.Search.Naver.com", "  insta\ngram\t ", "", null, "fbclid");
		assertThat(entry.refHost()).isEqualTo("m.search.naver.com");
		assertThat(entry.utmSource()).isEqualTo("instagram");
		assertThat(entry.utmMedium()).isNull();
		assertThat(entry.utmCampaign()).isNull();
		assertThat(entry.clickId()).isEqualTo("fbclid");

		assertThat(new VisitEntry(ClientApp.OTHER, "evil host", null, null, null, "utm_id").refHost()).isNull();
		assertThat(new VisitEntry(ClientApp.OTHER, "a".repeat(101), null, null, null, null).refHost()).isNull();
		assertThat(new VisitEntry(ClientApp.OTHER, null, null, null, null, "IwAR0value").clickId()).isNull();

		String longValue = "가".repeat(49) + "😀" + "끝";
		String cut = new VisitEntry(ClientApp.OTHER, null, longValue, null, null, null).utmSource();
		assertThat(cut.codePointCount(0, cut.length())).isEqualTo(50);
		assertThat(cut).endsWith("😀");
	}
}
