package backend.feedback.model.dto;

import java.util.List;

import backend.feedback.model.entity.Feedback;
import backend.feedback.model.entity.FeedbackProgress;

// 상세 화면용 값
// replies: 운영자 답변을 달린 순서대로 (JM-77). 답변이 없으면 빈 목록
public record FeedbackDetail(
		Long id,
		String title,
		String content,
		FeedbackProgress progress,
		String nickname,
		String createdAt,
		List<FeedbackReplyView> replies) {

	public static FeedbackDetail from(Feedback feedback, List<FeedbackReplyView> replies) {
		return new FeedbackDetail(
				feedback.getId(),
				feedback.getTitle(),
				feedback.getContent(),
				feedback.getProgress(),
				feedback.getNickname(),
				FeedbackSummary.TIME_FORMAT.format(feedback.getCreatedAt()),
				replies);
	}
}
