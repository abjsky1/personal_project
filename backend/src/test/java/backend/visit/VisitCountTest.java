package backend.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

import backend.image.TestImages;
import backend.visit.model.entity.VisitMetric;
import backend.visit.model.vo.Sido;
import backend.visit.service.VisitCounter;
import backend.visit.web.VisitCountInterceptor;

// 시/도별 하루 합계가 실제 DB에 쌓이는지 (JM-86). 개발 DB에 오늘 기록이 이미 있을 수 있어서 전후 차이로 본다.
// IP 위치 파일을 주지 않아서(app.geoip.path 비움) 모두 "알 수 없음"으로 센다
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
class VisitCountTest {

	private static final String BROWSER = "Mozilla/5.0 (Linux; Android 15; SM-S928N) AppleWebKit/537.36 Chrome/142.0 Mobile Safari/537.36";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private VisitCounter visitCounter;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private HandlerMapping handlerMapping;

	@Test
	void 글을_올리면_오늘의_시도별_합계에_1을_더한다() throws Exception {
		long before = hits("UPLOAD");

		mockMvc.perform(upload(BROWSER)).andExpect(status().is3xxRedirection());

		assertThat(hits("UPLOAD")).isEqualTo(before + 1);
	}

	@Test
	void 로봇이_올린_글은_세지_않는다() throws Exception {
		long before = hits("UPLOAD");

		mockMvc.perform(upload("python-requests/2.32")).andExpect(status().is3xxRedirection());

		assertThat(hits("UPLOAD")).isEqualTo(before);
	}

	// 같은 날 같은 시/도는 한 줄에 더한다. 테이블에는 날짜, 종류, 시/도, 횟수만 있고 IP를 둘 칸이 없다
	@Test
	void 같은_날_같은_시도는_한_줄에_더하고_IP는_남지_않는다() {
		long before = hits("VISIT");

		visitCounter.count(VisitMetric.VISIT, "1.2.3.4");
		visitCounter.count(VisitMetric.VISIT, "5.6.7.8");

		assertThat(hits("VISIT")).isEqualTo(before + 2);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM daily_region_counts WHERE stat_day = ? AND metric = 'VISIT' AND region = ?",
				Long.class, today(), Sido.UNKNOWN)).isEqualTo(1L);
		assertThat(jdbcTemplate.queryForList("SELECT column_name FROM information_schema.columns "
				+ "WHERE table_schema = DATABASE() AND table_name = 'daily_region_counts'", String.class))
				.containsExactlyInAnyOrder("stat_day", "metric", "region", "hit_count");
		// 종류 칸은 MySQL의 enum 칸이 아니라 글자 칸이어야 나중에 종류를 더해도 저장된다 (JM-86 검토)
		assertThat(jdbcTemplate.queryForObject("SELECT data_type FROM information_schema.columns "
				+ "WHERE table_schema = DATABASE() AND table_name = 'daily_region_counts' AND column_name = 'metric'", String.class))
				.isEqualToIgnoringCase("varchar");
	}

	// 방문 세기는 사용자 화면에만 걸고, 운영자 화면과 API(화면이 뒤에서 부르는 것 포함)에는 걸지 않는다
	@Test
	void 방문_세기는_사용자_화면에만_걸린다() throws Exception {
		assertThat(interceptors("GET", "/")).anyMatch(VisitCountInterceptor.class::isInstance);
		assertThat(interceptors("GET", "/posts/1")).anyMatch(VisitCountInterceptor.class::isInstance);
		assertThat(interceptors("POST", "/posts")).anyMatch(VisitCountInterceptor.class::isInstance);
		assertThat(interceptors("GET", "/admin/stats")).noneMatch(VisitCountInterceptor.class::isInstance);
		assertThat(interceptors("GET", "/api/admin/stats")).noneMatch(VisitCountInterceptor.class::isInstance);
		assertThat(interceptors("GET", "/api/posts/1/analysis")).noneMatch(VisitCountInterceptor.class::isInstance);
	}

	private long hits(String metric) {
		Long hits = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(hit_count), 0) FROM daily_region_counts "
				+ "WHERE stat_day = ? AND metric = ? AND region = ?", Long.class, today(), metric, Sido.UNKNOWN);
		return hits == null ? 0 : hits;
	}

	private static LocalDate today() {
		return LocalDate.now(ZoneId.of("Asia/Seoul"));
	}

	private MockMultipartHttpServletRequestBuilder upload(String userAgent) {
		MockMultipartFile photo = new MockMultipartFile("image", "food.jpg", "image/jpeg", TestImages.jpeg(300, 200));
		MockMultipartHttpServletRequestBuilder builder = multipart("/posts");
		builder.file(photo).param("nickname", "테스터").param("password", "1234").header("User-Agent", userAgent);
		return builder;
	}

	private List<HandlerInterceptor> interceptors(String method, String path) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		ServletRequestPathUtils.parseAndCache(request);
		HandlerExecutionChain chain = handlerMapping.getHandler(request);
		return chain == null ? List.of() : chain.getInterceptorList();
	}
}
