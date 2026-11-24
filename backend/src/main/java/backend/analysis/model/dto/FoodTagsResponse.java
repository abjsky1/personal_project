package backend.analysis.model.dto;

// 지난 글의 태그를 채울 때(classify, JM-83)의 답과, 그 답에 쓴 모델과 토큰 수 (FoodAnalyzerResponse와 같은 방식)
public record FoodTagsResponse(FoodTags tags, String model, int inputTokens, int outputTokens) {
}
