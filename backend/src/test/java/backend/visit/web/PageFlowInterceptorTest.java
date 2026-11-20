package backend.visit.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.servlet.ModelAndView;

import backend.admin.auth.AdminAuthService;
import backend.visit.model.entity.ClientApp;
import backend.visit.model.entity.Device;
import backend.visit.model.entity.PageView;
import backend.visit.model.entity.Screen;
import backend.visit.service.PageFlowRecorder;

// 무엇을 사용자 흐름으로 남기는지 (JM-92). DB 없이 저장하는 쪽(PageFlowRecorder)을 바꿔 끼워 남긴 줄만 본다
// (MockMvc는 JSP를 그리지 않아 세션과 HTML 응답이 생기지 않으므로, 방문 수 테스트처럼 요청과 응답을 직접 만든다.
// 실제 Spring처럼 POST는 postHandle(넘어갈 주소를 아는 때) 다음에 afterCompletion을 부른다)
class PageFlowInterceptorTest {

	private static final String IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 "
			+ "(KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1";
	private static final String KAKAO = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 "
			+ "(KHTML, like Gecko) Mobile/15E148 KAKAOTALK 10.9.0";
	private static final String LAPTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
			+ "Chrome/142.0.0.0 Safari/537.36";

	private final List<PageView> saved = new ArrayList<>();
	private final PageFlowInterceptor interceptor = new PageFlowInterceptor(new PageFlowRecorder(null, false, 20000) {
		@Override
		public void record(PageView view) {
			saved.add(view);
		}
	});

	// 피드 → 글 → 글쓰기 → 올리기 → 올린 글. 한 방문(같은 번호) 안에서 순서와 바로 앞 화면이 이어진다.
	// 올린 글(8)로 넘어간 화면에는 그 글의 번호를 남기지 않는다 (흐름을 글쓴이와 번호로 잇지 않게)
	@Test
	void 한_방문의_화면과_올리기를_순서대로_잇고_올린_글의_번호는_남기지_않는다() {
		MockHttpSession session = new MockHttpSession();

		run(page("/", null, IPHONE, session), html(200));
		run(page("/posts/7", "http://localhost/", IPHONE, session), html(200));
		run(page("/posts/new", "http://localhost/posts/7", IPHONE, session), html(200));
		post(upload("http://localhost/posts/new", IPHONE, session), "redirect:/posts/8", 302);
		run(page("/posts/8", "http://localhost/posts/new", IPHONE, session), html(200));

		assertThat(saved).extracting(PageView::getSeq).containsExactly(1, 2, 3, 4, 5);
		assertThat(saved).extracting(PageView::getScreen)
				.containsExactly(Screen.FEED, Screen.POST, Screen.POST_NEW, Screen.UPLOAD, Screen.POST);
		assertThat(saved).extracting(PageView::getTarget).containsExactly(1L, 7L, null, null, null);
		assertThat(saved).extracting(PageView::getPrevScreen)
				.containsExactly(null, Screen.FEED, Screen.POST, Screen.POST_NEW, Screen.POST_NEW);
		assertThat(saved).extracting(PageView::getVisitKey).containsOnly(saved.get(0).getVisitKey());
		assertThat(saved).extracting(PageView::getDevice).containsOnly(Device.MOBILE);
	}

	// 댓글을 단 글, 쓴 피드백, 확정하거나 메모를 고친 글도 그 방문에서는 번호를 남기지 않는다 (다시 열어도). 상관없는 글은 남긴다
	@Test
	void 이_방문이_쓰거나_고친_글의_번호는_그_방문에서_남기지_않는다() {
		MockHttpSession session = new MockHttpSession();

		run(page("/posts/7", null, IPHONE, session), html(200));
		post(request("POST", "/posts/7/comments", "http://localhost/posts/7", IPHONE, session), "redirect:/posts/7#comments", 302);
		run(page("/posts/7", "http://localhost/posts/7", IPHONE, session), html(200));
		post(request("POST", "/feedback", "http://localhost/feedback/new", IPHONE, session), "redirect:/feedback/5", 302);
		run(page("/feedback/5", "http://localhost/feedback/new", IPHONE, session), html(200));
		post(request("POST", "/posts/3/memo", "http://localhost/posts/3", IPHONE, session), "redirect:/posts/3#memo-edit", 302);
		run(page("/posts/3", "http://localhost/posts/3", IPHONE, session), html(200));
		run(page("/posts/9", "http://localhost/posts/3", IPHONE, session), html(200));
		run(page("/posts/7", "http://localhost/posts/9", IPHONE, session), html(200));

		assertThat(saved).extracting(PageView::getScreen)
				.containsExactly(Screen.POST, Screen.POST, Screen.FEEDBACK, Screen.POST, Screen.POST, Screen.POST);
		// 처음 연 글 7은 댓글을 달기 전이라 남고, 그 뒤로는 7, 피드백 5, 글 3이 비고 상관없는 글 9는 남는다
		assertThat(saved).extracting(PageView::getTarget).containsExactly(7L, null, null, null, 9L, null);
	}

	// 들어온 곳은 첫 줄에만, 다른 사이트는 도메인만, 링크 표시는 이름만. 검색어와 링크 표시의 값은 어느 칸에도 남지 않는다
	@Test
	void 들어온_곳은_방문의_첫_줄에만_도메인과_이름으로_남긴다() {
		MockHttpSession session = new MockHttpSession();
		MockHttpServletRequest first = page("/", "https://www.Google.com/search?q=secret-query", KAKAO, session);
		first.setParameter("utm_source", "instagram");
		first.setParameter("utm_medium", "bio");
		first.setParameter("utm_campaign", "beta-1120");
		first.setParameter("fbclid", "IwAR0secret-click");

		run(first, html(200));
		run(page("/posts/3", "http://localhost/?utm_source=instagram&fbclid=IwAR0secret-click", KAKAO, session), html(200));

		PageView entry = saved.get(0);
		assertThat(entry.getApp()).isEqualTo(ClientApp.KAKAOTALK);
		assertThat(entry.getRefHost()).isEqualTo("www.google.com");
		assertThat(entry.getUtmSource()).isEqualTo("instagram");
		assertThat(entry.getUtmMedium()).isEqualTo("bio");
		assertThat(entry.getUtmCampaign()).isEqualTo("beta-1120");
		assertThat(entry.getClickId()).isEqualTo("fbclid");
		assertThat(entry.getPrevScreen()).isNull();

		PageView next = saved.get(1);
		assertThat(next.getPrevScreen()).isEqualTo(Screen.FEED);
		assertThat(next.getApp()).isNull();
		assertThat(next.getRefHost()).isNull();
		assertThat(next.getUtmSource()).isNull();
		assertThat(next.getClickId()).isNull();

		assertThat(saved).allSatisfy(view -> assertThat(textOf(view)).doesNotContain("secret"));
	}

	// 다른 사이트에서 온 줄에는 바로 앞 화면이 없고, 그 사이트의 경로가 우리 화면 주소와 같아도 우리 화면으로 보지 않는다
	@Test
	void 다른_사이트의_주소는_바로_앞_화면이_아니다() {
		run(page("/posts/5", "https://l.instagram.com/posts/5", IPHONE, new MockHttpSession()), html(200));
		run(page("/", "android-app://com.google.android.gm/", IPHONE, new MockHttpSession()), html(200));

		assertThat(saved).extracting(PageView::getPrevScreen).containsOnlyNulls();
		assertThat(saved).extracting(PageView::getRefHost).containsExactly("l.instagram.com", "com.google.android.gm");
	}

	@Test
	void 사람이_아닌_요청과_에러_화면과_세션이_없는_요청은_남기지_않는다() {
		run(page("/", null, "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)", new MockHttpSession()), html(200));
		run(page("/", null, "facebookexternalhit/1.1", new MockHttpSession()), html(200));
		run(page("/", null, null, new MockHttpSession()), html(200));
		run(page("/posts/999", null, IPHONE, new MockHttpSession()), html(404));
		run(page("/", null, IPHONE, null), html(200));
		MockHttpServletResponse json = new MockHttpServletResponse();
		json.setContentType("application/json");
		run(page("/", null, IPHONE, new MockHttpSession()), json);
		// 화면 목록(Screen)에 없는 주소, 막힌 올리기
		run(page("/robots.txt", null, IPHONE, new MockHttpSession()), html(200));
		run(upload(null, IPHONE, new MockHttpSession()), html(429));

		assertThat(saved).isEmpty();
	}

	// 화면을 그리다 예외가 나면 (상태는 아직 200으로 보여도) 사용자는 에러 화면을 봤다. 남기지 않는다
	@Test
	void 화면을_그리다_실패하면_남기지_않는다() {
		interceptor.afterCompletion(page("/", null, IPHONE, new MockHttpSession()), html(200), new Object(),
				new IllegalStateException("JSP failed"));

		assertThat(saved).isEmpty();
	}

	// 관리자로 들어온 적이 있는 세션은 남기지 않는다. 관리자 화면만 쓰고 로그아웃한 뒤 같은 브라우저로 사용자 화면을 봐도 그 세션이 끝날 때까지 뺀다
	// (실제 로그인과 로그아웃이 이 표시를 남기는지는 AdminPageTest가 본다)
	@Test
	void 관리자로_들어온_적이_있는_세션은_로그아웃한_뒤에도_남기지_않는다() {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(AdminAuthService.SEEN, Boolean.TRUE);

		run(page("/", null, LAPTOP, session), html(200));
		run(page("/posts/7", "http://localhost/", LAPTOP, session), html(200));

		assertThat(saved).isEmpty();
	}

	@Test
	void 세션이_다르면_다른_방문이다() {
		run(page("/", null, IPHONE, new MockHttpSession()), html(200));
		run(page("/", null, LAPTOP, new MockHttpSession()), html(200));

		assertThat(saved).extracting(PageView::getSeq).containsExactly(1, 1);
		assertThat(saved.get(0).getVisitKey()).isNotEqualTo(saved.get(1).getVisitKey()).isNotNegative();
		assertThat(saved).extracting(PageView::getDevice).containsExactly(Device.MOBILE, Device.DESKTOP);
		assertThat(saved).extracting(PageView::getApp).containsExactly(ClientApp.SAFARI, ClientApp.CHROME);
	}

	// 요청한 쪽 번호를 남긴다. 없거나 비거나 0 이하면 1쪽 (PostService.getFeed와 같다)
	@Test
	void 피드는_요청한_쪽_번호를_남긴다() {
		MockHttpSession session = new MockHttpSession();
		for (String page : new String[] { null, "3", "0", "" }) {
			MockHttpServletRequest request = page("/", null, IPHONE, session);
			if (page != null) {
				request.setParameter("page", page);
			}
			run(request, html(200));
		}

		assertThat(saved).extracting(PageView::getTarget).containsExactly(1L, 3L, 1L, 1L);
	}

	// Referer는 아무 값이나 보낼 수 있다. 모양이 틀려도 화면은 남기고 그 값만 버린다
	@Test
	void 잘못된_Referer는_없는_것으로_본다() {
		run(page("/", "::not a referer::", IPHONE, new MockHttpSession()), html(200));
		run(page("/", "https://evil.example.com:99999/<script>", IPHONE, new MockHttpSession()), html(200));

		assertThat(saved).hasSize(2);
		assertThat(saved).extracting(PageView::getPrevScreen).containsOnlyNulls();
		assertThat(saved).extracting(PageView::getRefHost).containsOnlyNulls();
	}

	// 주소의 값이 깨져 있으면(잘리다 만 %ED%95 등) Tomcat이 값을 읽을 때 예외를 던진다. 그래도 방문의 첫 줄은 남고 utm만 비운다
	// (예전에는 순서 번호 1만 쓰고 줄을 잃어, 그 방문이 들어온 곳을 통째로 잃었다. 2026-11-20 로컬 서버에서 재현)
	@Test
	void 주소의_값이_깨져_있어도_첫_줄은_남는다() {
		MockHttpSession session = new MockHttpSession();
		MockHttpServletRequest broken = new MockHttpServletRequest("GET", "/terms") {
			@Override
			public String getParameter(String name) {
				throw new IllegalStateException("invalid parameter");
			}
		};
		broken.addHeader("User-Agent", KAKAO);
		broken.setSession(session);

		run(broken, html(200));
		run(page("/privacy", "http://localhost/terms", KAKAO, session), html(200));

		assertThat(saved).extracting(PageView::getSeq).containsExactly(1, 2);
		assertThat(saved.get(0).getApp()).isEqualTo(ClientApp.KAKAOTALK);
		assertThat(saved.get(0).getUtmSource()).isNull();
	}

	// 올리기의 입력이 틀려 글쓰기 화면을 다시 보여 주면 주소가 /posts로 남는다. 그 화면에서 넘어온 줄의 바로 앞 화면은 글쓰기다
	@Test
	void 다시_보여_준_글쓰기_화면에서_넘어오면_바로_앞_화면은_글쓰기다() {
		run(page("/", "http://localhost/posts", IPHONE, new MockHttpSession()), html(200));

		assertThat(saved).singleElement().extracting(PageView::getPrevScreen).isEqualTo(Screen.POST_NEW);
	}

	// 세션이 끝난 뒤 글쓰기 화면에서 올리면 올리기가 방문의 첫 줄이 된다. 그때도 form의 칸은 utm으로 읽지 않는다
	@Test
	void 올리기가_첫_줄이어도_form의_칸은_들어온_곳으로_읽지_않는다() {
		MockHttpServletRequest upload = upload("http://localhost/posts/new", IPHONE, new MockHttpSession());
		upload.setParameter("utm_source", "form-field");

		post(upload, "redirect:/posts/8", 302);

		assertThat(saved).singleElement().satisfies(view -> {
			assertThat(view.getScreen()).isEqualTo(Screen.UPLOAD);
			assertThat(view.getApp()).isEqualTo(ClientApp.SAFARI);
			assertThat(view.getUtmSource()).isNull();
			assertThat(view.getPrevScreen()).isEqualTo(Screen.POST_NEW);
		});
	}

	// 여기서 예외가 밖으로 나가면 Spring이 ERROR 로그를 찍어 에러 알림 메일이 울린다. 무엇이 실패해도 그 줄만 건너뛴다
	@Test
	void 남기다가_예외가_나도_밖으로_내보내지_않는다() {
		PageFlowInterceptor failing = new PageFlowInterceptor(new PageFlowRecorder(null, false, 20000) {
			@Override
			public void record(PageView view) {
				throw new IllegalStateException("DB down");
			}
		});

		failing.afterCompletion(page("/", null, IPHONE, new MockHttpSession()), html(200), new Object(), null);
	}

	private void run(MockHttpServletRequest request, MockHttpServletResponse response) {
		interceptor.afterCompletion(request, response, new Object(), null);
	}

	// POST가 viewName으로 넘어간다 (실제 Spring처럼 postHandle 다음에 afterCompletion)
	private void post(MockHttpServletRequest request, String viewName, int status) {
		MockHttpServletResponse response = redirect(status);
		interceptor.postHandle(request, response, new Object(), new ModelAndView(viewName));
		interceptor.afterCompletion(request, response, new Object(), null);
	}

	// session: 이 브라우저의 세션 (null이면 세션 없음). 같은 객체를 넘기면 같은 브라우저의 다음 요청이다
	private static MockHttpServletRequest page(String path, String referer, String userAgent, MockHttpSession session) {
		return request("GET", path, referer, userAgent, session);
	}

	private static MockHttpServletRequest upload(String referer, String userAgent, MockHttpSession session) {
		return request("POST", "/posts", referer, userAgent, session);
	}

	private static MockHttpServletRequest request(String method, String path, String referer, String userAgent, MockHttpSession session) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		if (referer != null) {
			request.addHeader("Referer", referer);
		}
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

	// 엔티티의 모든 칸 값을 이어 붙인 글자 (어느 칸에도 남지 않아야 할 값을 찾는 데 쓴다)
	private static String textOf(PageView view) {
		StringBuilder text = new StringBuilder();
		for (Field field : PageView.class.getDeclaredFields()) {
			field.setAccessible(true);
			try {
				text.append(field.get(view)).append('|');
			} catch (IllegalAccessException e) {
				throw new IllegalStateException(e);
			}
		}
		return text.toString();
	}
}
