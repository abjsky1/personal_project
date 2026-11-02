package backend.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import backend.analysis.model.dto.FoodAnalysisResult;
import backend.analysis.model.dto.FoodAnalysisResult.FoodItem;
import backend.analysis.model.dto.FoodAnalyzerResponse;
import backend.analysis.model.entity.AnalysisStatus;
import backend.analysis.model.entity.FoodAnalysis;
import backend.analysis.model.repository.FoodAnalysisRepository;
import backend.image.ImageStorage;
import backend.post.model.entity.Post;
import tools.jackson.databind.json.JsonMapper;

// 분석 결과에 따라 무엇이 저장되는지 확인하는 테스트
// mock(): 정해 둔 대로만 답하는 가짜 객체. 실제 AI·DB·저장소 없이 판단 로직만 확인한다.
class FoodAnalysisServiceTest {

	private static final Long POST_ID = 1L;
	private static final String IMAGE_KEY = "photo.jpg";
	private static final int DAILY_LIMIT = 200;

	private final FoodAnalysisRepository repository = mock(FoodAnalysisRepository.class);
	private final FoodAnalyzer analyzer = mock(FoodAnalyzer.class);
	private final ImageStorage storage = mock(ImageStorage.class);
	private final byte[] jpeg = {1, 2, 3};

	private final FoodAnalysisService service = new FoodAnalysisService(repository, analyzer, storage, JsonMapper.builder().build(), DAILY_LIMIT);

	private FoodAnalysis analysis;

	@BeforeEach
	void setUp() {
		analysis = new FoodAnalysis(new Post("테스터", "hash", null, IMAGE_KEY, "thumb.jpg", null));
		when(repository.findById(POST_ID)).thenReturn(Optional.of(analysis));
		when(storage.load(IMAGE_KEY)).thenReturn(Optional.of(jpeg));
	}

	@Test
	void 음식이면_합계를_서버에서_더해_DONE으로_저장한다() {
		aiAnswers(new FoodAnalysisResult(true, List.of(
				new FoodItem("김치찌개", 400, 320.5, 12, 22, 20),
				new FoodItem("공기밥", 210, 310, 68, 5, 1)), "MEDIUM", "1인분 기준"));

		service.analyze(POST_ID, IMAGE_KEY);

		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.DONE);
		assertThat(analysis.getTotalKcal()).isEqualByComparingTo("630.5");
		assertThat(analysis.getCarbsG()).isEqualByComparingTo("80");
		assertThat(analysis.getProteinG()).isEqualByComparingTo("27");
		assertThat(analysis.getFatG()).isEqualByComparingTo("21");
		assertThat(analysis.getItems()).contains("김치찌개", "공기밥");
		assertThat(analysis.getModel()).isEqualTo("test-model");
		assertThat(analysis.getInputTokens()).isEqualTo(1300);
		assertThat(analysis.getAttemptCount()).isEqualTo(1);
		assertThat(analysis.getCompletedAt()).isNotNull();
		verify(repository).save(analysis);
	}

	@Test
	void 음식이_아니면_NOT_FOOD로_저장한다() {
		aiAnswers(new FoodAnalysisResult(false, List.of(), "HIGH", "음식이 아니에요"));

		service.analyze(POST_ID, IMAGE_KEY);

		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.NOT_FOOD);
		assertThat(analysis.getTotalKcal()).isNull();
		verify(repository).save(analysis);
	}

	@Test
	void AI_호출이_실패하면_이유와_함께_FAILED로_저장한다() {
		when(analyzer.analyze(jpeg)).thenThrow(new FoodAnalysisException("HTTP_503"));

		service.analyze(POST_ID, IMAGE_KEY);

		assertThatFailed("HTTP_503");
	}

	@Test
	void 음수_값이_있으면_FAILED로_저장한다() {
		aiAnswers(new FoodAnalysisResult(true, List.of(new FoodItem("김치찌개", 400, -5, 12, 22, 20)), "LOW", "?"));

		service.analyze(POST_ID, IMAGE_KEY);

		assertThatFailed("OUT_OF_RANGE");
	}

	@Test
	void 합계가_너무_크면_FAILED로_저장한다() {
		aiAnswers(new FoodAnalysisResult(true, List.of(
				new FoodItem("피자", 3000, 6000, 12, 22, 20),
				new FoodItem("치킨", 3000, 6000, 12, 22, 20)), "LOW", "?"));

		service.analyze(POST_ID, IMAGE_KEY);

		assertThatFailed("OUT_OF_RANGE");
	}

	@Test
	void 음식이라면서_항목이_없으면_FAILED로_저장한다() {
		aiAnswers(new FoodAnalysisResult(true, List.of(), "LOW", "?"));

		service.analyze(POST_ID, IMAGE_KEY);

		assertThatFailed("EMPTY_ITEMS");
	}

	@Test
	void 사진을_찾지_못하면_FAILED로_저장한다() {
		when(storage.load(IMAGE_KEY)).thenReturn(Optional.empty());

		service.analyze(POST_ID, IMAGE_KEY);

		assertThatFailed("IMAGE_NOT_FOUND");
	}

	@Test
	void 예상하지_못한_오류도_PENDING으로_남기지_않고_FAILED로_저장한다() {
		when(analyzer.analyze(jpeg)).thenThrow(new IllegalStateException("boom"));

		service.analyze(POST_ID, IMAGE_KEY);

		assertThatFailed("UNEXPECTED");
	}

	@Test
	void 오늘_횟수가_상한에_닿으면_AI를_부르지_않고_LIMIT_EXCEEDED로_저장한다() {
		when(repository.countByCompletedAtGreaterThanEqual(any())).thenReturn((long) DAILY_LIMIT);

		service.analyze(POST_ID, IMAGE_KEY);

		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.LIMIT_EXCEEDED);
		// AI를 부르지 않았으니 오늘 횟수에 세면 안 된다.
		assertThat(analysis.getCompletedAt()).isNull();
		assertThat(analysis.getAttemptCount()).isZero();
		verify(analyzer, never()).analyze(any());
		verify(repository).save(analysis);
	}

	@Test
	void 상한보다_하나_적으면_분석한다() {
		when(repository.countByCompletedAtGreaterThanEqual(any())).thenReturn(DAILY_LIMIT - 1L);
		aiAnswers(new FoodAnalysisResult(false, List.of(), "HIGH", "음식이 아니에요"));

		service.analyze(POST_ID, IMAGE_KEY);

		verify(analyzer).analyze(jpeg);
	}

	@Test
	void 오늘_횟수는_한국_시간_자정부터_센다() {
		ZoneId korea = ZoneId.of("Asia/Seoul");
		aiAnswers(new FoodAnalysisResult(false, List.of(), "HIGH", "음식이 아니에요"));

		service.analyze(POST_ID, IMAGE_KEY);

		verify(repository).countByCompletedAtGreaterThanEqual(LocalDate.now(korea).atStartOfDay(korea).toInstant());
	}

	private void aiAnswers(FoodAnalysisResult result) {
		when(analyzer.analyze(jpeg)).thenReturn(new FoodAnalyzerResponse(result, "test-model", 1300, 100));
	}

	private void assertThatFailed(String reason) {
		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(analysis.getErrorMessage()).isEqualTo(reason);
		assertThat(analysis.getAttemptCount()).isEqualTo(1);
		verify(repository).save(analysis);
	}
}
