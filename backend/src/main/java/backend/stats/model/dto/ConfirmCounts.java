package backend.stats.model.dto;

// 고쳐 쓰는 AI의 확정 통계에 쓰는 건수 (JM-75, StatsMapper.findConfirmCounts). SELECT의 열 순서와 같은 순서로 둔다.
// autoMode, userMode: 올릴 때 고른 확정 방식별 글 수 / done: 칼로리가 나온(분석이 끝난) 글 수
// confirmable: "내가 보고 확정"으로 올렸고 분석이 끝나 후보가 있는 글 (확정할 수 있던 글) / confirmed: 실제로 확정한 글
// typedCalls: 이름을 직접 적어 AI를 한 번 더 부른 글 / typedMismatch: 직접 적은 음식을 AI가 사진과 다르다고 본 확정 글
// typedInputTokens, typedOutputTokens: 그 두 번째 호출에 쓴 토큰 (첫 분석의 토큰은 AnalysisCounts에 있다)
public record ConfirmCounts(long autoMode, long userMode, long done, long confirmable, long confirmed, long typedCalls,
		long typedMismatch, long typedInputTokens, long typedOutputTokens) {
}
