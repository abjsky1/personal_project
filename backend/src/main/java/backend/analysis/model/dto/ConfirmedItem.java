package backend.analysis.model.dto;

import backend.analysis.model.dto.FoodAnalysisResult.FoodItem;

// 사용자가 확정한 음식 하나 (JM-74). food_analyses.confirmed_items에 JSON 글자로 저장한다.
// AI가 낸 음식과 같은 순서, 같은 개수로 저장해서, 나중에 음식마다 "AI의 답을 어떻게 고쳤는지"를 볼 수 있다.
//
// choice: 무엇을 했는지
//   CANDIDATE = 후보에서 골랐다 (pick: 고른 후보의 순서. 0이면 AI의 첫 답을 그대로 둔 것)
//   TYPED     = 후보에 없어서 이름을 직접 적었다 (영양은 AI가 사진과 이름을 함께 보고 다시 계산한 값)
//   REMOVED   = "이 음식 아님"으로 뺐다 (이름은 AI의 첫 답, 양과 영양은 0)
// matchesPhoto: 직접 적은 음식에만 있다. AI가 "사진 속에 그 음식이 있을 수 있다"고 봤는지.
//   false여도 확정은 받는다 (글쓴이가 사진보다 잘 안다). 나중에 통계나 학습 데이터에서 뺄 수 있게 남겨 두는 표시다.
// 영양 값은 모두 서버가 "100g당 값 × 양"으로 계산한 것이다. 화면이 보낸 숫자를 그대로 쓰지 않는다.
public record ConfirmedItem(String choice, Integer pick, String name, double amountG, double kcal, double carbsG,
		double proteinG, double fatG, Boolean matchesPhoto) {

	public static final String CANDIDATE = "CANDIDATE";
	public static final String TYPED = "TYPED";
	public static final String REMOVED = "REMOVED";

	public static ConfirmedItem candidate(int pick, FoodItem food) {
		return new ConfirmedItem(CANDIDATE, pick, food.name(), food.amountG(), food.kcal(), food.carbsG(), food.proteinG(), food.fatG(), null);
	}

	public static ConfirmedItem typed(FoodItem food, boolean matchesPhoto) {
		return new ConfirmedItem(TYPED, null, food.name(), food.amountG(), food.kcal(), food.carbsG(), food.proteinG(), food.fatG(), matchesPhoto);
	}

	// aiName: AI가 처음에 무엇이라고 했는지만 이름으로 남긴다
	public static ConfirmedItem removed(String aiName) {
		return new ConfirmedItem(REMOVED, null, aiName, 0, 0, 0, 0, 0, null);
	}

	// "AI와 같음"의 기준: AI의 첫 답(후보 0번)을 그대로 고르고 양도 그대로 둔 경우.
	// 글 화면의 줄 표시(FoodAnalysisService.compare)와 운영자 통계(JM-75, AdminStatsResponse.ConfirmStats)가 함께 쓴다.
	// 양은 g 단위의 정수로 견준다: 확정 칸이 AI의 양을 정수로 채워 두므로, AI가 152.4g이라고 했어도 손대지 않고 확정하면 152g으로 온다
	// Math.rint: 확정 칸을 채우는 fmt:formatNumber와 같은 반올림 (딱 .5면 짝수 쪽: 152.5 → 152, 153.5 → 154). Math.round(.5는 올림)를
	// 쓰면 152.5g을 손대지 않고 확정해도 칸에 채워진 152와 153이 달라 "양만 고침"이 된다 (JM-75 검토)
	public boolean sameAs(FoodItem ai) {
		return CANDIDATE.equals(choice) && Integer.valueOf(0).equals(pick) && Math.rint(ai.amountG()) == Math.rint(amountG);
	}

	// 합계와 화면의 음식 표에 들어가는 음식인지 (뺀 음식이 아닌지)
	public boolean kept() {
		return !REMOVED.equals(choice);
	}

	// 화면의 음식 표에 넣을 모양으로 바꾼다
	public FoodItem toFoodItem() {
		return new FoodItem(name, amountG, kcal, carbsG, proteinG, fatG);
	}
}
