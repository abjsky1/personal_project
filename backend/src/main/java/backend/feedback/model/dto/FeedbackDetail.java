package backend.feedback.model.dto;

import backend.feedback.model.entity.Feedback;
import backend.feedback.model.entity.FeedbackProgress;

// 상세 화면용 값. adminReply·repliedAt은 운영자 답변이 없으면 null
public record FeedbackDetail(
		Long id,
		String title,
		String content,
		FeedbackProgress progress,
		String nickname,
		String createdAt,
		String adminReply,
		String repliedAt) {

	public static FeedbackDetail from(Feedback feedback) {
		return new FeedbackDetail(
				feedback.getId(),
				feedback.getTitle(),
				feedback.getContent(),
				feedback.getProgress(),
				feedback.getNickname(),
				FeedbackSummary.TIME_FORMAT.format(feedback.getCreatedAt()),
				feedback.getAdminReply(),
				feedback.getRepliedAt() == null ? null : FeedbackSummary.TIME_FORMAT.format(feedback.getRepliedAt()));
	}
}
