package backend.analysis.model.dto;

import java.math.BigDecimal;
import java.util.List;

import backend.analysis.model.dto.FoodAnalysisResult.FoodItem;
import backend.analysis.model.entity.AnalysisStatus;
import backend.analysis.model.entity.ConfirmMode;
import backend.analysis.model.entity.FoodAnalysis;

// 상세 화면(JSP)으로 넘기는 분석 결과. 토큰 수, 실패 이유 같은 내부 정보는 넣지 않는다.
//
// 화면은 AI의 답과 사용자가 확정한 값을 둘 다 보여 준다 (JM-74). 확정했다고 AI의 답을 지우지 않는다: 나란히 놓고 비교할 수 있게 한다.
// 화면의 꼬리표로는 AI의 값이 "AI", 확정한 값이 "REAL"이다. 코드에서는 지금까지처럼 confirmed(확정)라고 부른다.
//
// aiTotals: AI의 첫 답의 합계. 분석이 끝난(DONE) 글에만 값이 들어 있다.
// confirmedTotals: 사용자가 확정한 합계. 확정하지 않았으면 null
// rows: 음식 표의 줄들. 음식마다 AI의 답과, 확정했으면 그 음식을 어떻게 했는지
// userMode: 올릴 때 "내가 보고 확정"을 골랐는지 / confirmed: 확정을 마쳤는지
// confirmRows: 확정 칸에 그릴 줄들 (음식마다 후보). 후보가 없는 글과 이미 확정한 글은 빈 목록이라 확정 칸이 나오지 않는다.
//   확정은 글마다 한 번뿐이다. 확정한 뒤에는 다시 고칠 수 없다.
// typedAllowed: 후보에 없는 음식의 이름을 직접 적을 수 있는지. 직접 적기는 AI를 한 번 더 부르므로 글마다 한 번만 쓸 수 있다.
public record FoodAnalysisView(
		AnalysisStatus status,
		Totals aiTotals,
		Totals confirmedTotals,
		List<FoodRow> rows,
		String confidenceLabel,
		String comment,
		boolean userMode,
		boolean confirmed,
		List<ConfirmRow> confirmRows,
		boolean typedAllowed) {

	// 합계 한 줄: 칼로리와 탄수화물, 단백질, 지방(g)
	public record Totals(BigDecimal kcal, BigDecimal carbsG, BigDecimal proteinG, BigDecimal fatG) {
	}

	// 음식 표의 한 줄 = AI가 본 음식 하나.
	// ai: AI의 답 (항상 있다) / confirmed: 사용자가 확정한 값 (바꾼 경우에만 화면에 따로 그린다)
	// change: 사용자가 이 음식을 어떻게 했는지. 화면이 이 값으로 줄의 모양을 고른다.
	public record FoodRow(FoodItem ai, FoodItem confirmed, String change) {

		// 확정하지 않은 글: AI의 줄만 그린다
		public static final String NONE = "NONE";
		// AI의 답을 그대로 둠 (같은 음식, 같은 양): 한 줄에 "AI와 같음" 표시
		public static final String SAME = "SAME";
		// 다른 음식으로 바꾸거나 양을 고침: AI의 줄은 흐리게 두고, 바로 아래에 확정한 줄
		public static final String CHANGED = "CHANGED";
		// "이 음식 아님"으로 뺌: AI의 줄은 흐리게 두고, 아래에 뺐다는 줄
		public static final String REMOVED = "REMOVED";

		public static FoodRow aiOnly(FoodItem ai) {
			return new FoodRow(ai, null, NONE);
		}
	}

	// 확정 칸의 한 줄 = 음식 하나. index: 몇 번째 음식인지 (0부터) / amountG: AI가 추정한 양
	public record ConfirmRow(int index, List<Option> options, double amountG) {
	}

	// 고를 수 있는 후보 하나.
	// value: 화면의 고르는 칸(option)에 넣을 값 = 후보의 순서를 글자로 바꾼 것 ("0" = AI의 첫 답).
	//   숫자가 아니라 글자인 이유: 고르는 칸에는 "typed"(직접 적기) 같은 숫자가 아닌 값도 있어서, 화면이 글자끼리 비교해야 한다.
	// kcalPer100g: 화면이 "지금 고른 대로면 몇 kcal인지"를 미리 보여 주는 데 쓴다. 저장할 때는 서버가 다시 계산한다.
	public record Option(String value, String name, double kcalPer100g) {
	}

	// rows: 음식 표의 줄들 / confirmRows: 확정 칸의 줄들
	public static FoodAnalysisView of(FoodAnalysis analysis, List<FoodRow> rows, List<ConfirmRow> confirmRows) {
		boolean confirmed = analysis.isConfirmed();
		return new FoodAnalysisView(
				analysis.getStatus(),
				new Totals(analysis.getTotalKcal(), analysis.getCarbsG(), analysis.getProteinG(), analysis.getFatG()),
				confirmed ? new Totals(analysis.getConfirmedTotalKcal(), analysis.getConfirmedCarbsG(),
						analysis.getConfirmedProteinG(), analysis.getConfirmedFatG()) : null,
				rows,
				confidenceLabel(analysis.getConfidence()),
				analysis.getComment(),
				analysis.getConfirmMode() == ConfirmMode.USER,
				confirmed,
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
