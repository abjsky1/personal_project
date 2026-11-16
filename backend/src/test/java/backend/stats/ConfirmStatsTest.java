package backend.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import backend.analysis.model.dto.ConfirmedItem;
import backend.analysis.model.dto.FoodAnalysisResult.FoodItem;
import backend.stats.model.dto.AdminStatsResponse.ConfirmStats;
import backend.stats.model.dto.AdminStatsResponse.FoodChoices;
import backend.stats.model.dto.AdminStatsResponse.FoodChoices.Kind;
import backend.stats.model.dto.ConfirmCounts;
import backend.stats.model.dto.ConfirmedPost;

// 고쳐 쓰는 AI의 확정 통계 계산 (JM-75)을 DB 없이 확인하는 테스트
// 음식마다 어느 쪽인지 가르는 기준은 글 화면의 "AI와 같음"(ConfirmedItem.sameAs)과 같다
class ConfirmStatsTest {

	private static final LocalDateTime SINCE = LocalDateTime.of(2026, 11, 13, 10, 5);
	private static final ConfirmCounts NO_COUNTS = new ConfirmCounts(0, 0, 0, 0, 0, 0, 0, 0, 0);

	@Test
	void 음식마다_어느_쪽인지_한_곳에만_가른다() {
		FoodItem ai = food(152.4);

		// AI가 152.4g이라고 한 것을 손대지 않고 확정하면 정수 152g으로 온다 → AI와 같음
		assertThat(FoodChoices.kindOf(ai, candidate(0, 152))).isEqualTo(Kind.SAME);
		// 딱 .5는 확정 칸(fmt:formatNumber)처럼 짝수 쪽으로: 152.5g은 칸에 152, 153.5g은 154로 채워진다. 손대지 않으면 AI와 같음
		assertThat(FoodChoices.kindOf(food(152.5), candidate(0, 152))).isEqualTo(Kind.SAME);
		assertThat(FoodChoices.kindOf(food(153.5), candidate(0, 154))).isEqualTo(Kind.SAME);
		// 채워진 152를 153으로 고치면 양만 고침
		assertThat(FoodChoices.kindOf(food(152.5), candidate(0, 153))).isEqualTo(Kind.AMOUNT_CHANGED);
		// 확정한 쪽에 소수가 와도 같은 반올림으로 견준다: 152.4g → 152
		assertThat(FoodChoices.kindOf(food(152), candidate(0, 152.4))).isEqualTo(Kind.SAME);
		assertThat(FoodChoices.kindOf(ai, candidate(0, 200))).isEqualTo(Kind.AMOUNT_CHANGED);
		assertThat(FoodChoices.kindOf(ai, candidate(2, 152))).isEqualTo(Kind.OTHER_CANDIDATE);
		assertThat(FoodChoices.kindOf(ai, typed(152))).isEqualTo(Kind.TYPED);
		assertThat(FoodChoices.kindOf(ai, ConfirmedItem.removed("밥"))).isEqualTo(Kind.REMOVED);
		// 고쳐 쓰는 AI 첫 안 때의 기록처럼 choice가 없거나, 음식 수가 맞지 않아 견줄 AI 음식이 없으면 가를 수 없다
		assertThat(FoodChoices.kindOf(ai, new ConfirmedItem(null, null, "밥", 152, 220, 0, 0, 0, null))).isEqualTo(Kind.UNKNOWN);
		assertThat(FoodChoices.kindOf(null, candidate(0, 152))).isEqualTo(Kind.UNKNOWN);
	}

	@Test
	void 모든_음식이_AI와_같아야_첫_답_그대로_확정한_글이고_뺀_음식은_비율에서_뺀다() {
		ConfirmedPost untouched = post(List.of(food(100), food(50)), List.of(candidate(0, 100), candidate(0, 50)), 300, 300);
		ConfirmedPost oneTyped = post(List.of(food(100), food(50)), List.of(candidate(0, 100), typed(50)), 300, 400);
		ConfirmedPost removed = post(List.of(food(100)), List.of(ConfirmedItem.removed("밥")), 200, 0);

		ConfirmStats stats = ConfirmStats.of(SINCE, new ConfirmCounts(6, 4, 8, 4, 3, 1, 0, 120, 30),
				List.of(untouched, oneTyped, removed), 0);

		assertThat(stats.unchangedPosts()).isEqualTo(1);
		assertThat(stats.unchangedRate()).isEqualTo(33.3);
		assertThat(stats.foods()).isEqualTo(new FoodChoices(3, 0, 0, 1, 1, 0));
		// 남긴 음식 4개(같음 3, 직접 적음 1) 중 AI의 첫 이름이 맞은 것 3개, 후보 안에서 고른 것 3개. 뺀 음식은 분모에 넣지 않는다
		assertThat(stats.firstNameRate()).isEqualTo(75.0);
		assertThat(stats.withinCandidatesRate()).isEqualTo(75.0);
		// 건수에서 나오는 비율
		assertThat(stats.userModeRate()).isEqualTo(40.0);
		assertThat(stats.confirmRate()).isEqualTo(75.0);
		assertThat(stats.unconfirmed()).isEqualTo(1);
		assertThat(stats.confirmedShare()).isEqualTo(37.5);
		assertThat(stats.since()).isEqualTo(SINCE);
	}

	// 음식마다 한 것의 순서와 두 비율이 섞이지 않게, 여섯 칸을 모두 다른 수로 센다
	@Test
	void 음식마다_한_것은_여섯_칸에_따로_세고_두_비율은_분자가_다르다() {
		List<FoodItem> ai = new ArrayList<>();
		List<ConfirmedItem> confirmed = new ArrayList<>();
		add(ai, confirmed, 1, candidate(0, 100));
		add(ai, confirmed, 2, candidate(0, 150));
		add(ai, confirmed, 3, candidate(1, 100));
		add(ai, confirmed, 4, typed(100));
		add(ai, confirmed, 5, ConfirmedItem.removed("밥"));
		add(ai, confirmed, 6, new ConfirmedItem(null, null, "밥", 100, 220, 0, 0, 0, null));

		ConfirmStats stats = ConfirmStats.of(SINCE, NO_COUNTS, List.of(post(ai, confirmed, 300, 300)), 0);

		// 같음 1, 양만 고침 2, 다른 후보 3, 직접 적음 4, 뺌 5, 가를 수 없음 6
		assertThat(stats.foods()).isEqualTo(new FoodChoices(1, 2, 3, 4, 5, 6));
		// 남긴 음식 10개(1 + 2 + 3 + 4) 중 AI의 첫 이름이 맞은 것 3개(같음, 양만 고침), 후보 안에서 고른 것 6개(다른 후보까지)
		assertThat(stats.firstNameRate()).isEqualTo(30.0);
		assertThat(stats.withinCandidatesRate()).isEqualTo(60.0);
		assertThat(stats.unchangedPosts()).isZero();
	}

	// SQL이 센 건수와 읽지 못한 글 수는 그대로 낸다. 칸이 뒤바뀌면 잡히도록 값을 모두 다르게 둔다
	@Test
	void SQL이_센_건수와_읽지_못한_글_수는_그대로_낸다() {
		ConfirmStats stats = ConfirmStats.of(SINCE, new ConfirmCounts(6, 4, 9, 5, 3, 8, 1, 120, 30), List.of(), 7);

		assertThat(stats.since()).isEqualTo(SINCE);
		assertThat(stats.autoMode()).isEqualTo(6);
		assertThat(stats.userMode()).isEqualTo(4);
		assertThat(stats.done()).isEqualTo(9);
		assertThat(stats.confirmable()).isEqualTo(5);
		assertThat(stats.confirmed()).isEqualTo(3);
		assertThat(stats.unconfirmed()).isEqualTo(2);
		assertThat(stats.typedCalls()).isEqualTo(8);
		assertThat(stats.typedMismatch()).isEqualTo(1);
		assertThat(stats.typedInputTokens()).isEqualTo(120);
		assertThat(stats.typedOutputTokens()).isEqualTo(30);
		assertThat(stats.skipped()).isEqualTo(7);
		assertThat(stats.userModeRate()).isEqualTo(40.0);
		assertThat(stats.confirmRate()).isEqualTo(60.0);
		assertThat(stats.confirmedShare()).isEqualTo(33.3);
	}

	@Test
	void 칼로리_차이는_평균과_차이_크기의_평균과_가운데_값을_낸다() {
		// 차이: +300, -10, +20 (줄 세우지 않은 순서) → 평균 103, 크기 300, 10, 20 → 평균 110, 가운데 20 (평균과 다르다)
		ConfirmStats odd = ConfirmStats.of(SINCE, NO_COUNTS, List.of(
				post(List.of(food(100)), List.of(candidate(0, 100)), 300, 600),
				post(List.of(food(100)), List.of(candidate(1, 100)), 300, 290),
				post(List.of(food(100)), List.of(candidate(0, 50)), 300, 320)), 0);
		assertThat(odd.kcalDiffAvg()).isEqualTo(103L);
		assertThat(odd.kcalDiffAbsAvg()).isEqualTo(110L);
		assertThat(odd.kcalDiffAbsMedian()).isEqualTo(20L);

		// 개수가 짝수면 가운데 두 값의 평균: 차이 -40, +10, +100, -20 → 크기를 줄 세우면 10, 20, 40, 100 → 가운데 30.
		// 크기의 평균 42.5 → 43, 평균 12.5 → 13 (반올림)
		ConfirmStats even = ConfirmStats.of(SINCE, NO_COUNTS, List.of(
				post(List.of(food(100)), List.of(candidate(0, 100)), 300, 260),
				post(List.of(food(100)), List.of(candidate(0, 100)), 300, 310),
				post(List.of(food(100)), List.of(candidate(0, 100)), 300, 400),
				post(List.of(food(100)), List.of(candidate(0, 100)), 300, 280)), 0);
		assertThat(even.kcalDiffAbsMedian()).isEqualTo(30L);
		assertThat(even.kcalDiffAbsAvg()).isEqualTo(43L);
		assertThat(even.kcalDiffAvg()).isEqualTo(13L);
	}

	@Test
	void 나눌_기록이_없으면_비율과_칼로리_차이는_비어_있다() {
		ConfirmStats stats = ConfirmStats.of(SINCE, NO_COUNTS, List.of(), 0);

		assertThat(stats.userModeRate()).isNull();
		assertThat(stats.confirmRate()).isNull();
		assertThat(stats.confirmedShare()).isNull();
		assertThat(stats.unchangedRate()).isNull();
		assertThat(stats.firstNameRate()).isNull();
		assertThat(stats.withinCandidatesRate()).isNull();
		assertThat(stats.kcalDiffAvg()).isNull();
		assertThat(stats.kcalDiffAbsAvg()).isNull();
		assertThat(stats.kcalDiffAbsMedian()).isNull();
		assertThat(stats.unchangedPosts()).isZero();
	}

	private static FoodItem food(double amountG) {
		return new FoodItem("밥", amountG, 220, 48, 4, 1);
	}

	private static ConfirmedItem candidate(int pick, double amountG) {
		return new ConfirmedItem(ConfirmedItem.CANDIDATE, pick, "밥", amountG, 220, 48, 4, 1, null);
	}

	private static ConfirmedItem typed(double amountG) {
		return new ConfirmedItem(ConfirmedItem.TYPED, null, "잡곡밥", amountG, 230, 49, 5, 1, true);
	}

	// AI 음식 하나와 확정한 음식 하나를 같은 자리에 times번 넣는다
	private static void add(List<FoodItem> ai, List<ConfirmedItem> confirmed, int times, ConfirmedItem item) {
		for (int i = 0; i < times; i++) {
			ai.add(food(100));
			confirmed.add(item);
		}
	}

	private static ConfirmedPost post(List<FoodItem> ai, List<ConfirmedItem> confirmed, int aiKcal, int confirmedKcal) {
		return new ConfirmedPost(ai, confirmed, BigDecimal.valueOf(aiKcal), BigDecimal.valueOf(confirmedKcal));
	}
}
