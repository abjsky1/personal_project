package backend.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import backend.analysis.model.dto.FoodAnalysisResult;
import backend.analysis.model.dto.FoodAnalysisResult.FoodItem;
import backend.analysis.model.dto.FoodAnalyzerResponse;
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
import backend.stats.model.dto.DailyActivity;
import backend.stats.model.dto.FeedbackCounts;
import backend.stats.model.dto.LocationCounts;
import backend.stats.model.mapper.StatsMapper;
import backend.stats.service.StatsService;

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
				.andExpect(jsonPath("$.analysis.dailyLimit").value(200));
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

	private FoodAnalyzerResponse foodResponse(boolean food, int inputTokens, int outputTokens) {
		List<FoodItem> items = food ? List.of(new FoodItem("밥", 200, 300, 60, 5, 1)) : List.of();
		return new FoodAnalyzerResponse(new FoodAnalysisResult(food, items, "HIGH", "코멘트"), "test-model", inputTokens, outputTokens);
	}
}
