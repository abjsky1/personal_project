package backend.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

import backend.image.TestImages;
import backend.visit.model.entity.Screen;
import backend.visit.web.PageFlowInterceptor;

// 사용자 흐름(JM-92)이 실제 DB에 쌓이는지, 표에 둘 칸만 있는지, 사용자 화면에 빠짐없이 걸리는지.
// @Transactional: 테스트가 남긴 줄은 끝나면 되돌린다. 개발 DB에 다른 줄이 있을 수 있어서 이 테스트가 만든 방문 번호로만 본다
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
		"app.geoip.path=",
		"app.upload-dir=build/test-uploads",
		"app.rate-limit.upload-per-browser-per-minute=1000",
		"app.rate-limit.upload-per-browser-per-day=1000",
		"app.rate-limit.upload-per-ip-per-day=1000",
		"app.rate-limit.upload-total-per-day=1000000" })
class PageFlowTest {

	private static final String KAKAO = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 "
			+ "(KHTML, like Gecko) Mobile/15E148 KAKAOTALK 10.9.0";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PageFlowInterceptor pageFlowInterceptor;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	// 화면 두 개를 실제 인터셉터와 DB로 남기고 칸마다 들어간 값을 본다 (MockMvc는 JSP를 그리지 않아 요청과 응답을 직접 만든다).
	// 개발 DB에 다른 줄이 있을 수 있어서, 이 테스트만의 utm_campaign 값으로 방문을 찾는다
	@Test
	void 사람이_본_화면이_방문_번호로_이어져_쌓인다() {
		String campaign = "t" + UUID.randomUUID().toString().substring(0, 8);
		MockHttpSession session = new MockHttpSession();
		MockHttpServletRequest first = page("/", "https://www.google.com/search?q=jjikmuk", session);
		first.setParameter("utm_source", "instagram");
		first.setParameter("utm_medium", "bio");
		first.setParameter("utm_campaign", campaign);
		first.setParameter("fbclid", "IwAR0value");
		pageFlowInterceptor.afterCompletion(first, html(), new Object(), null);
		pageFlowInterceptor.afterCompletion(page("/posts/7", "http://localhost/", session), html(), new Object(), null);

		List<Map<String, Object>> rows = rowsOfVisit(campaign);
		assertThat(rows).hasSize(2);
		Map<String, Object> entry = rows.get(0);
		assertThat(entry).containsEntry("seq", 1).containsEntry("screen", "FEED").containsEntry("target", 1L)
				.containsEntry("device", "MOBILE").containsEntry("app", "KAKAOTALK").containsEntry("ref_host", "www.google.com")
				.containsEntry("utm_source", "instagram").containsEntry("utm_medium", "bio").containsEntry("utm_campaign", campaign)
				.containsEntry("click_id", "fbclid");
		assertThat(entry.get("prev_screen")).isNull();
		Map<String, Object> next = rows.get(1);
		assertThat(next).containsEntry("seq", 2).containsEntry("screen", "POST").containsEntry("target", 7L)
				.containsEntry("prev_screen", "FEED");
		assertThat(next.get("app")).isNull();
		assertThat(next.get("ref_host")).isNull();
		assertThat(next.get("utm_campaign")).isNull();
		// 시각은 UTC로 들어간다: DB의 UTC 지금과 몇 초 안쪽이어야 한다 (한국 시간으로 들어가면 9시간 차이가 난다)
		Long secondsAgo = jdbcTemplate.queryForObject("SELECT MAX(ABS(TIMESTAMPDIFF(SECOND, viewed_at, UTC_TIMESTAMP(6)))) "
				+ "FROM page_views WHERE visit_key = ?", Long.class, entry.get("visit_key"));
		assertThat(secondsAgo).isLessThan(120L);
	}

	// 실제 요청(MockMvc)으로 글을 올리면 올리기 줄이 남고, 넘어간 새 글 화면에는 그 글의 번호가 남지 않는다.
	// 인터셉터가 등록돼 있고 POST /posts의 302와 넘어갈 주소(postHandle)를 알아보는지까지 본다
	@Test
	void 글을_올리면_올리기_줄이_남고_새_글의_번호는_남지_않는다() throws Exception {
		String campaign = "t" + UUID.randomUUID().toString().substring(0, 8);
		MockHttpSession session = new MockHttpSession();
		MockHttpServletRequest feed = page("/", null, session);
		feed.setParameter("utm_campaign", campaign);
		pageFlowInterceptor.afterCompletion(feed, html(), new Object(), null);
		MockMultipartFile photo = new MockMultipartFile("image", "food.jpg", "image/jpeg", TestImages.jpeg(300, 200));

		MvcResult uploaded = mockMvc.perform(multipart("/posts").file(photo).param("nickname", "테스터").param("password", "1234")
				.session(session).header("User-Agent", KAKAO).header("Referer", "http://localhost/posts/new"))
				.andExpect(status().is3xxRedirection())
				.andReturn();
		String newPost = uploaded.getResponse().getRedirectedUrl();
		pageFlowInterceptor.afterCompletion(page(newPost, "http://localhost/posts/new", session), html(), new Object(), null);

		List<Map<String, Object>> rows = rowsOfVisit(campaign);
		assertThat(rows).extracting(row -> row.get("screen")).containsExactly("FEED", "UPLOAD", "POST");
		assertThat(rows.get(1)).containsEntry("prev_screen", "POST_NEW").containsEntry("device", "MOBILE");
		assertThat(rows.get(1).get("target")).isNull();
		assertThat(newPost).matches("/posts/\\d+");
		assertThat(rows.get(2).get("target")).isNull();
	}

	// IP, 세션 번호, User-Agent 전체, 주소 전체, 올린 글의 번호는 둘 칸조차 없다. 종류 칸은 MySQL enum이 아니라 글자 칸이다
	@Test
	void 표에는_정한_칸만_있고_종류_칸은_글자_칸이다() {
		assertThat(jdbcTemplate.queryForList("SELECT column_name FROM information_schema.columns "
				+ "WHERE table_schema = DATABASE() AND table_name = 'page_views'", String.class))
				.containsExactlyInAnyOrder("id", "visit_key", "seq", "viewed_at", "screen", "target", "prev_screen", "device", "app",
						"ref_host", "utm_source", "utm_medium", "utm_campaign", "click_id");
		assertThat(jdbcTemplate.queryForList("SELECT data_type FROM information_schema.columns WHERE table_schema = DATABASE() "
				+ "AND table_name = 'page_views' AND column_name IN ('screen', 'prev_screen', 'device', 'app')", String.class))
				.hasSize(4).allSatisfy(type -> assertThat(type).isEqualToIgnoringCase("varchar"));
		// 방문 번호와 순서는 한 쌍이 한 줄뿐이다 (유일 인덱스)
		assertThat(jdbcTemplate.queryForList("SELECT column_name FROM information_schema.statistics WHERE table_schema = DATABASE() "
				+ "AND table_name = 'page_views' AND index_name = 'uk_page_views_visit_seq' AND non_unique = 0 ORDER BY seq_in_index",
				String.class)).containsExactly("visit_key", "seq");
	}

	// 화면을 그리는 GET 주소 가운데 흐름 기록이 걸리는 것은 모두 Screen에 있어야 한다 (새 화면을 만들고 더하지 않으면 여기서 실패한다).
	// 운영자 화면, API, 사진, 에러 화면에는 걸리지 않는다
	@Test
	void 사용자_화면의_주소는_모두_Screen에_있고_흐름_기록은_사용자_화면에만_걸린다() throws Exception {
		List<String> pages = new ArrayList<>();
		for (Map.Entry<RequestMappingInfo, HandlerMethod> mapping : handlerMapping.getHandlerMethods().entrySet()) {
			Set<RequestMethod> methods = mapping.getKey().getMethodsCondition().getMethods();
			HandlerMethod method = mapping.getValue();
			// 화면을 그리는 handler: 이름(String), ModelAndView, View를 돌려주거나 아무것도 돌려주지 않는 것 (본문을 바로 쓰는 것은 뺀다)
			Class<?> returns = method.getReturnType().getParameterType();
			boolean drawsPage = (returns == String.class || returns == void.class || ModelAndView.class.isAssignableFrom(returns)
					|| View.class.isAssignableFrom(returns))
					&& !method.hasMethodAnnotation(ResponseBody.class)
					&& !AnnotatedElementUtils.hasAnnotation(method.getBeanType(), ResponseBody.class);
			if ((methods.isEmpty() || methods.contains(RequestMethod.GET)) && drawsPage) {
				pages.addAll(mapping.getKey().getPatternValues());
			}
		}

		List<String> userPages = new ArrayList<>();
		for (String pattern : pages) {
			String sample = pattern.replaceAll("\\{[^}]+}", "1");
			if (interceptors("GET", sample).stream().anyMatch(PageFlowInterceptor.class::isInstance)) {
				userPages.add(sample);
			}
		}
		assertThat(userPages).contains("/", "/search", "/posts/1", "/posts/new", "/notice", "/feedback", "/privacy", "/terms")
				.allSatisfy(path -> assertThat(Screen.match(path)).as(path).isNotNull());

		assertThat(interceptors("POST", "/posts")).anyMatch(PageFlowInterceptor.class::isInstance);
		assertThat(interceptors("GET", "/admin/stats")).noneMatch(PageFlowInterceptor.class::isInstance);
		assertThat(interceptors("GET", "/api/admin/stats")).noneMatch(PageFlowInterceptor.class::isInstance);
		assertThat(interceptors("GET", "/api/posts/1/analysis")).noneMatch(PageFlowInterceptor.class::isInstance);
		// 앱이 부르는 글 API (JM-90, 기획서 19.3). isNotEmpty: 그 주소가 있는지도 본다 (없는 주소는 걸린 것이 없어 저절로 통과한다)
		assertThat(interceptors("GET", "/api/posts")).isNotEmpty().noneMatch(PageFlowInterceptor.class::isInstance);
		assertThat(interceptors("GET", "/api/posts/1")).isNotEmpty().noneMatch(PageFlowInterceptor.class::isInstance);
		assertThat(interceptors("POST", "/api/posts/1/app-view")).isNotEmpty().noneMatch(PageFlowInterceptor.class::isInstance);
	}

	// 이 테스트만의 utm_campaign 값으로 찾은 방문의 줄 (순서대로)
	private List<Map<String, Object>> rowsOfVisit(String campaign) {
		return jdbcTemplate.queryForList("SELECT * FROM page_views WHERE visit_key = "
				+ "(SELECT visit_key FROM page_views WHERE utm_campaign = ?) ORDER BY seq", campaign);
	}

	private List<HandlerInterceptor> interceptors(String method, String path) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		ServletRequestPathUtils.parseAndCache(request);
		HandlerExecutionChain chain = handlerMapping.getHandler(request);
		return chain == null ? List.of() : chain.getInterceptorList();
	}

	private static MockHttpServletRequest page(String path, String referer, MockHttpSession session) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
		request.addHeader("User-Agent", KAKAO);
		if (referer != null) {
			request.addHeader("Referer", referer);
		}
		request.setSession(session);
		return request;
	}

	private static MockHttpServletResponse html() {
		MockHttpServletResponse response = new MockHttpServletResponse();
		response.setContentType("text/html;charset=UTF-8");
		return response;
	}
}
