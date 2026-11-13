package backend.analysis.model.dto;

// 후보 분석의 결과와, 그 결과를 얻는 데 쓴 모델, 토큰 수 (FoodAnalyzerResponse의 후보판, JM-74)
public record FoodCandidatesResponse(FoodCandidates result, String model, int inputTokens, int outputTokens) {
}
