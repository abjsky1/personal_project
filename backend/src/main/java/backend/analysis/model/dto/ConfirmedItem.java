package backend.analysis.model.dto;

import backend.analysis.model.dto.FoodAnalysisResult.FoodItem;

// 사용자가 확정한 음식 하나 (JM-74). food_analyses.confirmed_items에 JSON 글자로 저장한다.
// AI가 낸 음식과 같은 순서, 같은 개수로 저장해서, 나중에 "몇 번째 후보를 골랐는지"를 음식마다 볼 수 있다.
//
// pick: 고른 후보의 순서. 0이면 AI의 첫 답을 그대로 둔 것, 1이나 2면 다른 후보로 바꾼 것,
//       -1이면 "이 음식 아님"으로 뺀 것이다 (뺀 음식은 이름만 남기고 양과 영양은 0으로 둔다)
// 나머지 값은 서버가 저장해 둔 후보의 100g당 값에 양을 곱해서 계산한 것이다. 화면이 보낸 숫자를 그대로 쓰지 않는다.
public record ConfirmedItem(int pick, String name, double amountG, double kcal, double carbsG, double proteinG, double fatG) {

	public static final int REMOVED = -1;

	// 화면의 음식 표에 넣을 모양으로 바꾼다
	public FoodItem toFoodItem() {
		return new FoodItem(name, amountG, kcal, carbsG, proteinG, fatG);
	}
}
