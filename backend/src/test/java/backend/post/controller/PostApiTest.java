package backend.post.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import backend.analysis.model.dto.ConfirmedItem;
import backend.analysis.model.dto.FoodAnalysisResult;
import backend.analysis.model.dto.FoodAnalysisResult.FoodItem;
import backend.analysis.model.dto.FoodAnalyzerResponse;
import backend.analysis.model.entity.FoodAnalysis;
import backend.analysis.model.repository.FoodAnalysisRepository;
import backend.post.model.dto.FeedPage;
import backend.post.model.dto.PostSummary;
import backend.post.model.entity.Post;
import backend.post.model.repository.PostRepository;
import backend.post.service.PostService;
import tools.jackson.databind.json.JsonMapper;

// 앱이 부르는 글 API를 실제로 불러 보는 테스트 (JM-90, 기획서 19.2, 19.3)
// 개발 DB에 글이 이미 있어도 되도록, 피드의 기대값은 웹 피드(PostService.getFeed)에서 가져온다.
// @Transactional: 테스트마다 DB에 넣은 글과 기록을 되돌린다
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PostApiTest {

	// 앱 이름표의 예 (기획서 19.3)
	private static final String IOS_DEBUG = "JjikmukApp/1.0.0+1 (IOS 27.2; DEBUG)";
	private static final String ANDROID_RELEASE = "JjikmukApp/1.2.0 (ANDROID 17; RELEASE)";
	// 사람의 브라우저. 웹 상세 화면이라면 조회수가 오르는 이름표다 (JM-72)
	private static final String BROWSER =
			"Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 Safari/604.1";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private FoodAnalysisRepository foodAnalysisRepository;

	@Autowired
	private PostService postService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	// 분석 결과를 JSON 글자로 바꿔 저장할 때 쓴다 (서버가 쓰는 것과 같은 변환기)
	@Autowired
	private JsonMapper jsonMapper;

	@Test
	void 피드는_웹_피드와_같은_글을_같은_순서로_24개씩_준다() throws Exception {
		Post newest = null;
		for (int i = 0; i < 25; i++) {
			newest = savePost();
		}
		Post deleted = savePost();
		deleted.deleteByAuthor();
		postRepository.saveAndFlush(deleted);

		for (int page = 1; page <= 2; page++) {
			FeedPage web = postService.getFeed(page);
			DocumentContext app = json(get("/api/posts").param("page", String.valueOf(page)));
			assertThat(idsOf(app)).isEqualTo(web.posts().stream().map(PostSummary::id).toList());
			assertThat(app.read("$.page", Integer.class)).isEqualTo(page);
			assertThat(app.read("$.totalPages", Integer.class)).isEqualTo(web.nav().totalPages());
		}

		// 쪽 번호가 없으면 첫 쪽: 24개, 지운 글을 빼고 가장 최신 글이 맨 앞
		List<Long> first = idsOf(json(get("/api/posts")));
		assertThat(first).hasSize(24).doesNotContain(deleted.getId());
		assertThat(first.get(0)).isEqualTo(newest.getId());
	}

	@Test
	void 마지막_쪽_뒤의_번호는_빈_목록이고_0과_음수는_첫_쪽이다() throws Exception {
		savePost();
		int totalPages = postService.getFeed(1).nav().totalPages();

		// 웹처럼 마지막 쪽으로 보내지 않는다: 앱은 빈 목록을 보고 그만 받는다
		DocumentContext after = json(get("/api/posts").param("page", String.valueOf(totalPages + 1)));
		assertThat(idsOf(after)).isEmpty();
		assertThat(after.read("$.page", Integer.class)).isEqualTo(totalPages + 1);
		assertThat(after.read("$.totalPages", Integer.class)).isEqualTo(totalPages);
		// 아주 큰 번호도 500 없이 빈 목록 (JM-107의 쪽 번호 규칙)
		assertThat(idsOf(json(get("/api/posts").param("page", String.valueOf(Integer.MAX_VALUE))))).isEmpty();

		assertThat(json(get("/api/posts").param("page", "0")).read("$.page", Integer.class)).isEqualTo(1);
		assertThat(json(get("/api/posts").param("page", "-3")).read("$.page", Integer.class)).isEqualTo(1);
	}

	@Test
	void 쪽_번호가_숫자가_아니면_400_JSON이다() throws Exception {
		mockMvc.perform(get("/api/posts").param("page", "abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	void 피드_카드는_썸네일_경로와_닉네임과_웹_카드와_같은_칼로리를_준다() throws Exception {
		Post done = doneWithFoods();
		Post confirmed = doneWithFoods();
		confirm(confirmed);
		Post pending = savePost();
		foodAnalysisRepository.save(new FoodAnalysis(pending));
		// 분석 기록이 없는 옛날 글
		Post old = savePost();

		DocumentContext feed = json(get("/api/posts"));

		Map<String, Object> doneCard = cardOf(feed, done);
		// 보내는 값은 이것뿐이다 (비밀번호 해시, 위치, 촬영 시각은 없다)
		assertThat(doneCard).containsOnlyKeys("id", "nickname", "thumbnailPath", "photoHeld", "analysisStatus", "totalKcal",
				"createdAt");
		assertThat(doneCard).containsEntry("nickname", "앱시험")
				.containsEntry("thumbnailPath", "/images/" + done.getThumbnailKey())
				.containsEntry("photoHeld", false)
				.containsEntry("analysisStatus", "DONE");
		assertThat(decimal(doneCard.get("totalKcal"))).isEqualByComparingTo("325");
		assertUtcSeconds((String) doneCard.get("createdAt"), done.getCreatedAt());

		// 확정한 글은 확정한 칼로리 (웹 카드와 같은 기준, JM-74)
		assertThat(decimal(cardOf(feed, confirmed).get("totalKcal"))).isEqualByComparingTo("310");
		assertThat(cardOf(feed, pending)).containsEntry("analysisStatus", "PENDING").containsEntry("totalKcal", null);
		assertThat(cardOf(feed, old)).containsEntry("analysisStatus", null).containsEntry("totalKcal", null);
	}

	@Test
	void 사진을_가린_글은_사진_경로를_비우고_검토_중_표시만_준다() throws Exception {
		Post held = notFoodPost();

		assertThat(cardOf(json(get("/api/posts")), held)).containsEntry("thumbnailPath", null).containsEntry("photoHeld", true);

		Map<String, Object> detail = json(get("/api/posts/" + held.getId())).read("$");
		assertThat(detail).containsEntry("imagePath", null).containsEntry("photoHeld", true);
	}

	@Test
	void 글_하나는_웹_상세_화면에_보이는_값을_준다() throws Exception {
		Post post = doneWithFoods();
		confirm(post);

		DocumentContext detail = json(get("/api/posts/" + post.getId()));

		Map<String, Object> root = detail.read("$");
		// 보내는 값은 이것뿐이다 (비밀번호 해시, 위치, 촬영 시각은 없다)
		assertThat(root).containsOnlyKeys("id", "nickname", "memo", "imagePath", "photoHeld", "createdAt", "viewCount", "analysis");
		assertThat(root).containsEntry("nickname", "앱시험")
				.containsEntry("memo", "앱으로 보는 글")
				.containsEntry("imagePath", "/images/" + post.getImageKey())
				.containsEntry("photoHeld", false)
				.containsEntry("viewCount", 0);
		assertThat(decimal(root.get("id")).longValue()).isEqualTo(post.getId());
		assertUtcSeconds((String) root.get("createdAt"), post.getCreatedAt());

		Map<String, Object> analysis = detail.read("$.analysis");
		// 분석의 내부 값(모델, 토큰, 걸린 시간, 오류 내용)과 확정 칸의 재료는 없다
		assertThat(analysis).containsOnlyKeys("status", "aiTotals", "confirmedTotals", "foods", "confidenceLabel", "comment");
		assertThat(analysis).containsEntry("status", "DONE")
				.containsEntry("confidenceLabel", "높음")
				.containsEntry("comment", "1공기와 김치");
		// AI 줄(첫 답의 합계)과 REAL 줄(확정한 합계)
		assertThat(decimal(detail.read("$.analysis.aiTotals.kcal"))).isEqualByComparingTo("325");
		assertThat(decimal(detail.read("$.analysis.aiTotals.carbsG"))).isEqualByComparingTo("70");
		assertThat(decimal(detail.read("$.analysis.aiTotals.fatG"))).isEqualByComparingTo("1.5");
		assertThat(decimal(detail.read("$.analysis.confirmedTotals.kcal"))).isEqualByComparingTo("310");
		assertThat(decimal(detail.read("$.analysis.confirmedTotals.proteinG"))).isEqualByComparingTo("5");

		// 음식 표: 음식마다 AI 줄과 REAL 줄. 공기밥은 AI와 같음, 김치는 "이 음식 아님"으로 뺌
		assertThat(detail.read("$.analysis.foods[0].change", String.class)).isEqualTo("SAME");
		assertThat(detail.read("$.analysis.foods[0].ai.name", String.class)).isEqualTo("공기밥");
		assertThat(decimal(detail.read("$.analysis.foods[0].ai.amountG"))).isEqualByComparingTo("210");
		assertThat(detail.read("$.analysis.foods[0].confirmed.name", String.class)).isEqualTo("공기밥");
		assertThat(detail.read("$.analysis.foods[1].change", String.class)).isEqualTo("REMOVED");
		assertThat(detail.read("$.analysis.foods[1].ai.name", String.class)).isEqualTo("김치");
		Map<String, Object> removed = detail.read("$.analysis.foods[1]");
		assertThat(removed).containsEntry("confirmed", null);
	}

	@Test
	void 분석_중인_글은_합계가_없고_분석_기록이_없는_옛날_글은_분석이_없다() throws Exception {
		Post pending = savePost();
		foodAnalysisRepository.save(new FoodAnalysis(pending));
		Post old = savePost();

		DocumentContext pendingDetail = json(get("/api/posts/" + pending.getId()));
		Map<String, Object> analysis = pendingDetail.read("$.analysis");
		assertThat(analysis).containsEntry("status", "PENDING").containsEntry("aiTotals", null).containsEntry("confirmedTotals", null);
		List<?> foods = pendingDetail.read("$.analysis.foods");
		assertThat(foods).isEmpty();

		Map<String, Object> oldDetail = json(get("/api/posts/" + old.getId())).read("$");
		assertThat(oldDetail).containsEntry("analysis", null);
	}

	@Test
	void 없는_글이나_지운_글은_404이고_앱_조회도_남지_않는다() throws Exception {
		mockMvc.perform(get("/api/posts/999999999"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
		mockMvc.perform(post("/api/posts/999999999/app-view").header("User-Agent", IOS_DEBUG))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));

		Post deleted = savePost();
		deleted.deleteByAuthor();
		postRepository.saveAndFlush(deleted);

		mockMvc.perform(get("/api/posts/" + deleted.getId())).andExpect(status().isNotFound());
		mockMvc.perform(post(appViewUrl(deleted)).header("User-Agent", IOS_DEBUG)).andExpect(status().isNotFound());
		assertThat(appViewsOf(deleted)).isEmpty();
	}

	@Test
	void 앱_조회_기록은_부를_때마다_한_줄을_남기고_남기는_값은_다섯뿐이다() throws Exception {
		Post post = savePost();

		mockMvc.perform(post(appViewUrl(post)).header("User-Agent", IOS_DEBUG))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));
		mockMvc.perform(post(appViewUrl(post)).header("User-Agent", ANDROID_RELEASE))
				.andExpect(status().isNoContent());

		List<Map<String, Object>> rows = appViewsOf(post);
		assertThat(rows).hasSize(2);
		// 글 번호, 플랫폼, 빌드, 앱 버전, 시각 (그리고 줄 번호). IP와 기기 번호는 없다 (기획서 19.3)
		assertThat(rows.get(0).keySet()).containsExactlyInAnyOrder("id", "post_id", "platform", "build", "app_version", "viewed_at");
		assertThat(rows.get(0)).containsEntry("platform", "IOS").containsEntry("build", "DEBUG").containsEntry("app_version", "1.0.0+1");
		assertThat(rows.get(1)).containsEntry("platform", "ANDROID").containsEntry("build", "RELEASE")
				.containsEntry("app_version", "1.2.0");
	}

	@Test
	void 앱_이름표가_없거나_모양이_다르면_400이고_기록하지_않는다() throws Exception {
		Post post = savePost();

		mockMvc.perform(post(appViewUrl(post)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_APP_AGENT"));
		mockMvc.perform(post(appViewUrl(post)).header("User-Agent", BROWSER))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_APP_AGENT"));
		assertThat(appViewsOf(post)).isEmpty();
	}

	@Test
	void 앱_API는_웹_조회수를_올리지_않고_상세를_읽기만_해서는_앱_조회도_남지_않는다() throws Exception {
		Post post = savePost();

		// 브라우저 이름표로 불러도 세지 않는다: 웹 조회수는 HTML 상세 화면에서만 오른다 (기획서 19.3)
		mockMvc.perform(get("/api/posts").header("User-Agent", BROWSER)).andExpect(status().isOk());
		mockMvc.perform(get("/api/posts/" + post.getId()).header("User-Agent", BROWSER)).andExpect(status().isOk());
		mockMvc.perform(get("/api/posts/" + post.getId()).header("User-Agent", IOS_DEBUG)).andExpect(status().isOk());
		assertThat(appViewsOf(post)).isEmpty();

		mockMvc.perform(post(appViewUrl(post)).header("User-Agent", IOS_DEBUG)).andExpect(status().isNoContent());
		assertThat(appViewsOf(post)).hasSize(1);
		assertThat(viewCountOf(post)).isZero();
	}

	private Post savePost() {
		String key = UUID.randomUUID().toString();
		return postRepository.save(new Post("앱시험", "hash", "앱으로 보는 글", key + ".jpg", key + "-thumb.jpg", null));
	}

	// AI가 음식 둘(공기밥 310kcal, 김치 15kcal)을 본 글. AI의 합계는 325kcal
	private Post doneWithFoods() throws Exception {
		Post post = savePost();
		FoodAnalysisResult result = new FoodAnalysisResult(true,
				List.of(new FoodItem("공기밥", 210, 310, 68, 5, 1), new FoodItem("김치", 50, 15, 2, 1, 0.5)), "HIGH", "1공기와 김치");
		FoodAnalysis analysis = new FoodAnalysis(post);
		analysis.markDone(new FoodAnalyzerResponse(result, "test-model", 1300, 100), jsonMapper.writeValueAsString(result.items()), 2000);
		foodAnalysisRepository.save(analysis);
		return post;
	}

	// 공기밥은 AI의 답 그대로 두고, 김치는 "이 음식 아님"으로 빼서 확정한다 (확정한 합계 310kcal)
	private void confirm(Post post) throws Exception {
		List<ConfirmedItem> items = List.of(
				ConfirmedItem.candidate(0, new FoodItem("공기밥", 210, 310, 68, 5, 1)), ConfirmedItem.removed("김치"));
		int changed = foodAnalysisRepository.confirmOnce(post.getId(), jsonMapper.writeValueAsString(items), new BigDecimal("310.0"),
				new BigDecimal("68.0"), new BigDecimal("5.0"), new BigDecimal("1.0"), false, Instant.now());
		assertThat(changed).isEqualTo(1);
	}

	// AI가 음식이 아니라고 봐서 사진을 가린 글 (JM-80)
	private Post notFoodPost() {
		Post post = savePost();
		FoodAnalysis analysis = new FoodAnalysis(post);
		analysis.markNotFood(new FoodAnalyzerResponse(
				new FoodAnalysisResult(false, List.of(), "HIGH", "음식이 아니에요"), "test-model", 1300, 50), 1500);
		foodAnalysisRepository.save(analysis);
		return post;
	}

	// 요청을 보내 200인지 본 뒤, 응답 JSON을 읽는다 (한글이 깨지지 않게 UTF-8로)
	private DocumentContext json(MockHttpServletRequestBuilder request) throws Exception {
		String body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
		return JsonPath.parse(body);
	}

	private List<Long> idsOf(DocumentContext feed) {
		List<Number> ids = feed.read("$.posts[*].id");
		return ids.stream().map(Number::longValue).toList();
	}

	private Map<String, Object> cardOf(DocumentContext feed, Post post) {
		List<Map<String, Object>> cards = feed.read("$.posts[?(@.id == " + post.getId() + ")]");
		assertThat(cards).hasSize(1);
		return cards.get(0);
	}

	private List<Map<String, Object>> appViewsOf(Post post) {
		return jdbcTemplate.queryForList("SELECT * FROM app_post_views WHERE post_id = ? ORDER BY id", post.getId());
	}

	private int viewCountOf(Post post) {
		return jdbcTemplate.queryForObject("SELECT view_count FROM posts WHERE id = ?", Integer.class, post.getId());
	}

	private static String appViewUrl(Post post) {
		return "/api/posts/" + post.getId() + "/app-view";
	}

	// JSON의 숫자는 정수면 Integer, 소수면 Double로 읽혀서 BigDecimal로 맞춰 견준다
	private static BigDecimal decimal(Object number) {
		return new BigDecimal(String.valueOf(number));
	}

	// UTC의 ISO-8601이고 초까지만이다 (예: 2026-11-28T08:24:58Z). 저장한 시각과는 1초 안에서 같다 (DB가 초 아래를 반올림할 수 있다)
	private static void assertUtcSeconds(String text, Instant saved) {
		assertThat(text).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
		assertThat(Instant.parse(text)).isCloseTo(saved, within(1, ChronoUnit.SECONDS));
	}
}
