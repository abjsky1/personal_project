package backend.analysis.model.dto;

import java.math.BigDecimal;
import java.util.List;

import backend.analysis.model.dto.FoodAnalysisResult.FoodItem;
import backend.analysis.model.entity.AnalysisStatus;
import backend.analysis.model.entity.FoodAnalysis;

// 상세 화면(JSP)으로 넘기는 분석 결과. 토큰 수·실패 이유 같은 내부 정보는 넣지 않는다.
public record FoodAnalysisView(
		AnalysisStatus status,
		BigDecimal totalKcal,
		BigDecimal carbsG,
		BigDecimal proteinG,
		BigDecimal fatG,
		List<FoodItem> items,
		String confidenceLabel,
		String comment) {

	public static FoodAnalysisView of(FoodAnalysis analysis, List<FoodItem> items) {
		return new FoodAnalysisView(
				analysis.getStatus(),
				analysis.getTotalKcal(),
				analysis.getCarbsG(),
				analysis.getProteinG(),
				analysis.getFatG(),
				items,
				confidenceLabel(analysis.getConfidence()),
				analysis.getComment());
	}

	// AI가 준 확신 정도를 화면에 보여 줄 말로 바꾼다.
	private static String confidenceLabel(String confidence) {
		if ("HIGH".equals(confidence)) {
			return "높음";
		}
		if ("MEDIUM".equals(confidence)) {
			return "보통";
		}
		if ("LOW".equals(confidence)) {
			return "낮음";
		}
		return null;
	}
}
