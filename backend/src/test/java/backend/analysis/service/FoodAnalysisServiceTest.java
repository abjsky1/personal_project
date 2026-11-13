package backend.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import backend.analysis.model.dto.FoodAnalysisResult;
import backend.analysis.model.dto.FoodAnalysisResult.FoodItem;
import backend.analysis.model.dto.FoodAnalysisView;
import backend.analysis.model.dto.FoodAnalysisView.FoodRow;
import backend.analysis.model.dto.FoodAnalyzerResponse;
import backend.analysis.model.dto.FoodCandidates;
import backend.analysis.model.dto.FoodCandidates.Candidate;
import backend.analysis.model.dto.FoodCandidates.CandidateItem;
import backend.analysis.model.dto.FoodCandidatesResponse;
import backend.analysis.model.dto.TypedEstimates;
import backend.analysis.model.dto.TypedEstimates.TypedEstimate;
import backend.analysis.model.dto.TypedEstimatesResponse;
import backend.analysis.model.entity.AnalysisStatus;
import backend.analysis.model.entity.ConfirmMode;
import backend.analysis.model.entity.FoodAnalysis;
import backend.analysis.model.repository.FoodAnalysisRepository;
import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;
import backend.image.ImageStorage;
import backend.post.model.entity.Post;
import tools.jackson.databind.json.JsonMapper;

// 분석 결과에 따라 무엇이 저장되는지 확인하는 테스트
// mock(): 정해 둔 대로만 답하는 가짜 객체. 실제 AI, DB, 저장소 없이 판단 로직만 확인한다.
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

	@Test
	void 오늘_횟수에는_직접_적은_음식을_계산한_호출도_센다() {
		when(repository.countByCompletedAtGreaterThanEqual(any())).thenReturn(3L);
		when(repository.countByTypedCalledAtGreaterThanEqual(any())).thenReturn(2L);

		assertThat(service.countToday()).isEqualTo(5);
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

	// ---- 고쳐 쓰는 AI (JM-74): "내가 보고 확정"으로 올린 글 ----

	@Test
	void 내가_보고_확정으로_올린_글은_후보를_받아_첫_후보를_AI의_첫_답으로_저장한다() {
		userMode();
		aiOffers(twoFoods());

		service.analyze(POST_ID, IMAGE_KEY);

		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.DONE);
		// 김치찌개 100g당 55kcal × 400g = 220, 공기밥 100g당 148kcal × 210g = 310.8
		assertThat(analysis.getTotalKcal()).isEqualByComparingTo("530.8");
		assertThat(analysis.getCarbsG()).isEqualByComparingTo("80.0");
		assertThat(analysis.getItems()).contains("김치찌개", "공기밥").doesNotContain("부대찌개");
		// 후보 전체는 따로 보관한다
		assertThat(analysis.getCandidates()).contains("김치찌개", "부대찌개", "순두부찌개", "잡곡밥");
		assertThat(analysis.isConfirmed()).isFalse();
		assertThat(analysis.getOutputTokens()).isEqualTo(300);
		// 음식마다 답 하나만 받는 예전 분석은 부르지 않는다
		verify(analyzer, never()).analyze(any());
		verify(repository).save(analysis);
	}

	@Test
	void AI가_바로_확정으로_올린_글은_후보_분석을_부르지_않는다() {
		aiAnswers(new FoodAnalysisResult(true, List.of(new FoodItem("공기밥", 210, 310, 68, 5, 1)), "HIGH", "1공기"));

		service.analyze(POST_ID, IMAGE_KEY);

		assertThat(analysis.getConfirmMode()).isEqualTo(ConfirmMode.AUTO);
		assertThat(analysis.getCandidates()).isNull();
		verify(analyzer, never()).analyzeCandidates(any());
	}

	@Test
	void 후보는_세_개까지만_두고_이름이_없는_후보는_버린다() {
		userMode();
		aiOffers(new FoodCandidates(true, List.of(new CandidateItem(100, List.of(
				new Candidate(" ", 10, 1, 1, 1),
				new Candidate("사과", 52, 14, 0.3, 0.2),
				new Candidate("배", 51, 13, 0.3, 0.1),
				new Candidate("복숭아", 39, 10, 0.9, 0.3),
				new Candidate("자두", 46, 11, 0.7, 0.3)))), "LOW", "과일"));

		service.analyze(POST_ID, IMAGE_KEY);

		// 이름이 빈 후보를 버린 뒤의 첫 번째가 AI의 첫 답이 된다
		assertThat(analysis.getItems()).contains("사과");
		assertThat(analysis.getCandidates()).contains("사과", "배", "복숭아").doesNotContain("자두");
	}

	@Test
	void 후보가_하나도_없는_음식이_있으면_FAILED로_저장한다() {
		userMode();
		aiOffers(new FoodCandidates(true, List.of(new CandidateItem(100, List.of())), "LOW", "?"));

		service.analyze(POST_ID, IMAGE_KEY);

		assertThatFailed("EMPTY_CANDIDATES");
	}

	@Test
	void 백_그램당_칼로리가_터무니없으면_FAILED로_저장한다() {
		userMode();
		// 100g당이 아니라 사진 속 양 기준으로 잘못 답한 경우 (기름도 100g당 900kcal쯤이다)
		aiOffers(new FoodCandidates(true, List.of(new CandidateItem(300, List.of(
				new Candidate("피자", 2500, 30, 12, 10)))), "LOW", "?"));

		service.analyze(POST_ID, IMAGE_KEY);

		assertThatFailed("OUT_OF_RANGE");
	}

	@Test
	void 후보_분석에서_음식이_아니면_NOT_FOOD로_저장하고_후보는_두지_않는다() {
		userMode();
		aiOffers(new FoodCandidates(false, List.of(), "HIGH", "음식이 아니에요"));

		service.analyze(POST_ID, IMAGE_KEY);

		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.NOT_FOOD);
		assertThat(analysis.getCandidates()).isNull();
	}

	// ---- 확정: 후보에서 고르기 ----
	// 저장소가 가짜라서 확정 값이 분석 기록에 실제로 쓰이지는 않는다. 그래서 "무엇을 저장하라고 넘겼는지"를 확인한다 (savedConfirmation).
	// 실제 DB에 한 번만 쓰이는지는 PostControllerTest가 확인한다.

	@Test
	void 확정하면_고른_후보와_양으로_서버가_다시_계산해서_저장하고_AI는_다시_부르지_않는다() {
		analyzedInUserMode();
		confirmWillBeSaved();

		// 첫 음식은 둘째 후보(부대찌개) 300g으로, 둘째 음식은 첫 후보(공기밥) 그대로 105g으로
		service.confirm(POST_ID, IMAGE_KEY, List.of("1", "0"), List.of("300", "105"), List.of());

		// 부대찌개 100g당 85kcal × 300g = 255, 공기밥 100g당 148kcal × 105g = 155.4
		Saved saved = savedConfirmation(false);
		assertThat(saved.kcal()).isEqualByComparingTo("410.4");
		assertThat(saved.carbs()).isEqualByComparingTo("49.0");
		assertThat(saved.items()).contains("부대찌개", "공기밥", "CANDIDATE").doesNotContain("김치찌개", "TYPED");
		// 후보에서만 골랐으므로 AI를 다시 부르지 않고, 직접 적기 한 번도 쓰지 않는다
		verify(analyzer, never()).estimateTyped(any(), any());
		verify(repository, never()).claimTyped(any(), any());
		// AI의 첫 답은 덮어쓰지 않는다 (확정 값은 다른 칸에 쓴다)
		assertThat(analysis.getTotalKcal()).isEqualByComparingTo("530.8");
		assertThat(analysis.getItems()).contains("김치찌개");
	}

	@Test
	void 이_음식_아님으로_뺀_음식은_합계에서_빠지고_양을_비워_보내도_된다() {
		analyzedInUserMode();
		confirmWillBeSaved();

		service.confirm(POST_ID, IMAGE_KEY, List.of("-1", "0"), List.of("", "210"), List.of());

		Saved saved = savedConfirmation(false);
		assertThat(saved.kcal()).isEqualByComparingTo("310.8");
		// 뺀 음식도 "AI는 김치찌개라고 했는데 뺐다"는 기록으로 남는다
		assertThat(saved.items()).contains("REMOVED", "김치찌개", "공기밥");
	}

	@Test
	void 이미_확정한_글은_다시_확정할_수_없다() {
		analyzedInUserMode();
		ReflectionTestUtils.setField(analysis, "confirmedAt", Instant.now());

		assertThatConfirmFails(ErrorCode.ALREADY_CONFIRMED, List.of("1", "0"), List.of("300", "105"), List.of());

		verifyNothingConfirmed();
	}

	@Test
	void 같은_확정이_동시에_와서_다른_요청이_먼저_확정했으면_받지_않는다() {
		analyzedInUserMode();
		// confirmOnce가 0을 돌려준다 = 조건(아직 확정하지 않음)이 맞지 않아 아무것도 바뀌지 않았다. 가짜 저장소의 기본 답이 0이다.

		assertThatConfirmFails(ErrorCode.ALREADY_CONFIRMED, List.of("1", "0"), List.of("300", "105"), List.of());
	}

	@Test
	void 값이_맞지_않으면_받지_않고_AI도_부르지_않는다() {
		analyzedInUserMode();

		assertThatInvalid(List.of("-1", "-1"), List.of("400", "210"), List.of());
		assertThatInvalid(List.of("0", "0"), List.of("0", "210"), List.of());
		assertThatInvalid(List.of("0", "0"), List.of("5001", "210"), List.of());
		assertThatInvalid(List.of("0", "0"), List.of("많이", "210"), List.of());
		assertThatInvalid(List.of("0", "0"), List.of("", "210"), List.of());
		assertThatInvalid(List.of("3", "0"), List.of("400", "210"), List.of());
		assertThatInvalid(List.of("첫째", "0"), List.of("400", "210"), List.of());
		assertThatInvalid(List.of("1.5", "0"), List.of("400", "210"), List.of());
		// 음식 수와 보낸 칸 수가 다른 경우
		assertThatInvalid(List.of("0"), List.of("400"), List.of());
		assertThatInvalid(null, null, null);
		// 직접 적기: 이름이 비었거나, 빈칸뿐이거나, 30자를 넘거나, 이름 칸이 아예 오지 않은 경우
		assertThatInvalid(List.of("typed", "0"), List.of("300", "210"), List.of("", ""));
		assertThatInvalid(List.of("typed", "0"), List.of("300", "210"), List.of("   ", ""));
		assertThatInvalid(List.of("typed", "0"), List.of("300", "210"), List.of("가".repeat(31), ""));
		assertThatInvalid(List.of("typed", "0"), List.of("300", "210"), List.of());
		// 직접 적은 줄도 양은 맞아야 한다
		assertThatInvalid(List.of("typed", "0"), List.of("0", "210"), List.of("마라샹궈", ""));

		// 잘못 적은 값 때문에 "직접 적기 한 번"을 쓰거나 AI를 부르는 일은 없다
		verify(repository, never()).claimTyped(any(), any());
		verify(analyzer, never()).estimateTyped(any(), any());
		verifyNothingConfirmed();
	}

	@Test
	void 합계가_터무니없이_크면_받지_않는다() {
		analyzedInUserMode();

		// 부대찌개 100g당 85kcal × 5000g = 4,250, 볶음밥 100g당 185kcal × 5000g = 9,250 → 13,500kcal
		assertThatConfirmFails(ErrorCode.CONFIRMATION_TOO_LARGE, List.of("1", "2"), List.of("5000", "5000"), List.of());

		verifyNothingConfirmed();
	}

	@Test
	void 후보가_없는_글은_확정할_수_없다() {
		// "AI가 바로 확정"으로 올려서 분석이 끝난 글
		aiAnswers(new FoodAnalysisResult(true, List.of(new FoodItem("공기밥", 210, 310, 68, 5, 1)), "HIGH", "1공기"));
		service.analyze(POST_ID, IMAGE_KEY);

		assertThatConfirmFails(ErrorCode.NOT_CONFIRMABLE, List.of("0"), List.of("210"), List.of());
	}

	// ---- 확정: 후보에 없어서 이름을 직접 적기 ----

	@Test
	void 직접_적은_음식은_AI가_사진과_이름으로_계산한_값으로_확정한다() {
		analyzedInUserMode();
		confirmWillBeSaved();
		typedCanBeClaimed();
		aiEstimates(List.of("마라샹궈"), new TypedEstimate(true, true, 150, 8, 9, 9));

		// 첫 음식은 후보에 없어서 "마라샹궈" 300g으로 적고, 둘째 음식은 AI의 첫 답(공기밥 210g) 그대로
		service.confirm(POST_ID, IMAGE_KEY, List.of("typed", "0"), List.of("300", "210"), List.of("마라샹궈", ""));

		// 마라샹궈 100g당 150kcal × 300g = 450, 공기밥 310.8
		Saved saved = savedConfirmation(false);
		assertThat(saved.kcal()).isEqualByComparingTo("760.8");
		// 탄수화물: 마라샹궈 100g당 8g × 300g = 24, 공기밥 100g당 32.4g × 210g = 68.0
		assertThat(saved.carbs()).isEqualByComparingTo("92.0");
		assertThat(saved.items()).contains("TYPED", "마라샹궈", "\"matchesPhoto\":true", "CANDIDATE", "공기밥");
		// AI에게는 사진과, 직접 적은 이름만 보낸다 (후보에서 고른 음식은 보내지 않는다)
		verify(analyzer).estimateTyped(jpeg, List.of("마라샹궈"));
		// 부르기 전에 "직접 적기 한 번"을 차지하고, 답을 받으면 토큰 수를 적는다
		verify(repository).claimTyped(eq(POST_ID), any());
		verify(repository).recordTypedCall(eq(POST_ID), eq(1500), eq(40), anyLong());
		verify(repository, never()).releaseTyped(any());
	}

	@Test
	void 직접_적은_이름은_줄바꿈과_이어진_빈칸을_없애고_AI에게_보낸다() {
		analyzedInUserMode();
		confirmWillBeSaved();
		typedCanBeClaimed();
		aiEstimates(List.of("마라 샹궈", "현미밥"), new TypedEstimate(true, true, 150, 8, 9, 9), new TypedEstimate(true, true, 150, 32, 3, 1));

		service.confirm(POST_ID, IMAGE_KEY, List.of("typed", "typed"), List.of("300", "200"), List.of("  마라\t샹궈\n", "현미밥"));

		// 두 음식을 적어도 AI는 한 번만 부른다 (이름 둘을 한 번에 물어본다)
		verify(analyzer).estimateTyped(jpeg, List.of("마라 샹궈", "현미밥"));
		// 450 + 현미밥 100g당 150kcal × 200g = 300
		Saved saved = savedConfirmation(false);
		assertThat(saved.kcal()).isEqualByComparingTo("750.0");
		assertThat(saved.items()).contains("마라 샹궈", "현미밥");
	}

	@Test
	void 음식이지만_사진과_맞지_않는다고_본_것은_확정하고_표시를_남긴다() {
		analyzedInUserMode();
		confirmWillBeSaved();
		typedCanBeClaimed();
		aiEstimates(List.of("초코케이크"), new TypedEstimate(true, false, 370, 50, 5, 17));

		service.confirm(POST_ID, IMAGE_KEY, List.of("typed", "0"), List.of("100", "210"), List.of("초코케이크", ""));

		// 글쓴이가 사진보다 잘 안다고 보고 받는다. 대신 "사진과 맞지 않음" 표시(typedMismatch)를 참으로 저장한다
		Saved saved = savedConfirmation(true);
		assertThat(saved.kcal()).isEqualByComparingTo("680.8");
		assertThat(saved.items()).contains("초코케이크", "\"matchesPhoto\":false");
	}

	@Test
	void 직접_적은_글자가_음식이_아니면_확정하지_않고_직접_적기_한_번은_쓴_것으로_남는다() {
		analyzedInUserMode();
		typedCanBeClaimed();
		aiEstimates(List.of("마라샹궈", "자동차"), new TypedEstimate(true, true, 150, 8, 9, 9), new TypedEstimate(false, false, 0, 0, 0, 0));

		// 둘 중 하나만 음식이 아니어도 확정 전체를 받지 않는다
		assertThatConfirmFails(ErrorCode.TYPED_NOT_FOOD, List.of("typed", "typed"), List.of("300", "210"), List.of("마라샹궈", "자동차"));

		verifyNothingConfirmed();
		// AI가 답을 줬으므로 한 번을 돌려주지 않는다 (글마다 AI의 답은 두 번까지)
		verify(repository).claimTyped(eq(POST_ID), any());
		verify(repository).recordTypedCall(eq(POST_ID), eq(1500), eq(40), anyLong());
		verify(repository, never()).releaseTyped(any());
	}

	@Test
	void 직접_적기를_이미_쓴_글은_AI를_다시_부르지_않는다() {
		analyzedInUserMode();
		ReflectionTestUtils.setField(analysis, "typedCalledAt", Instant.now());

		assertThatConfirmFails(ErrorCode.TYPED_ALREADY_USED, List.of("typed", "0"), List.of("300", "210"), List.of("마라샹궈", ""));

		verify(repository, never()).claimTyped(any(), any());
		verify(analyzer, never()).estimateTyped(any(), any());
		verifyNothingConfirmed();
	}

	@Test
	void 직접_적기를_이미_쓴_글도_후보에서_골라_확정할_수_있다() {
		analyzedInUserMode();
		confirmWillBeSaved();
		ReflectionTestUtils.setField(analysis, "typedCalledAt", Instant.now());

		service.confirm(POST_ID, IMAGE_KEY, List.of("1", "0"), List.of("300", "105"), List.of());

		assertThat(savedConfirmation(false).kcal()).isEqualByComparingTo("410.4");
	}

	@Test
	void 같은_요청이_동시에_와서_직접_적기_한_번을_먼저_빼앗기면_AI를_부르지_않는다() {
		analyzedInUserMode();
		// claimTyped가 0을 돌려준다 = 그사이에 다른 요청이 먼저 차지했다. 가짜 저장소의 기본 답이 0이다.

		assertThatConfirmFails(ErrorCode.TYPED_ALREADY_USED, List.of("typed", "0"), List.of("300", "210"), List.of("마라샹궈", ""));

		verify(analyzer, never()).estimateTyped(any(), any());
		verifyNothingConfirmed();
	}

	@Test
	void AI에게서_답을_받지_못하면_직접_적기_한_번을_돌려준다() {
		analyzedInUserMode();
		typedCanBeClaimed();
		// AI 서버가 혼잡해서 오류로 답한 경우. 분석이 이루어지지 않았다.
		when(analyzer.estimateTyped(any(), any())).thenThrow(new FoodAnalysisException("HTTP_503"));

		assertThatConfirmFails(ErrorCode.TYPED_AI_UNAVAILABLE, List.of("typed", "0"), List.of("300", "210"), List.of("마라샹궈", ""));

		verify(repository).releaseTyped(POST_ID);
		verifyNothingConfirmed();
	}

	@Test
	void AI가_답은_했지만_읽을_수_없으면_직접_적기_한_번은_쓴_것으로_남는다() {
		analyzedInUserMode();
		typedCanBeClaimed();
		when(analyzer.estimateTyped(any(), any())).thenThrow(new FoodAnalysisException("BAD_RESPONSE"));

		assertThatConfirmFails(ErrorCode.TYPED_AI_FAILED, List.of("typed", "0"), List.of("300", "210"), List.of("마라샹궈", ""));

		verify(repository, never()).releaseTyped(any());
		verifyNothingConfirmed();
	}

	@Test
	void AI의_답이_개수가_다르거나_값이_터무니없으면_받지_않는다() {
		analyzedInUserMode();
		typedCanBeClaimed();
		// 이름 하나를 물었는데 답이 둘
		aiEstimates(List.of("마라샹궈"), new TypedEstimate(true, true, 150, 8, 9, 9), new TypedEstimate(true, true, 150, 8, 9, 9));
		// 100g당 2,500kcal (기름도 900쯤이다)
		aiEstimates(List.of("꿔바로우"), new TypedEstimate(true, true, 2500, 30, 10, 12));

		assertThatConfirmFails(ErrorCode.TYPED_AI_FAILED, List.of("typed", "0"), List.of("300", "210"), List.of("마라샹궈", ""));
		assertThatConfirmFails(ErrorCode.TYPED_AI_FAILED, List.of("typed", "0"), List.of("300", "210"), List.of("꿔바로우", ""));

		verify(repository, never()).releaseTyped(any());
		verifyNothingConfirmed();
	}

	@Test
	void 오늘_횟수가_다_찼으면_직접_적은_음식을_AI에게_묻지_않고_한_번도_쓰지_않는다() {
		analyzedInUserMode();
		when(repository.countByCompletedAtGreaterThanEqual(any())).thenReturn((long) DAILY_LIMIT);

		assertThatConfirmFails(ErrorCode.TYPED_AI_UNAVAILABLE, List.of("typed", "0"), List.of("300", "210"), List.of("마라샹궈", ""));

		verify(repository, never()).claimTyped(any(), any());
		verify(analyzer, never()).estimateTyped(any(), any());
	}

	@Test
	void 후보에서_고른_것만으로_합계가_너무_크면_AI를_부르기_전에_돌려보낸다() {
		userMode();
		aiOffers(new FoodCandidates(true, List.of(
				new CandidateItem(100, List.of(new Candidate("버터", 750, 0, 1, 82))),
				new CandidateItem(60, List.of(new Candidate("식빵", 270, 50, 9, 4)))), "LOW", "빵과 버터"));
		service.analyze(POST_ID, IMAGE_KEY);

		// 버터 100g당 750kcal × 2000g = 15,000kcal: 직접 적은 둘째 음식을 계산해 보지 않아도 이미 너무 크다
		assertThatConfirmFails(ErrorCode.CONFIRMATION_TOO_LARGE, List.of("0", "typed"), List.of("2000", "60"), List.of("", "크루아상"));

		verify(repository, never()).claimTyped(any(), any());
		verify(analyzer, never()).estimateTyped(any(), any());
	}

	// ---- 상세 화면으로 넘기는 값 ----

	@Test
	void 상세_화면에는_확정_전에는_AI의_첫_답과_확정_칸을_넘긴다() {
		analyzedInUserMode();

		FoodAnalysisView view = service.getView(POST_ID);

		assertThat(view.userMode()).isTrue();
		assertThat(view.confirmed()).isFalse();
		assertThat(view.aiTotals().kcal()).isEqualByComparingTo("530.8");
		assertThat(view.confirmedTotals()).isNull();
		// 음식 표: AI의 줄만
		assertThat(view.rows()).extracting(row -> row.ai().name()).containsExactly("김치찌개", "공기밥");
		assertThat(view.rows()).extracting(FoodRow::change).containsOnly(FoodRow.NONE);
		// 확정 칸: 음식 둘, 후보 셋씩 (고르는 칸의 값은 후보의 순서를 글자로 바꾼 것), 양은 AI가 추정한 값
		assertThat(view.confirmRows()).hasSize(2);
		assertThat(view.confirmRows().get(0).options()).extracting(FoodAnalysisView.Option::name)
				.containsExactly("김치찌개", "부대찌개", "순두부찌개");
		assertThat(view.confirmRows().get(0).options()).extracting(FoodAnalysisView.Option::value)
				.containsExactly("0", "1", "2");
		assertThat(view.confirmRows().get(0).amountG()).isEqualTo(400);
		assertThat(view.typedAllowed()).isTrue();
	}

	@Test
	void 직접_적기를_이미_쓴_글의_상세에는_직접_적기를_열어_주지_않는다() {
		analyzedInUserMode();
		ReflectionTestUtils.setField(analysis, "typedCalledAt", Instant.now());

		FoodAnalysisView view = service.getView(POST_ID);

		assertThat(view.typedAllowed()).isFalse();
		// 후보에서 골라 확정하는 것은 그대로 할 수 있다
		assertThat(view.confirmRows()).hasSize(2);
	}

	@Test
	void 확정한_글의_상세에는_AI의_답과_확정한_값을_함께_넘기고_확정_칸은_없다() {
		analyzedInUserMode();
		confirmWillBeSaved();
		typedCanBeClaimed();
		aiEstimates(List.of("마라샹궈"), new TypedEstimate(true, true, 150, 8, 9, 9));
		// 첫 음식은 직접 적고, 둘째 음식은 뺀다
		service.confirm(POST_ID, IMAGE_KEY, List.of("typed", "-1"), List.of("300", "210"), List.of("마라샹궈", ""));
		// 가짜 저장소는 값을 쓰지 않으므로, 저장하라고 넘긴 값을 분석 기록에 직접 넣어 "확정한 글"을 만든다
		markConfirmed(savedConfirmation(false));

		FoodAnalysisView view = service.getView(POST_ID);

		assertThat(view.confirmed()).isTrue();
		// 합계는 둘 다: AI의 답은 확정해도 지워지지 않는다
		assertThat(view.aiTotals().kcal()).isEqualByComparingTo("530.8");
		assertThat(view.confirmedTotals().kcal()).isEqualByComparingTo("450.0");
		assertThat(view.confirmedTotals().carbsG()).isEqualByComparingTo("24.0");
		// 음식 표의 줄도 AI가 본 음식 그대로 둘. 첫 음식은 다른 것으로 바꿨고, 둘째 음식은 뺐다
		assertThat(view.rows()).extracting(row -> row.ai().name()).containsExactly("김치찌개", "공기밥");
		assertThat(view.rows()).extracting(FoodRow::change).containsExactly(FoodRow.CHANGED, FoodRow.REMOVED);
		assertThat(view.rows().get(0).confirmed().name()).isEqualTo("마라샹궈");
		assertThat(view.rows().get(0).confirmed().kcal()).isEqualTo(450);
		assertThat(view.rows().get(1).confirmed()).isNull();
		// 확정은 한 번뿐이라 다시 고칠 칸을 넘기지 않는다
		assertThat(view.confirmRows()).isEmpty();
	}

	@Test
	void AI의_답을_그대로_둔_음식은_같음으로_양만_고친_음식은_바꿈으로_넘긴다() {
		analyzedInUserMode();
		confirmWillBeSaved();
		// 첫 음식은 AI의 첫 답(김치찌개 400g) 그대로, 둘째 음식은 같은 공기밥이지만 양을 절반으로
		service.confirm(POST_ID, IMAGE_KEY, List.of("0", "0"), List.of("400", "105"), List.of());
		markConfirmed(savedConfirmation(false));

		FoodAnalysisView view = service.getView(POST_ID);

		assertThat(view.rows()).extracting(FoodRow::change).containsExactly(FoodRow.SAME, FoodRow.CHANGED);
		assertThat(view.rows().get(1).ai().amountG()).isEqualTo(210);
		assertThat(view.rows().get(1).confirmed().amountG()).isEqualTo(105);
		// 김치찌개 220 + 공기밥 100g당 148kcal × 105g = 155.4
		assertThat(view.confirmedTotals().kcal()).isEqualByComparingTo("375.4");
	}

	@Test
	void AI가_소수로_낸_양을_손대지_않고_확정하면_같음으로_본다() {
		userMode();
		// 확정 칸은 AI의 양을 정수로 보여 준다: 152.4g은 152로 보이고, 손대지 않고 확정하면 152가 온다
		aiOffers(new FoodCandidates(true, List.of(new CandidateItem(152.4, List.of(
				new Candidate("사과", 52, 14, 0.3, 0.2),
				new Candidate("배", 51, 13, 0.3, 0.1)))), "HIGH", "사과 한 개"));
		service.analyze(POST_ID, IMAGE_KEY);
		confirmWillBeSaved();
		service.confirm(POST_ID, IMAGE_KEY, List.of("0"), List.of("152"), List.of());
		markConfirmed(savedConfirmation(false));

		FoodAnalysisView view = service.getView(POST_ID);

		assertThat(view.rows()).extracting(FoodRow::change).containsExactly(FoodRow.SAME);
	}

	@Test
	void AI가_바로_확정으로_올린_글의_상세에는_확정_칸이_없다() {
		aiAnswers(new FoodAnalysisResult(true, List.of(new FoodItem("공기밥", 210, 310, 68, 5, 1)), "HIGH", "1공기"));
		service.analyze(POST_ID, IMAGE_KEY);

		FoodAnalysisView view = service.getView(POST_ID);

		assertThat(view.userMode()).isFalse();
		assertThat(view.confirmRows()).isEmpty();
		assertThat(view.aiTotals().kcal()).isEqualByComparingTo("310");
		assertThat(view.confirmedTotals()).isNull();
		assertThat(view.rows()).extracting(row -> row.ai().name()).containsExactly("공기밥");
	}

	// 이 테스트의 분석 기록을 "내가 보고 확정"으로 올린 글의 것으로 바꾼다
	private void userMode() {
		analysis = new FoodAnalysis(new Post("테스터", "hash", null, IMAGE_KEY, "thumb.jpg", null), ConfirmMode.USER);
		when(repository.findById(POST_ID)).thenReturn(Optional.of(analysis));
	}

	private void aiOffers(FoodCandidates candidates) {
		when(analyzer.analyzeCandidates(jpeg)).thenReturn(new FoodCandidatesResponse(candidates, "test-model", 1400, 300));
	}

	// 음식 둘(찌개 400g, 밥 210g)에 후보 셋씩. 100g당 칼로리, 탄수화물, 단백질, 지방
	private FoodCandidates twoFoods() {
		return new FoodCandidates(true, List.of(
				new CandidateItem(400, List.of(
						new Candidate("김치찌개", 55, 3, 4.5, 2.5),
						new Candidate("부대찌개", 85, 5, 5, 5),
						new Candidate("순두부찌개", 48, 2, 4.3, 2.5))),
				new CandidateItem(210, List.of(
						new Candidate("공기밥", 148, 32.4, 2.7, 0.3),
						new Candidate("잡곡밥", 150, 31.8, 3.5, 0.6),
						new Candidate("볶음밥", 185, 27, 4.2, 6.4)))),
				"MEDIUM", "1인분 기준");
	}

	// "내가 보고 확정"으로 올려서 분석까지 끝난 상태
	private void analyzedInUserMode() {
		userMode();
		aiOffers(twoFoods());
		service.analyze(POST_ID, IMAGE_KEY);
	}

	// 확정 값을 쓰는 UPDATE가 한 행을 바꿨다고 답하게 한다 (= 아직 확정하지 않은 글이었다)
	private void confirmWillBeSaved() {
		when(repository.confirmOnce(any(), any(), any(), any(), any(), any(), anyBoolean(), any())).thenReturn(1);
	}

	// "직접 적기 한 번"을 차지하는 UPDATE가 한 행을 바꿨다고 답하게 한다 (= 아직 쓰지 않은 글이었다)
	private void typedCanBeClaimed() {
		when(repository.claimTyped(eq(POST_ID), any())).thenReturn(1);
	}

	// names를 물으면 AI가 estimates로 답하게 한다
	private void aiEstimates(List<String> names, TypedEstimate... estimates) {
		when(analyzer.estimateTyped(jpeg, names))
				.thenReturn(new TypedEstimatesResponse(new TypedEstimates(List.of(estimates)), "test-model", 1500, 40));
	}

	// 저장하라고 넘긴 확정 값
	private record Saved(String items, BigDecimal kcal, BigDecimal carbs, BigDecimal protein, BigDecimal fat) {
	}

	// confirmOnce에 넘긴 값을 꺼낸다. 딱 한 번 불렸는지, "사진과 맞지 않음" 표시가 mismatch와 같은지도 함께 확인된다.
	// ArgumentCaptor: 가짜 객체의 메서드가 불릴 때 넘어온 값을 붙잡아 두는 도구
	private Saved savedConfirmation(boolean mismatch) {
		ArgumentCaptor<String> items = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<BigDecimal> kcal = ArgumentCaptor.forClass(BigDecimal.class);
		ArgumentCaptor<BigDecimal> carbs = ArgumentCaptor.forClass(BigDecimal.class);
		ArgumentCaptor<BigDecimal> protein = ArgumentCaptor.forClass(BigDecimal.class);
		ArgumentCaptor<BigDecimal> fat = ArgumentCaptor.forClass(BigDecimal.class);
		verify(repository).confirmOnce(eq(POST_ID), items.capture(), kcal.capture(), carbs.capture(), protein.capture(),
				fat.capture(), eq(mismatch), any());
		return new Saved(items.getValue(), kcal.getValue(), carbs.getValue(), protein.getValue(), fat.getValue());
	}

	// 분석 기록을 "확정한 글"로 만든다. 확정 값은 실제로는 DB의 UPDATE가 쓰기 때문에 바꾸는 메서드가 없어서, 테스트에서만 칸에 직접 넣는다.
	private void markConfirmed(Saved saved) {
		ReflectionTestUtils.setField(analysis, "confirmedItems", saved.items());
		ReflectionTestUtils.setField(analysis, "confirmedTotalKcal", saved.kcal());
		ReflectionTestUtils.setField(analysis, "confirmedCarbsG", saved.carbs());
		ReflectionTestUtils.setField(analysis, "confirmedProteinG", saved.protein());
		ReflectionTestUtils.setField(analysis, "confirmedFatG", saved.fat());
		ReflectionTestUtils.setField(analysis, "confirmedAt", Instant.now());
	}

	private void verifyNothingConfirmed() {
		verify(repository, never()).confirmOnce(any(), any(), any(), any(), any(), any(), anyBoolean(), any());
	}

	private void assertThatInvalid(List<String> picks, List<String> amounts, List<String> names) {
		assertThatConfirmFails(ErrorCode.INVALID_CONFIRMATION, picks, amounts, names);
	}

	private void assertThatConfirmFails(ErrorCode expected, List<String> picks, List<String> amounts, List<String> names) {
		assertThatThrownBy(() -> service.confirm(POST_ID, IMAGE_KEY, picks, amounts, names))
				.isInstanceOf(BusinessException.class)
				.satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(expected));
	}
}
