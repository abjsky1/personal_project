package backend.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import backend.analysis.model.dto.FoodAnalysisView;
import backend.analysis.model.dto.FoodAnalyzerResponse;
import backend.analysis.model.dto.FoodCandidates;
import backend.analysis.model.dto.FoodCandidates.Candidate;
import backend.analysis.model.dto.FoodCandidates.CandidateItem;
import backend.analysis.model.dto.FoodCandidatesResponse;
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

	@Test
	void 확정하면_고른_후보와_양으로_서버가_다시_계산하고_AI의_첫_답은_그대로_둔다() {
		analyzedInUserMode();

		// 첫 음식은 둘째 후보(부대찌개) 300g으로, 둘째 음식은 첫 후보(공기밥) 그대로 105g으로
		service.confirm(POST_ID, List.of("1", "0"), List.of("300", "105"));

		// 부대찌개 100g당 85kcal × 300g = 255, 공기밥 100g당 148kcal × 105g = 155.4
		assertThat(analysis.isConfirmed()).isTrue();
		assertThat(analysis.getConfirmedTotalKcal()).isEqualByComparingTo("410.4");
		assertThat(analysis.getConfirmedCarbsG()).isEqualByComparingTo("49.0");
		assertThat(analysis.getConfirmedItems()).contains("부대찌개", "공기밥").doesNotContain("김치찌개");
		assertThat(analysis.getConfirmCount()).isEqualTo(1);
		assertThat(analysis.getShownTotalKcal()).isEqualByComparingTo("410.4");
		// AI의 첫 답은 덮어쓰지 않는다
		assertThat(analysis.getTotalKcal()).isEqualByComparingTo("530.8");
		assertThat(analysis.getItems()).contains("김치찌개");
	}

	@Test
	void 이_음식_아님으로_뺀_음식은_합계에서_빠지고_양을_비워_보내도_된다() {
		analyzedInUserMode();

		service.confirm(POST_ID, List.of("-1", "0"), List.of("", "210"));

		assertThat(analysis.getConfirmedTotalKcal()).isEqualByComparingTo("310.8");
		FoodAnalysisView view = service.getView(POST_ID);
		assertThat(view.items()).extracting(FoodItem::name).containsExactly("공기밥");
	}

	@Test
	void 다시_확정하면_확정한_값을_새로_쓰고_횟수를_올린다() {
		analyzedInUserMode();
		service.confirm(POST_ID, List.of("1", "0"), List.of("300", "105"));

		service.confirm(POST_ID, List.of("0", "2"), List.of("400", "210"));

		// 김치찌개 220 + 볶음밥 100g당 185kcal × 210g = 388.5
		assertThat(analysis.getConfirmedTotalKcal()).isEqualByComparingTo("608.5");
		assertThat(analysis.getConfirmCount()).isEqualTo(2);
	}

	@Test
	void 음식을_전부_빼거나_양이_범위_밖이거나_없는_후보를_고르면_받지_않는다() {
		analyzedInUserMode();

		assertThatInvalid(List.of("-1", "-1"), List.of("400", "210"));
		assertThatInvalid(List.of("0", "0"), List.of("0", "210"));
		assertThatInvalid(List.of("0", "0"), List.of("5001", "210"));
		assertThatInvalid(List.of("0", "0"), List.of("많이", "210"));
		assertThatInvalid(List.of("0", "0"), List.of("", "210"));
		assertThatInvalid(List.of("3", "0"), List.of("400", "210"));
		assertThatInvalid(List.of("첫째", "0"), List.of("400", "210"));
		// 음식 수와 보낸 칸 수가 다른 경우
		assertThatInvalid(List.of("0"), List.of("400"));
		assertThatInvalid(null, null);

		assertThat(analysis.isConfirmed()).isFalse();
	}

	@Test
	void 후보가_없는_글은_확정할_수_없다() {
		// "AI가 바로 확정"으로 올려서 분석이 끝난 글
		aiAnswers(new FoodAnalysisResult(true, List.of(new FoodItem("공기밥", 210, 310, 68, 5, 1)), "HIGH", "1공기"));
		service.analyze(POST_ID, IMAGE_KEY);

		assertThatThrownBy(() -> service.confirm(POST_ID, List.of("0"), List.of("210")))
				.isInstanceOf(BusinessException.class)
				.satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_CONFIRMABLE));
	}

	@Test
	void 상세_화면에는_확정_전에는_AI의_첫_답을_확정한_뒤에는_확정한_값을_넘긴다() {
		analyzedInUserMode();

		FoodAnalysisView before = service.getView(POST_ID);
		assertThat(before.userMode()).isTrue();
		assertThat(before.confirmed()).isFalse();
		assertThat(before.totalKcal()).isEqualByComparingTo("530.8");
		// 확정 칸: 음식 둘, 후보 셋씩, AI의 첫 답이 골라져 있고 양은 AI가 추정한 값
		assertThat(before.confirmRows()).hasSize(2);
		assertThat(before.confirmRows().get(0).options()).extracting(FoodAnalysisView.Option::name)
				.containsExactly("김치찌개", "부대찌개", "순두부찌개");
		assertThat(before.confirmRows().get(0).pick()).isZero();
		assertThat(before.confirmRows().get(0).amountG()).isEqualTo(400);

		service.confirm(POST_ID, List.of("1", "-1"), List.of("300", "210"));

		FoodAnalysisView after = service.getView(POST_ID);
		assertThat(after.confirmed()).isTrue();
		assertThat(after.totalKcal()).isEqualByComparingTo("255.0");
		assertThat(after.aiTotalKcal()).isEqualByComparingTo("530.8");
		assertThat(after.items()).extracting(FoodItem::name).containsExactly("부대찌개");
		// 다시 고칠 때는 지난번에 고른 값에서 이어서: 첫 음식은 둘째 후보 300g, 뺀 음식은 "뺌"과 AI가 추정한 양
		assertThat(after.confirmRows().get(0).pick()).isEqualTo(1);
		assertThat(after.confirmRows().get(0).amountG()).isEqualTo(300);
		assertThat(after.confirmRows().get(1).pick()).isEqualTo(-1);
		assertThat(after.confirmRows().get(1).amountG()).isEqualTo(210);
	}

	@Test
	void AI가_바로_확정으로_올린_글의_상세에는_확정_칸이_없다() {
		aiAnswers(new FoodAnalysisResult(true, List.of(new FoodItem("공기밥", 210, 310, 68, 5, 1)), "HIGH", "1공기"));
		service.analyze(POST_ID, IMAGE_KEY);

		FoodAnalysisView view = service.getView(POST_ID);

		assertThat(view.userMode()).isFalse();
		assertThat(view.confirmRows()).isEmpty();
		assertThat(view.totalKcal()).isEqualByComparingTo("310");
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

	private void assertThatInvalid(List<String> picks, List<String> amounts) {
		assertThatThrownBy(() -> service.confirm(POST_ID, picks, amounts))
				.isInstanceOf(BusinessException.class)
				.satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_CONFIRMATION));
	}
}
