package backend.analysis.model.dto;

import java.util.List;

import backend.analysis.model.dto.FoodAnalysisResult.FoodItem;

// "내가 보고 확정"을 고른 글에서 AI가 돌려주는 답 (JM-74, 고쳐 쓰는 AI).
// 이름과 타입을 GeminiFoodAnalyzer의 후보용 responseSchema와 똑같이 맞춘다.
//
// FoodAnalysisResult와 다른 점 두 가지
// - 음식마다 이름 하나가 아니라 후보 여러 개를 받는다 (가능성이 높은 순서)
// - 영양을 "사진 속 양"이 아니라 "100g당" 값으로 받는다
//   → 사용자가 다른 후보를 고르거나 양을 고쳐도, AI를 다시 부르지 않고 서버가 곱해서 계산할 수 있다
public record FoodCandidates(boolean isFood, List<CandidateItem> items, String confidence, String comment) {

	// 사진 속 음식 하나: 추정한 양(g)과, 그 음식일 가능성이 높은 순서로 놓인 후보들
	public record CandidateItem(double amountG, List<Candidate> candidates) {
	}

	// 후보 하나: 이름과 100g당 칼로리, 탄수화물, 단백질, 지방
	public record Candidate(String name, double kcalPer100g, double carbsPer100g, double proteinPer100g, double fatPer100g) {

		// 이 후보를 amountG만큼 먹었을 때의 값으로 바꾼다 (100g당 값 × 양 ÷ 100). 소수점 한 자리로 맞춘다.
		public FoodItem toItem(double amountG) {
			return new FoodItem(name, amountG,
					scale(kcalPer100g, amountG),
					scale(carbsPer100g, amountG),
					scale(proteinPer100g, amountG),
					scale(fatPer100g, amountG));
		}

		private static double scale(double per100g, double amountG) {
			return Math.round(per100g * amountG / 100 * 10) / 10.0;
		}
	}

	// AI의 첫 답: 음식마다 첫 번째 후보를 고른 결과.
	// "AI가 바로 확정"의 결과(FoodAnalysisResult)와 같은 모양이라, 검사와 저장과 화면을 그대로 쓴다.
	public FoodAnalysisResult firstAnswer() {
		List<FoodItem> first = items.stream()
				.map(item -> item.candidates().get(0).toItem(item.amountG()))
				.toList();
		return new FoodAnalysisResult(isFood, first, confidence, comment);
	}
}
