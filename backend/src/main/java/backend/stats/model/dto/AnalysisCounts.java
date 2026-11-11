package backend.stats.model.dto;

// AI 분석의 상태별 건수와 응답 시간, 토큰 합계. SQL의 열 순서와 같아야 한다 (StatsMapper.xml).
// avgLatencyMs: AI를 부른 기록이 없으면 null
public record AnalysisCounts(long done, long notFood, long failed, long limitExceeded, long pending,
		Double avgLatencyMs, long inputTokens, long outputTokens) {
}
