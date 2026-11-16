package backend.stats.model.dto;

import java.math.BigDecimal;
import java.util.List;

import backend.analysis.model.dto.ConfirmedItem;
import backend.analysis.model.dto.FoodAnalysisResult.FoodItem;

// 확정한 글 하나를 읽은 것 (JM-75). StatsService가 ConfirmedRow의 JSON을 풀어서 만들고, AdminStatsResponse.ConfirmStats가 센다.
// ai: AI의 첫 답 음식들 / confirmed: 확정한 음식들 (ai와 같은 순서, 같은 개수) / aiKcal, confirmedKcal: 두 칼로리 합계
public record ConfirmedPost(List<FoodItem> ai, List<ConfirmedItem> confirmed, BigDecimal aiKcal, BigDecimal confirmedKcal) {
}
