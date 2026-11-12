package backend.analysis.model.dto;

import java.math.BigDecimal;
import java.util.List;

import backend.analysis.model.dto.FoodAnalysisResult.FoodItem;
import backend.analysis.model.entity.AnalysisStatus;
import backend.analysis.model.entity.ConfirmMode;
import backend.analysis.model.entity.FoodAnalysis;

// 상세 화면(JSP)으로 넘기는 분석 결과. 토큰 수, 실패 이유 같은 내부 정보는 넣지 않는다.
//
// totalKcal, carbsG, proteinG, fatG, items: 화면에 보여 줄 값. 사용자가 확정했으면 확정한 값이고, 아니면 AI의 첫 답이다 (JM-74).
// userMode: 올릴 때 "내가 보고 확정"을 골랐는지 / confirmed: 확정을 마쳤는지
// aiTotalKcal: AI의 첫 답의 합계. 확정한 글에서 "AI는 처음에 얼마라고 했는지"를 함께 보여 주려고 넘긴다.
// confirmRows: 확정 칸에 그릴 줄들 (음식마다 후보). 후보가 없는 글과 이미 확정한 글은 빈 목록이라 확정 칸이 나오지 않는다.
//   확정은 글마다 한 번뿐이다. 확정한 뒤에는 다시 고칠 수 없다.
// typedAllowed: 후보에 없는 음식의 이름을 직접 적을 수 있는지. 직접 적기는 AI를 한 번 더 부르므로 글마다 한 번만 쓸 수 있다.
public record FoodAnalysisView(
		AnalysisStatus status,
		BigDecimal totalKcal,
		BigDecimal carbsG,
		BigDecimal proteinG,
		BigDecimal fatG,
		List<FoodItem> items,
		String confidenceLabel,
		String comment,
		boolean userMode,
		boolean confirmed,
		BigDecimal aiTotalKcal,
		List<ConfirmRow> confirmRows,
		boolean typedAllowed) {

	// 확정 칸의 한 줄 = 음식 하나. index: 몇 번째 음식인지 (0부터) / amountG: AI가 추정한 양
	public record ConfirmRow(int index, List<Option> options, double amountG) {
	}

	// 고를 수 있는 후보 하나.
	// value: 화면의 고르는 칸(option)에 넣을 값 = 후보의 순서를 글자로 바꾼 것 ("0" = AI의 첫 답).
	//   숫자가 아니라 글자인 이유: 고르는 칸에는 "typed"(직접 적기) 같은 숫자가 아닌 값도 있어서, 화면이 글자끼리 비교해야 한다.
	// kcalPer100g: 화면이 "지금 고른 대로면 몇 kcal인지"를 미리 보여 주는 데 쓴다. 저장할 때는 서버가 다시 계산한다.
	public record Option(String value, String name, double kcalPer100g) {
	}

	// shownItems: 화면에 보여 줄 음식 목록 (확정했으면 확정한 것, 아니면 AI의 첫 답) / confirmRows: 확정 칸의 줄들
	public static FoodAnalysisView of(FoodAnalysis analysis, List<FoodItem> shownItems, List<ConfirmRow> confirmRows) {
		boolean confirmed = analysis.isConfirmed();
		return new FoodAnalysisView(
				analysis.getStatus(),
				confirmed ? analysis.getConfirmedTotalKcal() : analysis.getTotalKcal(),
				confirmed ? analysis.getConfirmedCarbsG() : analysis.getCarbsG(),
				confirmed ? analysis.getConfirmedProteinG() : analysis.getProteinG(),
				confirmed ? analysis.getConfirmedFatG() : analysis.getFatG(),
				shownItems,
				confidenceLabel(analysis.getConfidence()),
				analysis.getComment(),
				analysis.getConfirmMode() == ConfirmMode.USER,
				confirmed,
				analysis.getTotalKcal(),
				confirmRows,
				!analysis.isTypedUsed());
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
