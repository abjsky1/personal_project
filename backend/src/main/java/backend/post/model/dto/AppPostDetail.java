package backend.post.model.dto;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import backend.analysis.model.dto.FoodAnalysisView;
import backend.analysis.model.dto.FoodAnalysisView.FoodRow;
import backend.analysis.model.dto.FoodAnalysisView.Totals;
import backend.analysis.model.entity.AnalysisStatus;
import backend.post.model.entity.Post;

// 앱의 글 상세 (GET /api/posts/{id}, JM-90, 기획서 19.2). 웹 상세 화면에 보이는 값 가운데 앱의 첫 조각이 쓰는 것을 JSON으로 보낸다
// imagePath: 큰 사진의 경로 (/images/파일이름). 사진을 가린 글(JM-80)은 null이고 photoHeld가 true다
// createdAt: 올린 시각 (AppPostCard와 같다: UTC의 ISO-8601, 초까지)
// viewCount: 웹 조회수 (JM-72). 앱에서 연 것은 여기에 더하지 않고 따로 센다 (app_post_views, 기획서 19.3)
// analysis: 분석 결과. 분석 기록이 없는 옛날 글은 null
// 보내지 않는 값 (기획서 19.2): 비밀번호 해시, 위치(글과 다른 표에 있다), 사진의 촬영 시각, 분석의 내부 값(모델, 토큰, 걸린 시간, 오류 내용)
public record AppPostDetail(
		Long id,
		String nickname,
		String memo,
		String imagePath,
		boolean photoHeld,
		Instant createdAt,
		int viewCount,
		Analysis analysis) {

	// 분석 결과: 웹 상세 화면과 같은 값이다 (FoodAnalysisService.getView). 확정 칸의 재료(후보, 직접 적기를 쓸 수 있는지)는
	// 앱의 첫 조각이 읽기만 해서 넣지 않는다
	// status: 분석 상태
	// aiTotals: AI의 첫 답의 합계 (웹의 AI 줄). 분석이 끝난 글에만 있고, 그 밖에는 null
	// confirmedTotals: 확정한 합계 (웹의 REAL 줄). 확정하지 않았으면 null
	// foods: 음식 표의 줄. 음식마다 AI의 답(ai), 확정한 값(confirmed), 어떻게 했는지(change: NONE 확정 전, SAME AI와 같음,
	//   CHANGED 바꿈, REMOVED 이 음식 아님. REMOVED는 confirmed가 null)
	// confidenceLabel: AI가 얼마나 확신하는지 (높음, 보통, 낮음. 웹 화면과 같은 말이라 앱이 그대로 보여 준다)
	// comment: AI의 설명
	public record Analysis(
			AnalysisStatus status,
			Totals aiTotals,
			Totals confirmedTotals,
			List<FoodRow> foods,
			String confidenceLabel,
			String comment) {

		static Analysis of(FoodAnalysisView view) {
			// 분석이 끝나지 않은 글도 웹의 분석 결과에는 빈 합계(값이 모두 null)가 들어 있다. 앱에는 합계가 없다는 뜻으로 null을 보낸다
			Totals aiTotals = view.aiTotals() == null || view.aiTotals().kcal() == null ? null : view.aiTotals();
			return new Analysis(view.status(), aiTotals, view.confirmedTotals(), view.rows(), view.confidenceLabel(), view.comment());
		}
	}

	// analysis: 그 글의 분석 결과 (FoodAnalysisService.getView, 기록이 없으면 null)
	public static AppPostDetail from(Post post, FoodAnalysisView analysis) {
		boolean photoHeld = post.isPhotoHeld(analysis == null ? null : analysis.status());
		return new AppPostDetail(
				post.getId(),
				post.getNickname(),
				post.getMemo(),
				photoHeld ? null : "/images/" + post.getImageKey(),
				photoHeld,
				post.getCreatedAt().truncatedTo(ChronoUnit.SECONDS),
				post.getViewCount(),
				analysis == null ? null : Analysis.of(analysis));
	}
}
