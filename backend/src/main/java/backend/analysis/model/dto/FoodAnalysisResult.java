package backend.analysis.model.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.function.ToDoubleFunction;

// AI가 돌려준 분석 결과. 이름과 타입을 responseSchema(GeminiFoodAnalyzer)와 똑같이 맞춘다. (기획서 6.3절)
// Map 대신 record를 쓰는 이유: 검사, 합계, 저장, 화면에서 값을 꺼내 쓰는데, 이름을 틀리면 실행 전에 빨간 줄로 알 수 있다.
public record FoodAnalysisResult(boolean isFood, List<FoodItem> items, String confidence, String comment) {

	// 음식 하나. 숫자는 320.5처럼 소수로 올 수 있어서 double로 받는다.
	public record FoodItem(String name, double amountG, double kcal, double carbsG, double proteinG, double fatG) {
	}

	// 항목들의 합계를 소수점 한 자리로 계산한다. AI가 합계를 직접 내게 하지 않는 이유: 항목의 합과 어긋날 수 있다.
	// ToDoubleFunction: "음식 하나에서 어떤 숫자를 꺼낼지"를 넘겨받는다. 예: total(FoodItem::kcal)
	public BigDecimal total(ToDoubleFunction<FoodItem> value) {
		double sum = items.stream().mapToDouble(value).sum();
		return BigDecimal.valueOf(sum).setScale(1, RoundingMode.HALF_UP);
	}
}
