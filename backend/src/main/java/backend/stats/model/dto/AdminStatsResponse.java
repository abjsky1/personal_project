package backend.stats.model.dto;

import java.time.LocalDate;
import java.util.List;

// 운영자 통계 API의 응답 (GET /api/admin/stats). 개인을 알아볼 수 있는 값 없이 집계 숫자만 담는다.
// 비율은 모두 퍼센트(소수점 한 자리)이고, 계산할 기록이 없으면 null이다.
public record AdminStatsResponse(int days, LocalDate from, LocalDate to, List<DailyActivity> daily, Totals totals,
		AnalysisStats analysis, LocationStats location, FeedbackCounts feedback) {

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

	private static Double percent(long part, long whole) {
		if (whole == 0) {
			return null;
		}
		return Math.round(part * 1000.0 / whole) / 10.0;
	}
}
