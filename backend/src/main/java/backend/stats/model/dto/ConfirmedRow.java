package backend.stats.model.dto;

import java.math.BigDecimal;

// 확정한 글 하나의 AI 첫 답과 확정 값 (JM-75, StatsMapper.findConfirmedRows). SELECT의 열 순서와 같은 순서로 둔다.
// items, confirmedItems: 음식별 상세의 JSON 글자. StatsService가 풀어서 음식마다 무엇을 했는지 센다.
// 음식 이름이 들어 있어서 통계 응답에는 그대로 내지 않는다 (개수만 낸다)
public record ConfirmedRow(Long postId, BigDecimal totalKcal, BigDecimal confirmedTotalKcal, String items, String confirmedItems) {
}
