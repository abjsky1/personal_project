package backend.feedback.model.dto;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import backend.feedback.model.entity.Feedback;
import backend.feedback.model.entity.FeedbackProgress;
import backend.post.model.entity.PostStatus;

// 목록 화면에 피드백 하나를 보여 줄 때 필요한 값 (DTO)
// id: 글 번호. 목록의 맨 왼쪽에 보여 준다 (JM-76). 운영자가 답변을 달거나 확인할 때 이 번호로 찾는다
// progress: 처리 상태. JSP에서 ${feedback.progress.label}(글자)와 ${feedback.progress.cssClass}(배지 색)로 쓴다
// hasReply: 운영자 답변이 있는지 (목록에 "답변 있음" 표시)
// deleted: 삭제된 글인지 (JM-76). 삭제된 글도 목록에서 빼지 않고 번호와 "삭제된 피드백입니다."만 남긴다
//   → 글이 지워져도 다른 글의 번호와 자리가 당겨지지 않는다
public record FeedbackSummary(Long id, String title, FeedbackProgress progress, String nickname, String createdAt, boolean hasReply,
		boolean deleted) {

	static final DateTimeFormatter TIME_FORMAT =
			DateTimeFormatter.ofPattern("M월 d일 HH:mm").withZone(ZoneId.of("Asia/Seoul"));

	public static FeedbackSummary from(Feedback feedback) {
		// 삭제된 글(작성자가 지웠든 운영자가 지웠든): 번호만 넘긴다. 제목, 닉네임, 시간은 화면으로 보내지 않는다
		if (feedback.getStatus() != PostStatus.ACTIVE) {
			return new FeedbackSummary(feedback.getId(), null, null, null, null, false, true);
		}
		return new FeedbackSummary(
				feedback.getId(),
				feedback.getTitle(),
				feedback.getProgress(),
				feedback.getNickname(),
				TIME_FORMAT.format(feedback.getCreatedAt()),
				feedback.getAdminReply() != null,
				false);
	}
}
