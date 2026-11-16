package backend.stats.model.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import backend.analysis.model.dto.ConfirmedItem;
import backend.analysis.model.dto.FoodAnalysisResult.FoodItem;

// 운영자 통계 API의 응답 (GET /api/admin/stats). 개인을 알아볼 수 있는 값 없이 집계 숫자만 담는다.
// 비율은 모두 퍼센트(소수점 한 자리)이고, 계산할 기록이 없으면 null이다.
// 새 항목은 맨 뒤에 붙인다 (예전 키는 그대로 두어 curl | jq 명령이 계속 맞게): confirm (JM-75)
public record AdminStatsResponse(int days, LocalDate from, LocalDate to, List<DailyActivity> daily, Totals totals,
		AnalysisStats analysis, LocationStats location, FeedbackCounts feedback, ConfirmStats confirm) {

	// 기간 전체의 합계
	public record Totals(long posts, long comments, long feedbacks) {

		public static Totals of(List<DailyActivity> daily) {
			return new Totals(
					daily.stream().mapToLong(DailyActivity::posts).sum(),
					daily.stream().mapToLong(DailyActivity::comments).sum(),
					daily.stream().mapToLong(DailyActivity::feedbacks).sum());
		}
	}

	// successRate: AI가 답한 건의 비율. 음식이 아니라고 답한 것도 성공이고, 한도 초과와 대기 중은 AI를 부르지 않아서 뺀다. (기획서 17절)
	// usedToday, dailyLimit: 오늘 끝난 AI 호출 수와 하루 상한 (기간과 상관없이 오늘 기준)
	public record AnalysisStats(long done, long notFood, long failed, long limitExceeded, long pending,
			Double successRate, Long avgLatencyMs, Long p95LatencyMs, long inputTokens, long outputTokens,
			long usedToday, int dailyLimit, Double todayUsageRate) {

		public static AnalysisStats of(AnalysisCounts counts, Long p95LatencyMs, long usedToday, int dailyLimit) {
			long answered = counts.done() + counts.notFood();
			return new AnalysisStats(counts.done(), counts.notFood(), counts.failed(), counts.limitExceeded(), counts.pending(),
					percent(answered, answered + counts.failed()),
					counts.avgLatencyMs() == null ? null : Math.round(counts.avgLatencyMs()),
					p95LatencyMs, counts.inputTokens(), counts.outputTokens(),
					usedToday, dailyLimit, percent(usedToday, dailyLimit));
		}
	}

	// collectionRate: 남아 있는 글 중 위치가 저장된 글의 비율 (= 동의율 x GPS가 있는 사진의 비율, 기획서 17절)
	public record LocationStats(long activePosts, long withLocation, Double collectionRate) {

		public static LocationStats of(LocationCounts counts) {
			return new LocationStats(counts.activePosts(), counts.withLocation(), percent(counts.withLocation(), counts.activePosts()));
		}
	}

	// 고쳐 쓰는 AI의 확정 통계 (JM-75, 기획서 6.5절)
	// since: 이 시각(한국 시각) 뒤에 올린 글만 센다. 기간의 시작과 고쳐 쓰는 AI 배포 시각(11-13 10:05) 중 늦은 쪽
	// userModeRate: 올릴 때 "내가 보고 확정"을 고른 비율 (기본으로 골라져 있는 "AI가 바로 확정"에서 바꾼 비율)
	// confirmable: 그중 분석이 끝나 후보가 있어 확정할 수 있던 글 / confirmRate: 그중 실제로 확정한 비율 / unconfirmed: 아직 확정 안 한 글
	// confirmedShare: 칼로리가 나온 글(DONE) 중 확정 값(REAL)이 있는 비율
	// unchangedRate: 확정한 글 중 모든 음식을 AI의 첫 답 그대로 확정한 비율 (글 화면의 "AI와 같음"과 같은 기준, ConfirmedItem.sameAs)
	// foods: 음식마다 한 것 / firstNameRate: 남긴 음식 중 AI의 첫 이름이 맞은 비율 (양만 고친 것 포함)
	// withinCandidatesRate: 남긴 음식 중 후보 세 개 안에서 고른 비율 (나머지는 이름을 직접 적은 것)
	// kcalDiff*: 확정 합계 - AI 첫 답 합계 (kcal, 정수). 음수면 AI가 많게 잡은 것. Avg는 평균, AbsAvg와 AbsMedian은 차이 크기의 평균과 가운데 값
	// typed*: 이름을 직접 적어 AI를 한 번 더 부른 글, 사진과 다르다고 본 확정, 그 호출의 토큰 / skipped: 기록을 읽지 못해 뺀 확정 글
	// 음식 이름과 직접 적은 글자는 내지 않고 개수만 낸다
	public record ConfirmStats(LocalDateTime since, long autoMode, long userMode, Double userModeRate, long done,
			long confirmable, long confirmed, long unconfirmed, Double confirmRate, Double confirmedShare,
			long unchangedPosts, Double unchangedRate, FoodChoices foods, Double firstNameRate, Double withinCandidatesRate,
			Long kcalDiffAvg, Long kcalDiffAbsAvg, Long kcalDiffAbsMedian,
			long typedCalls, long typedMismatch, long typedInputTokens, long typedOutputTokens, long skipped) {

		public static ConfirmStats of(LocalDateTime since, ConfirmCounts counts, List<ConfirmedPost> posts, long skipped) {
			long[] kinds = new long[FoodChoices.Kind.values().length];
			long unchanged = 0;
			List<Double> diffs = new ArrayList<>();
			for (ConfirmedPost post : posts) {
				// 음식 수가 같고 모든 음식이 "AI와 같음"이어야 AI의 첫 답 그대로 확정한 글이다
				boolean allSame = !post.confirmed().isEmpty() && post.confirmed().size() == post.ai().size();
				for (int i = 0; i < post.confirmed().size(); i++) {
					FoodItem ai = i < post.ai().size() ? post.ai().get(i) : null;
					FoodChoices.Kind kind = FoodChoices.kindOf(ai, post.confirmed().get(i));
					kinds[kind.ordinal()]++;
					allSame &= kind == FoodChoices.Kind.SAME;
				}
				if (allSame) {
					unchanged++;
				}
				if (post.aiKcal() != null && post.confirmedKcal() != null) {
					diffs.add(post.confirmedKcal().subtract(post.aiKcal()).doubleValue());
				}
			}
			FoodChoices foods = new FoodChoices(kinds[0], kinds[1], kinds[2], kinds[3], kinds[4], kinds[5]);
			long kept = foods.same() + foods.amountChanged() + foods.otherCandidate() + foods.typed();
			List<Double> sizes = diffs.stream().map(Math::abs).sorted().toList();
			return new ConfirmStats(since, counts.autoMode(), counts.userMode(),
					percent(counts.userMode(), counts.autoMode() + counts.userMode()), counts.done(),
					counts.confirmable(), counts.confirmed(), Math.max(0, counts.confirmable() - counts.confirmed()),
					percent(counts.confirmed(), counts.confirmable()), percent(counts.confirmed(), counts.done()),
					unchanged, percent(unchanged, posts.size()), foods,
					percent(foods.same() + foods.amountChanged(), kept),
					percent(foods.same() + foods.amountChanged() + foods.otherCandidate(), kept),
					average(diffs), average(sizes), median(sizes),
					counts.typedCalls(), counts.typedMismatch(), counts.typedInputTokens(), counts.typedOutputTokens(), skipped);
		}

		private static Long average(List<Double> values) {
			return values.isEmpty() ? null : Math.round(values.stream().mapToDouble(Double::doubleValue).sum() / values.size());
		}

		// 가운데 값. 개수가 짝수면 가운데 두 값의 평균 / sorted: 작은 순으로 줄 세운 값
		private static Long median(List<Double> sorted) {
			if (sorted.isEmpty()) {
				return null;
			}
			int middle = sorted.size() / 2;
			double value = sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
			return Math.round(value);
		}
	}

	// 확정한 음식 하나하나가 어느 쪽인지 (JM-75). 음식마다 꼭 하나에만 들어간다
	// same: AI와 같음 / amountChanged: AI의 첫 이름 그대로 양만 고침 / otherCandidate: 다른 후보를 고름 / typed: 이름을 직접 적음
	// removed: "이 음식 아님"으로 뺌 / unknown: 기록 모양이 달라 가를 수 없음 (고쳐 쓰는 AI 첫 안 때의 로컬 시험 기록뿐)
	public record FoodChoices(long same, long amountChanged, long otherCandidate, long typed, long removed, long unknown) {

		// 위 순서와 같은 순서 (ConfirmStats.of가 순서로 센다)
		public enum Kind {
			SAME, AMOUNT_CHANGED, OTHER_CANDIDATE, TYPED, REMOVED, UNKNOWN
		}

		// ai: 같은 자리의 AI 첫 답 음식 (음식 수가 맞지 않아 없으면 null)
		public static Kind kindOf(FoodItem ai, ConfirmedItem confirmed) {
			if (confirmed == null || confirmed.choice() == null) {
				return Kind.UNKNOWN;
			}
			if (ConfirmedItem.REMOVED.equals(confirmed.choice())) {
				return Kind.REMOVED;
			}
			if (ConfirmedItem.TYPED.equals(confirmed.choice())) {
				return Kind.TYPED;
			}
			if (!ConfirmedItem.CANDIDATE.equals(confirmed.choice()) || confirmed.pick() == null || confirmed.pick() < 0 || ai == null) {
				return Kind.UNKNOWN;
			}
			if (confirmed.pick() > 0) {
				return Kind.OTHER_CANDIDATE;
			}
			return confirmed.sameAs(ai) ? Kind.SAME : Kind.AMOUNT_CHANGED;
		}
	}

	private static Double percent(long part, long whole) {
		if (whole == 0) {
			return null;
		}
		return Math.round(part * 1000.0 / whole) / 10.0;
	}
}
