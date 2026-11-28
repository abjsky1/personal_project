package backend.post.model.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import backend.analysis.model.entity.AnalysisStatus;
import backend.analysis.model.entity.FoodAnalysis;
import backend.post.model.entity.Post;

// 앱의 피드 카드 하나 (JM-90, 기획서 19.2). 웹 피드 카드(PostSummary)와 같은 기준의 값을 JSON으로 보낸다
// thumbnailPath: 썸네일의 경로 (/images/파일이름). 앱이 자기가 부른 서버 주소를 앞에 붙인다.
//   사진을 가린 글(JM-80)은 null이고 photoHeld가 true다 → 앱은 사진 대신 "관리자가 사진을 검토 중입니다"를 보여 준다
// analysisStatus, totalKcal: 분석 상태와 보여 줄 칼로리 (확정했으면 확정한 값, 아니면 AI의 첫 답). 분석 기록이 없는 옛날 글은 둘 다 null
// createdAt: 올린 시각. JSON에는 UTC의 ISO-8601 글자로 나간다 (예: 2026-11-28T08:24:58Z). 앱이 휴대폰의 시간대로 바꿔 보여 준다.
//   화면에는 분까지만 보이므로 초 아래는 버린다
public record AppPostCard(
		Long id,
		String nickname,
		String thumbnailPath,
		boolean photoHeld,
		AnalysisStatus analysisStatus,
		BigDecimal totalKcal,
		Instant createdAt) {

	// analysis: 그 글의 분석 기록 (없으면 null)
	public static AppPostCard from(Post post, FoodAnalysis analysis) {
		AnalysisStatus status = analysis == null ? null : analysis.getStatus();
		boolean photoHeld = post.isPhotoHeld(status);
		return new AppPostCard(
				post.getId(),
				post.getNickname(),
				photoHeld ? null : "/images/" + post.getThumbnailKey(),
				photoHeld,
				status,
				analysis == null ? null : analysis.getShownTotalKcal(),
				post.getCreatedAt().truncatedTo(ChronoUnit.SECONDS));
	}
}
