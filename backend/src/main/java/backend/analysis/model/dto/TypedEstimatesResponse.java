package backend.analysis.model.dto;

// 직접 적은 음식에 대한 AI의 답과, 그 답을 얻는 데 쓴 모델, 토큰 수 (JM-74)
public record TypedEstimatesResponse(TypedEstimates result, String model, int inputTokens, int outputTokens) {
}
