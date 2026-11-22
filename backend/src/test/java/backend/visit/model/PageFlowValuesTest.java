package backend.visit.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import backend.visit.model.entity.ClientApp;
import backend.visit.model.entity.Device;
import backend.visit.model.entity.Screen;
import backend.visit.model.vo.VisitEntry;

// 사용자 흐름(JM-92)의 값 규칙: 주소 → 화면, User-Agent → 앱 종류와 기기, 들어온 곳을 줄이는 규칙 (만들 때 줄인다)
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

	// 도메인은 소문자로. 도메인에 쓰지 않는 글자가 있거나, 100자를 넘거나, 점이 없거나(localhost), IP 주소면 버린다
	@Test
	void 다른_사이트는_도메인일_때만_남긴다() {
		assertThat(entry("M.Search.Naver.com", null).refHost()).isEqualTo("m.search.naver.com");
		assertThat(entry("com.google.android.gm", null).refHost()).isEqualTo("com.google.android.gm");
		for (String host : new String[] { "evil host", "a".repeat(101) + ".com", "localhost", "devbox", "192.168.0.12", "8.8.8.8", "" }) {
			assertThat(entry(host, null).refHost()).as(host).isNull();
		}
	}

	// utm은 글자(한글 포함), 숫자, 빈칸, . _ ~ + - 만 남기고 50자까지. HTML, 수식, 보이지 않는 글자, 줄바꿈은 지운다
	@Test
	void utm_값은_정한_글자만_50자까지_남긴다() {
		assertThat(entry(null, "  insta\ngram\t ").utmSource()).isEqualTo("instagram");
		assertThat(entry(null, "beta_launch-1120 v2.0").utmSource()).isEqualTo("beta_launch-1120 v2.0");
		assertThat(entry(null, "지인 베타").utmSource()).isEqualTo("지인 베타");
		assertThat(entry(null, "<script>alert(1)</script>").utmSource()).isEqualTo("scriptalert1script");
		assertThat(entry(null, "=HYPERLINK(\"x\")@").utmSource()).isEqualTo("HYPERLINKx");
		assertThat(entry(null, "insta​gram‮").utmSource()).isEqualTo("instagram");
		assertThat(entry(null, "​<>").utmSource()).isNull();
		assertThat(entry(null, "").utmSource()).isNull();

		String cut = entry(null, "가".repeat(60)).utmSource();
		assertThat(cut).isEqualTo("가".repeat(50));
		assertThat(entry(null, "가".repeat(49) + "😀끝끝").utmSource()).isEqualTo("가".repeat(49) + "끝");
	}

	// 링크 표시는 정한 이름(fbclid, gclid, igsh, igshid)만. 값이나 다른 이름은 버린다
	@Test
	void 링크_표시는_정한_이름만_남긴다() {
		assertThat(new VisitEntry(ClientApp.OTHER, null, null, null, null, "fbclid").clickId()).isEqualTo("fbclid");
		assertThat(new VisitEntry(ClientApp.OTHER, null, null, null, null, "utm_id").clickId()).isNull();
		assertThat(new VisitEntry(ClientApp.OTHER, null, null, null, null, "IwAR0value").clickId()).isNull();
		assertThat(new VisitEntry(ClientApp.OTHER, null, null, null, null, null).clickId()).isNull();
	}

	private static VisitEntry entry(String host, String utmSource) {
		return new VisitEntry(ClientApp.CHROME, host, utmSource, null, null, null);
	}
}
