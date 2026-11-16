package backend.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import backend.admin.AdminTokenInterceptor;
import backend.analysis.model.dto.ConfirmedItem;
import backend.analysis.model.dto.FoodAnalysisResult;
import backend.analysis.model.dto.FoodAnalysisResult.FoodItem;
import backend.analysis.model.dto.FoodAnalyzerResponse;
import backend.analysis.model.dto.FoodCandidates.Candidate;
import backend.analysis.model.dto.FoodCandidates.CandidateItem;
import backend.analysis.model.entity.ConfirmMode;
import backend.analysis.model.entity.FoodAnalysis;
import backend.analysis.model.repository.FoodAnalysisRepository;
import backend.comment.model.entity.Comment;
import backend.comment.model.repository.CommentRepository;
import backend.feedback.model.entity.Feedback;
import backend.feedback.model.entity.FeedbackProgress;
import backend.feedback.model.repository.FeedbackRepository;
import backend.location.model.entity.PostLocation;
import backend.location.model.repository.PostLocationRepository;
import backend.location.model.vo.GeoPoint;
import backend.post.model.entity.Post;
import backend.post.model.repository.PostRepository;
import backend.stats.model.dto.AdminStatsResponse;
import backend.stats.model.dto.AdminStatsResponse.AnalysisStats;
import backend.stats.model.dto.AdminStatsResponse.LocationStats;
import backend.stats.model.dto.AnalysisCounts;
import backend.stats.model.dto.ConfirmCounts;
import backend.stats.model.dto.ConfirmedRow;
import backend.stats.model.dto.DailyActivity;
import backend.stats.model.dto.FeedbackCounts;
import backend.stats.model.dto.LocationCounts;
import backend.stats.model.mapper.StatsMapper;
import backend.stats.service.StatsService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

// 운영자 통계의 토큰 검사와 SQL 결과를 실제 DB로 확인하는 테스트
// 개발 DB에는 이미 기록이 있어서, 숫자를 정확히 맞춰 볼 때는 기록을 먼 미래(2099년)로 옮기고 그 시각 이후만 조회한다.
// MyBatis는 JPA가 아직 DB에 쓰지 않은 내용을 모른다. 그래서 저장은 모두 saveAndFlush로 바로 DB에 쓴다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.admin.token=test-admin-token")
class AdminStatsTest {

	private static final String TOKEN = "test-admin-token";
	private static final LocalDateTime FUTURE = LocalDateTime.of(2099, 1, 1, 0, 0);
	private static final TypeReference<List<FoodItem>> FOOD_ITEMS = new TypeReference<>() {
	};
	private static final TypeReference<List<ConfirmedItem>> CONFIRMED_ITEMS = new TypeReference<>() {
	};

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private StatsMapper statsMapper;

	@Autowired
	private StatsService statsService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private CommentRepository commentRepository;

	@Autowired
	private FeedbackRepository feedbackRepository;

	@Autowired
	private FoodAnalysisRepository foodAnalysisRepository;

	@Autowired
	private PostLocationRepository postLocationRepository;

	@Autowired
	private JsonMapper jsonMapper;

	@Test
	void 토큰이_없으면_통계를_볼_수_없다() throws Exception {
		mockMvc.perform(get("/api/admin/stats"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("ADMIN_UNAUTHORIZED"));
	}

	@Test
	void 기간을_안_주면_오늘까지_7일을_날짜마다_돌려준다() throws Exception {
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

		mockMvc.perform(get("/api/admin/stats").header(AdminTokenInterceptor.HEADER, TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.days").value(7))
				.andExpect(jsonPath("$.from").value(today.minusDays(6).toString()))
				.andExpect(jsonPath("$.to").value(today.toString()))
				.andExpect(jsonPath("$.daily.length()").value(7))
				.andExpect(jsonPath("$.daily[0].day").value(today.minusDays(6).toString()))
				.andExpect(jsonPath("$.daily[6].day").value(today.toString()))
				.andExpect(jsonPath("$.analysis.dailyLimit").value(500));
	}

	@Test
	void 기간이_1일에서_90일을_벗어나면_400이다() throws Exception {
		for (String days : List.of("0", "91")) {
			mockMvc.perform(get("/api/admin/stats").param("days", days).header(AdminTokenInterceptor.HEADER, TOKEN))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}
	}

	@Test
	void 지금_올린_글은_한국_날짜로_오늘에_들어간다() {
		long before = lastDay(statsService.getStats(1)).posts();

		savePost();

		assertThat(lastDay(statsService.getStats(1)).posts()).isEqualTo(before + 1);
	}

	@Test
	void 날짜는_한국_시간_자정으로_나눈다() {
		// DB의 시각은 UTC다. 14:59:59(UTC)는 한국의 23:59:59, 15:00:00(UTC)은 한국의 다음 날 00:00:00
		moveCreatedAt("posts", "id", savePost().getId(), FUTURE.withHour(14).withMinute(59).withSecond(59));
		moveCreatedAt("posts", "id", savePost().getId(), FUTURE.withHour(15));

		assertThat(statsMapper.findDailyActivity(FUTURE)).containsExactly(
				new DailyActivity(LocalDate.of(2099, 1, 1), 1, 0, 0),
				new DailyActivity(LocalDate.of(2099, 1, 2), 1, 0, 0));
	}

	@Test
	void 글_댓글_피드백을_같은_날짜에_합치고_삭제된_것도_센다() {
		Post post = savePost();
		moveCreatedAt("posts", "id", post.getId(), FUTURE);

		Comment comment = commentRepository.saveAndFlush(new Comment(post.getId(), "댓글러", "hash", "댓글"));
		moveCreatedAt("comments", "id", comment.getId(), FUTURE);
		Comment reply = commentRepository.saveAndFlush(new Comment(post.getId(), comment.getId(), "답글러", "hash", "답글"));
		moveCreatedAt("comments", "id", reply.getId(), FUTURE);

		Feedback feedback = feedbackRepository.saveAndFlush(new Feedback("사용자", "hash", "제목", "내용"));
		feedback.deleteByAuthor();
		feedbackRepository.saveAndFlush(feedback);
		moveCreatedAt("feedbacks", "id", feedback.getId(), FUTURE);

		assertThat(statsMapper.findDailyActivity(FUTURE)).containsExactly(new DailyActivity(LocalDate.of(2099, 1, 1), 1, 2, 1));
	}

	@Test
	void 시작_시각_전의_기록은_세지_않는다() {
		moveCreatedAt("posts", "id", savePost().getId(), FUTURE.minusSeconds(1));

		assertThat(statsMapper.findDailyActivity(FUTURE)).isEmpty();
	}

	@Test
	void AI_분석을_상태별로_세고_응답_시간과_토큰을_낸다() {
		FoodAnalysis done = new FoodAnalysis(savePost());
		done.markDone(foodResponse(true, 30, 10), "[]", 1000);
		saveAnalysis(done);

		FoodAnalysis notFood = new FoodAnalysis(savePost());
		notFood.markNotFood(foodResponse(false, 20, 5), 2000);
		saveAnalysis(notFood);

		FoodAnalysis failed = new FoodAnalysis(savePost());
		failed.markFailed("HTTP_503", 3000);
		saveAnalysis(failed);

		FoodAnalysis limitExceeded = new FoodAnalysis(savePost());
		limitExceeded.markLimitExceeded();
		saveAnalysis(limitExceeded);

		saveAnalysis(new FoodAnalysis(savePost()));

		// AI를 부르지 않은 한도 초과와 대기 중은 응답 시간이 없어서 평균에 들어가지 않는다
		assertThat(statsMapper.findAnalysisCounts(FUTURE)).isEqualTo(new AnalysisCounts(1, 1, 1, 1, 1, 2000.0, 50, 15));
	}

	@Test
	void 기록이_없으면_상태별_건수는_0이고_응답_시간은_비어_있다() {
		LocalDateTime farther = FUTURE.plusYears(1);

		assertThat(statsMapper.findAnalysisCounts(farther)).isEqualTo(new AnalysisCounts(0, 0, 0, 0, 0, null, 0, 0));
		assertThat(statsMapper.findLatencyP95(farther)).isNull();
		assertThat(statsMapper.findLocationCounts(farther)).isEqualTo(new LocationCounts(0, 0));
	}

	@Test
	void 응답_시간_20건의_95백분위는_19번째_값이다() {
		for (long latency = 100; latency <= 2000; latency += 100) {
			FoodAnalysis failed = new FoodAnalysis(savePost());
			failed.markFailed("HTTP_503", latency);
			saveAnalysis(failed);
		}

		assertThat(statsMapper.findLatencyP95(FUTURE)).isEqualTo(1900L);
	}

	@Test
	void 위치_수집률은_남아_있는_글만_본다() {
		Post withLocation = savePost();
		postLocationRepository.saveAndFlush(new PostLocation(withLocation, GeoPoint.of(37.566535, 126.977969), Instant.now()));
		moveCreatedAt("posts", "id", withLocation.getId(), FUTURE);

		moveCreatedAt("posts", "id", savePost().getId(), FUTURE);

		Post deleted = savePost();
		deleted.deleteByAuthor();
		postRepository.saveAndFlush(deleted);
		moveCreatedAt("posts", "id", deleted.getId(), FUTURE);

		assertThat(statsMapper.findLocationCounts(FUTURE)).isEqualTo(new LocationCounts(2, 1));
	}

	// 지역별 글 수 (JM-85): 위치 수집률과 같은 글(남아 있고 위치가 저장된 글)의 좌표만 읽는다
	@Test
	void 지역을_찾을_좌표는_남아_있는_글의_것만_읽는다() {
		Post active = savePost();
		postLocationRepository.saveAndFlush(new PostLocation(active, GeoPoint.of(37.566535, 126.977969), Instant.now()));
		moveCreatedAt("posts", "id", active.getId(), FUTURE);

		Post hidden = savePost();
		postLocationRepository.saveAndFlush(new PostLocation(hidden, GeoPoint.of(35.115225, 129.042243), Instant.now()));
		moveCreatedAt("posts", "id", hidden.getId(), FUTURE);
		setPostStatus(hidden, "DELETED_BY_ADMIN");

		moveCreatedAt("posts", "id", savePost().getId(), FUTURE);

		assertThat(statsMapper.findLocatedPoints(FUTURE)).containsExactly(GeoPoint.of(37.566535, 126.977969));
		assertThat(statsMapper.findLocatedPoints(FUTURE.plusYears(1))).isEmpty();
	}

	@Test
	void 피드백을_처리_상태별로_세고_삭제된_것은_뺀다() {
		FeedbackCounts before = statsMapper.findFeedbackCounts();

		feedbackRepository.saveAndFlush(new Feedback("사용자", "hash", "접수", "내용"));
		Feedback done = new Feedback("사용자", "hash", "반영", "내용");
		done.changeProgress(FeedbackProgress.DONE);
		feedbackRepository.saveAndFlush(done);
		Feedback deleted = new Feedback("사용자", "hash", "삭제", "내용");
		deleted.deleteByAuthor();
		feedbackRepository.saveAndFlush(deleted);

		FeedbackCounts after = statsMapper.findFeedbackCounts();
		assertThat(after.received()).isEqualTo(before.received() + 1);
		assertThat(after.done()).isEqualTo(before.done() + 1);
		assertThat(after.inProgress()).isEqualTo(before.inProgress());
		assertThat(after.onHold()).isEqualTo(before.onHold());
	}

	@Test
	void 성공률은_AI가_답한_건만_놓고_계산한다() {
		AnalysisCounts counts = new AnalysisCounts(8, 1, 1, 5, 5, 2400.4, 100, 50);

		AnalysisStats stats = AnalysisStats.of(counts, 5000L, 20, 200);

		assertThat(stats.successRate()).isEqualTo(90.0);
		assertThat(stats.avgLatencyMs()).isEqualTo(2400L);
		assertThat(stats.todayUsageRate()).isEqualTo(10.0);
	}

	@Test
	void 나눌_기록이_없으면_비율은_비어_있다() {
		AnalysisStats stats = AnalysisStats.of(new AnalysisCounts(0, 0, 0, 3, 0, null, 0, 0), null, 0, 200);

		assertThat(stats.successRate()).isNull();
		assertThat(stats.avgLatencyMs()).isNull();
		assertThat(LocationStats.of(new LocationCounts(0, 0)).collectionRate()).isNull();
	}

	// 고쳐 쓰는 AI의 확정 통계 (JM-75). 운영자가 지운 글은 빼고 글쓴이가 지운 글은 넣는다
	// 열 순서가 뒤바뀌어도 잡히도록 아홉 건수가 모두 다른 수가 되게 글을 만든다
	@Test
	void 확정_통계는_확정_방식과_확정과_직접_적기를_세고_운영자가_지운_글은_뺀다() {
		saveConfirmCase(ConfirmMode.AUTO, true, false, false);
		saveConfirmCase(ConfirmMode.AUTO, true, false, false);
		saveConfirmCase(ConfirmMode.AUTO, false, false, false);
		setPostStatus(saveConfirmCase(ConfirmMode.AUTO, true, false, false), "DELETED_BY_AUTHOR");
		setPostStatus(saveConfirmCase(ConfirmMode.AUTO, true, false, false), "DELETED_BY_ADMIN");
		Post candidate = saveConfirmCase(ConfirmMode.USER, true, true, false);
		Post typed = saveConfirmCase(ConfirmMode.USER, true, true, true);
		Post authorDeleted = saveConfirmCase(ConfirmMode.USER, true, true, false);
		setPostStatus(authorDeleted, "DELETED_BY_AUTHOR");
		setPostStatus(saveConfirmCase(ConfirmMode.USER, true, true, false), "DELETED_BY_ADMIN");
		typedCallOnly(saveConfirmCase(ConfirmMode.USER, true, false, false), 150, 35);
		saveConfirmCase(ConfirmMode.USER, true, false, false);
		saveConfirmCase(ConfirmMode.USER, false, false, false);

		// AUTO 4(글쓴이가 지운 글 포함), USER 6, 칼로리가 나온 글 8, 확정할 수 있던 글 5, 확정 3,
		// 직접 적기로 AI를 부른 글 2(확정한 글 하나와 확정 전인 글 하나), 사진과 다름 1, 그 토큰 100 + 150, 20 + 35
		assertThat(statsMapper.findConfirmCounts(FUTURE)).isEqualTo(new ConfirmCounts(4, 6, 8, 5, 3, 2, 1, 250, 55));

		// 확정한 글의 기록: 운영자가 지운 글은 빠지고, AI의 첫 답(items)과 확정한 값(confirmed_items)이 제 칸에서 온다
		List<ConfirmedRow> rows = statsMapper.findConfirmedRows(FUTURE);
		assertThat(rows).extracting(ConfirmedRow::postId)
				.containsExactly(candidate.getId(), typed.getId(), authorDeleted.getId());
		ConfirmedRow typedRow = rows.get(1);
		assertThat(typedRow.totalKcal()).isEqualByComparingTo("300");
		assertThat(typedRow.confirmedTotalKcal()).isEqualByComparingTo("400");
		assertThat(jsonMapper.readValue(typedRow.items(), FOOD_ITEMS)).extracting(FoodItem::name).containsExactly("검증용비밀음식");
		assertThat(jsonMapper.readValue(typedRow.confirmedItems(), CONFIRMED_ITEMS)).extracting(ConfirmedItem::name)
				.containsExactly("직접적은비밀음식");
	}

	// 확정 통계를 세기 시작하는 시각: 기간의 시작(한국 날짜의 자정)과 고쳐 쓰는 AI 배포 시각 중 늦은 쪽을 한국 시각으로 낸다
	@Test
	void 확정_통계의_시작_시각은_한국_시각이고_배포_전으로_가지_않는다() {
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
		LocalDateTime released = LocalDateTime.of(2026, 11, 13, 10, 5);

		assertThat(statsService.getStats(1).confirm().since()).isEqualTo(today.atStartOfDay());
		LocalDateTime ninetyDays = today.minusDays(89).atStartOfDay();
		assertThat(statsService.getStats(90).confirm().since()).isEqualTo(ninetyDays.isAfter(released) ? ninetyDays : released);
	}

	// 확정한 기록을 읽지 못하면 그 글만 빼고 skipped로 센다 (통계 화면 전체가 깨지지 않게)
	@Test
	void 읽지_못한_확정_기록은_빼고_skipped로_센다() {
		long before = statsService.getStats(1).confirm().skipped();
		Post broken = saveConfirmCase(ConfirmMode.USER, true, true, false);
		jdbcTemplate.update("UPDATE food_analyses SET confirmed_items = '[{' WHERE post_id = ?", broken.getId());

		assertThat(statsService.getStats(1).confirm().skipped()).isEqualTo(before + 1);
	}

	@Test
	void 확정_기록이_없으면_건수는_0이다() {
		LocalDateTime later = FUTURE.plusYears(1);

		assertThat(statsMapper.findConfirmCounts(later)).isEqualTo(new ConfirmCounts(0, 0, 0, 0, 0, 0, 0, 0, 0));
		assertThat(statsMapper.findConfirmedRows(later)).isEmpty();
	}

	@Test
	void 통계_API에_확정_통계가_맨_뒤에_붙고_음식_이름은_나가지_않는다() throws Exception {
		long typedBefore = statsService.getStats(1).confirm().foods().typed();
		saveConfirmCase(ConfirmMode.USER, true, true, true);

		String body = mockMvc.perform(get("/api/admin/stats").param("days", "1").header(AdminTokenInterceptor.HEADER, TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.confirm.since").exists())
				.andExpect(jsonPath("$.feedback").exists())
				.andExpect(jsonPath("$.regions.dong.top").isArray())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		// 직접 적은 음식이 하나 늘어난 응답이다 (만든 기록이 통계에 들어갔다). 그래도 음식 이름은 나가지 않는다
		assertThat(jsonMapper.readTree(body).path("confirm").path("foods").path("typed").asLong()).isEqualTo(typedBefore + 1);
		assertThat(body).doesNotContain("검증용비밀음식").doesNotContain("직접적은비밀음식");
		// 지역별 글 수(JM-85)는 이름과 개수만 낸다: 좌표의 키가 없다
		assertThat(body).doesNotContain("latitude").doesNotContain("longitude");
	}

	private DailyActivity lastDay(AdminStatsResponse stats) {
		return stats.daily().get(stats.daily().size() - 1);
	}

	private Post savePost() {
		return postRepository.saveAndFlush(new Post("테스터", "hash", null, "test.jpg", "test-thumb.jpg", null));
	}

	// 분석을 저장하고 그 기록을 먼 미래로 옮긴다
	private void saveAnalysis(FoodAnalysis analysis) {
		FoodAnalysis saved = foodAnalysisRepository.saveAndFlush(analysis);
		moveCreatedAt("food_analyses", "post_id", saved.getPostId(), FUTURE);
	}

	private void moveCreatedAt(String table, String idColumn, Long id, LocalDateTime utc) {
		jdbcTemplate.update("UPDATE " + table + " SET created_at = ? WHERE " + idColumn + " = ?", utc, id);
	}

	// 확정 통계용 글과 분석 (JM-75). done: 칼로리가 나왔는지(아니면 음식 아님), confirm: 확정했는지, typed: 이름을 직접 적었는지
	// "내가 보고 확정"으로 올린 글은 분석이 끝나면 후보를 갖는다. 확정과 직접 적기는 실제 서비스와 같은 UPDATE로 쓴다
	private Post saveConfirmCase(ConfirmMode mode, boolean done, boolean confirm, boolean typed) {
		Post post = savePost();
		FoodAnalysis analysis = new FoodAnalysis(post, mode);
		FoodItem first = new FoodItem("검증용비밀음식", 200, 300, 60, 5, 1);
		if (done) {
			analysis.markDone(new FoodAnalyzerResponse(new FoodAnalysisResult(true, List.of(first), "HIGH", "코멘트"), "test-model", 1000, 100),
					jsonMapper.writeValueAsString(List.of(first)), 2000);
			if (mode == ConfirmMode.USER) {
				analysis.keepCandidates(jsonMapper.writeValueAsString(
						List.of(new CandidateItem(200, List.of(new Candidate("검증용비밀음식", 150, 30, 2.5, 0.5))))));
			}
		} else {
			analysis.markNotFood(foodResponse(false, 1000, 50), 1500);
		}
		foodAnalysisRepository.saveAndFlush(analysis);
		if (typed) {
			foodAnalysisRepository.claimTyped(post.getId(), Instant.now());
			foodAnalysisRepository.recordTypedCall(post.getId(), 100, 20, 1000);
		}
		if (confirm) {
			ConfirmedItem item = typed
					? ConfirmedItem.typed(new FoodItem("직접적은비밀음식", 200, 400, 60, 5, 1), false)
					: ConfirmedItem.candidate(0, first);
			foodAnalysisRepository.confirmOnce(post.getId(), jsonMapper.writeValueAsString(List.of(item)),
					BigDecimal.valueOf(typed ? 400 : 300), BigDecimal.valueOf(60), BigDecimal.valueOf(5), BigDecimal.ONE, typed, Instant.now());
		}
		moveCreatedAt("food_analyses", "post_id", post.getId(), FUTURE);
		return post;
	}

	// 이름을 직접 적어 AI를 한 번 더 불렀지만 아직 확정하지 않은 글 (실제 서비스와 같은 UPDATE)
	private void typedCallOnly(Post post, int inputTokens, int outputTokens) {
		foodAnalysisRepository.claimTyped(post.getId(), Instant.now());
		foodAnalysisRepository.recordTypedCall(post.getId(), inputTokens, outputTokens, 1000);
	}

	private void setPostStatus(Post post, String status) {
		jdbcTemplate.update("UPDATE posts SET status = ? WHERE id = ?", status, post.getId());
	}

	private FoodAnalyzerResponse foodResponse(boolean food, int inputTokens, int outputTokens) {
		List<FoodItem> items = food ? List.of(new FoodItem("밥", 200, 300, 60, 5, 1)) : List.of();
		return new FoodAnalyzerResponse(new FoodAnalysisResult(food, items, "HIGH", "코멘트"), "test-model", inputTokens, outputTokens);
	}
}
