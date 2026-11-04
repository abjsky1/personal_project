package backend.feedback.model.dto;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import backend.feedback.model.entity.Feedback;
import backend.feedback.model.entity.FeedbackProgress;

// 목록 화면에 피드백 하나를 보여 줄 때 필요한 값 (DTO)
// progress: 처리 상태. JSP에서 ${feedback.progress.label}(글자)와 ${feedback.progress.cssClass}(배지 색)로 쓴다
// hasReply: 운영자 답변이 있는지 (목록에 "답변 있음" 표시)
public record FeedbackSummary(Long id, String title, FeedbackProgress progress, String nickname, String createdAt, boolean hasReply) {

	static final DateTimeFormatter TIME_FORMAT =
			DateTimeFormatter.ofPattern("M월 d일 HH:mm").withZone(ZoneId.of("Asia/Seoul"));

	public static FeedbackSummary from(Feedback feedback) {
		return new FeedbackSummary(
				feedback.getId(),
				feedback.getTitle(),
				feedback.getProgress(),
				feedback.getNickname(),
				TIME_FORMAT.format(feedback.getCreatedAt()),
				feedback.getAdminReply() != null);
	}
}
