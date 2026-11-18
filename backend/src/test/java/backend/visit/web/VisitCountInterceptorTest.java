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
	void 처음_받은_화면만_방문으로_센다() {
		run(page("GET", "/", BROWSER, true), html(200));
		run(page("GET", "/posts/7", BROWSER, false), html(200));

		assertThat(counted).containsExactly("VISIT 1.2.3.4");
	}

	@Test
	void 로봇과_User_Agent가_없는_요청은_세지_않는다() {
		run(page("GET", "/", "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)", true), html(200));
		run(page("GET", "/", "Go-http-client/1.1", true), html(200));
		run(page("GET", "/", null, true), html(200));

		assertThat(counted).isEmpty();
	}

	@Test
	void 에러_화면과_HTML이_아닌_응답은_방문이_아니다() {
		run(page("GET", "/posts/999", BROWSER, true), html(404));
		MockHttpServletResponse json = new MockHttpServletResponse();
		json.setContentType("application/json");
		run(page("GET", "/", BROWSER, true), json);

		assertThat(counted).isEmpty();
	}

	@Test
	void 새_글로_넘어간_글_올리기만_센다() {
		run(page("POST", "/posts", BROWSER, true), redirect(302));
		run(page("POST", "/posts", BROWSER, false), html(429));
		run(page("POST", "/posts/7/comments", BROWSER, false), redirect(302));

		assertThat(counted).containsExactly("UPLOAD 1.2.3.4");
	}

	private void run(MockHttpServletRequest request, MockHttpServletResponse response) {
		interceptor.afterCompletion(request, response, new Object(), null);
	}

	// newSession: 이 요청에서 세션이 새로 생겼는지 (브라우저의 첫 화면)
	// 실제 Tomcat은 세션을 만든 요청이 끝날 때까지 isNew()가 true다. MockHttpSession은 꺼낼 때마다 false로 바꿔서 값을 고정해 둔다
	private static MockHttpServletRequest page(String method, String path, String userAgent, boolean newSession) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		request.setRemoteAddr("1.2.3.4");
		if (userAgent != null) {
			request.addHeader("User-Agent", userAgent);
		}
		request.setSession(new MockHttpSession() {
			@Override
			public boolean isNew() {
				return newSession;
			}
		});
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
