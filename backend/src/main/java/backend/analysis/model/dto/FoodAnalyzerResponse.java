package backend.analysis.model.dto;

// 분석 결과와, 그 결과를 얻는 데 쓴 모델, 토큰 수 (비용과 성능을 나중에 계산하려고 함께 저장한다)
public record FoodAnalyzerResponse(FoodAnalysisResult result, String model, int inputTokens, int outputTokens) {
}
