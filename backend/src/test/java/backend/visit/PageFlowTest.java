package backend.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerInterceptor;
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

	// 화면 두 개를 실제 인터셉터와 DB로 남기고 칸마다 들어간 값을 본다 (MockMvc는 JSP를 그리지 않아 요청과 응답을 직접 만든다)
	@Test
	void 사람이_본_화면이_방문_번호로_이어져_쌓인다() {
		MockHttpSession session = new MockHttpSession();
		MockHttpServletRequest first = page("/", "https://www.google.com/search?q=jjikmuk", session);
		first.setParameter("utm_source", "instagram");
		first.setParameter("fbclid", "IwAR0value");
		pageFlowInterceptor.afterCompletion(first, html(), new Object(), null);
		pageFlowInterceptor.afterCompletion(page("/posts/7", "http://localhost/", session), html(), new Object(), null);

		List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM page_views WHERE visit_key = "
				+ "(SELECT visit_key FROM page_views ORDER BY id DESC LIMIT 1) ORDER BY seq");
		assertThat(rows).hasSize(2);
		Map<String, Object> entry = rows.get(0);
		assertThat(entry).containsEntry("seq", 1).containsEntry("screen", "FEED").containsEntry("target", 1L)
				.containsEntry("device", "MOBILE").containsEntry("app", "KAKAOTALK").containsEntry("ref_host", "www.google.com")
				.containsEntry("utm_source", "instagram").containsEntry("click_id", "fbclid");
		assertThat(entry.get("prev_screen")).isNull();
		assertThat(entry.get("viewed_at")).isNotNull();
		Map<String, Object> next = rows.get(1);
		assertThat(next).containsEntry("seq", 2).containsEntry("screen", "POST").containsEntry("target", 7L)
				.containsEntry("prev_screen", "FEED");
		assertThat(next.get("app")).isNull();
		assertThat(next.get("ref_host")).isNull();
	}

	// 실제 요청(MockMvc)으로 글을 올리면 올리기 줄이 남는다. 인터셉터가 등록돼 있고 POST /posts의 302를 알아보는지까지 본다
	@Test
	void 글을_올리면_올리기_줄이_남는다() throws Exception {
		MockHttpSession session = new MockHttpSession();
		MockMultipartFile photo = new MockMultipartFile("image", "food.jpg", "image/jpeg", TestImages.jpeg(300, 200));

		mockMvc.perform(multipart("/posts").file(photo).param("nickname", "테스터").param("password", "1234").session(session)
				.header("User-Agent", KAKAO).header("Referer", "http://localhost/posts/new"))
				.andExpect(status().is3xxRedirection());

		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM page_views ORDER BY id DESC LIMIT 1");
		assertThat(row).containsEntry("screen", "UPLOAD").containsEntry("prev_screen", "POST_NEW").containsEntry("device", "MOBILE");
		assertThat(row.get("target")).isNull();
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
			boolean drawsPage = method.getReturnType().getParameterType() == String.class
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
		assertThat(userPages).contains("/", "/posts/1", "/posts/new", "/notice", "/feedback", "/privacy", "/terms")
				.allSatisfy(path -> assertThat(Screen.match(path)).as(path).isNotNull());

		assertThat(interceptors("POST", "/posts")).anyMatch(PageFlowInterceptor.class::isInstance);
		assertThat(interceptors("GET", "/admin/stats")).noneMatch(PageFlowInterceptor.class::isInstance);
		assertThat(interceptors("GET", "/api/admin/stats")).noneMatch(PageFlowInterceptor.class::isInstance);
		assertThat(interceptors("GET", "/api/posts/1/analysis")).noneMatch(PageFlowInterceptor.class::isInstance);
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
		request.addHeader("Referer", referer);
		request.setSession(session);
		return request;
	}

	private static MockHttpServletResponse html() {
		MockHttpServletResponse response = new MockHttpServletResponse();
		response.setContentType("text/html;charset=UTF-8");
		return response;
	}
}
