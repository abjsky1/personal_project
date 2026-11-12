package backend.analysis.model.dto;

import java.util.List;

// 사용자가 직접 적은 음식 이름에 대해 AI가 돌려주는 답 (JM-74, 고쳐 쓰는 AI).
// 이름과 타입을 GeminiFoodAnalyzer의 "직접 적은 음식"용 responseSchema와 똑같이 맞춘다.
// 적은 순서대로, 같은 개수로 답하라고 지시하지만 보장되지는 않아서, 받는 쪽(FoodAnalysisService)이 개수를 확인한다.
public record TypedEstimates(List<TypedEstimate> items) {

	// isFood: 적은 글자가 음식이나 음료의 이름인지 (장난 글, 음식이 아닌 물건이면 false)
	// matchesPhoto: 사진 속에 그 음식이 있을 수 있는지 (비슷하게 보일 수 있으면 true)
	// 나머지: 그 음식의 100g당 칼로리, 탄수화물, 단백질, 지방
	public record TypedEstimate(boolean isFood, boolean matchesPhoto, double kcalPer100g, double carbsPer100g,
			double proteinPer100g, double fatPer100g) {
	}
}
