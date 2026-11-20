package backend.visit.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import backend.visit.model.entity.VisitMetric;
import backend.visit.service.VisitCounter;

// 무엇을 방문과 글 올리기로 세는지 (JM-86). DB 없이 세는 쪽(VisitCounter)을 바꿔 끼워 부른 횟수만 본다
// (MockMvc는 JSP를 그리지 않아 세션과 HTML 응답이 생기지 않으므로, 요청과 응답을 직접 만든다)
class VisitCountInterceptorTest {

	private static final String BROWSER = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148";

	private final List<String> counted = new ArrayList<>();
	private final VisitCountInterceptor interceptor = new VisitCountInterceptor(new VisitCounter(null, null) {
		@Override
		public void count(VisitMetric metric, String ip) {
			counted.add(metric + " " + ip);
		}
	});

	@Test
	void 세션의_첫_화면만_방문으로_센다() {
		MockHttpSession session = new MockHttpSession();

		run(page("GET", "/", BROWSER, session), html(200));
		run(page("GET", "/posts/7", BROWSER, session), html(200));

		assertThat(counted).containsExactly("VISIT 1.2.3.4");
		assertThat(session.getAttribute(VisitCountInterceptor.COUNTED)).isEqualTo(Boolean.TRUE);
	}

	// 지워진 글의 링크로 처음 들어와 404를 본 뒤 목록으로 가도, 그 세션의 첫 200 화면에서 한 번 센다 (JM-86 검토)
	@Test
	void 첫_화면이_에러_화면이어도_다음_화면에서_한_번_센다() {
		MockHttpSession session = new MockHttpSession();

		run(page("GET", "/posts/999", BROWSER, session), html(404));
		run(page("GET", "/", BROWSER, session), html(200));
		run(page("GET", "/notice", BROWSER, session), html(200));

		assertThat(counted).containsExactly("VISIT 1.2.3.4");
	}

	@Test
	void 로봇과_User_Agent가_없는_요청은_세지_않는다() {
		run(page("GET", "/", "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)", new MockHttpSession()), html(200));
		run(page("GET", "/", "Mozilla/5.0 (compatible; Yeti/1.1; +https://naver.me/spd)", new MockHttpSession()), html(200));
		run(page("GET", "/", "Go-http-client/1.1", new MockHttpSession()), html(200));
		run(page("GET", "/", null, new MockHttpSession()), html(200));

		assertThat(counted).isEmpty();
	}

	@Test
	void 에러_화면과_HTML이_아닌_응답과_세션이_없는_요청은_방문이_아니다() {
		run(page("GET", "/posts/999", BROWSER, new MockHttpSession()), html(404));
		MockHttpServletResponse json = new MockHttpServletResponse();
		json.setContentType("application/json");
		run(page("GET", "/", BROWSER, new MockHttpSession()), json);
		run(page("GET", "/", BROWSER, null), html(200));

		assertThat(counted).isEmpty();
	}

	// 화면을 그리다 예외가 나면 사용자는 에러 화면을 봤다. 상태가 아직 200으로 보여도 방문으로 세지 않는다 (JM-92 검토)
	@Test
	void 화면을_그리다_실패하면_세지_않는다() {
		interceptor.afterCompletion(page("GET", "/", BROWSER, new MockHttpSession()), html(200), new Object(),
				new IllegalStateException("JSP failed"));

		assertThat(counted).isEmpty();
	}

	@Test
	void 새_글로_넘어간_글_올리기만_센다() {
		run(page("POST", "/posts", BROWSER, new MockHttpSession()), redirect(302));
		run(page("POST", "/posts", BROWSER, new MockHttpSession()), html(429));
		run(page("POST", "/posts/7/comments", BROWSER, new MockHttpSession()), redirect(302));

		assertThat(counted).containsExactly("UPLOAD 1.2.3.4");
	}

	private void run(MockHttpServletRequest request, MockHttpServletResponse response) {
		interceptor.afterCompletion(request, response, new Object(), null);
	}

	// session: 이 브라우저의 세션 (null이면 세션 없음). 같은 객체를 넘기면 같은 브라우저의 다음 요청이다
	private static MockHttpServletRequest page(String method, String path, String userAgent, MockHttpSession session) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		request.setRemoteAddr("1.2.3.4");
		if (userAgent != null) {
			request.addHeader("User-Agent", userAgent);
		}
		if (session != null) {
			request.setSession(session);
		}
		return request;
	}

	private static MockHttpServletResponse html(int status) {
		MockHttpServletResponse response = new MockHttpServletResponse();
		response.setStatus(status);
		response.setContentType("text/html;charset=UTF-8");
		return response;
	}

	private static MockHttpServletResponse redirect(int status) {
		MockHttpServletResponse response = new MockHttpServletResponse();
		response.setStatus(status);
		return response;
	}
}
