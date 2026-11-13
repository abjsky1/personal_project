package backend.feedback.model.dto;

import backend.feedback.model.entity.FeedbackReply;

// 상세 화면에 운영자 답변 하나를 보여 줄 때 필요한 값 (JM-77)
// id: 답변 번호. 잘못 단 답변을 지울 때 쓴다 (운영자 API). 글자로는 보이지 않고,
//   상세 화면의 그 답변 상자에 id="reply-번호"로 들어가며 운영자 API의 응답에도 실린다
public record FeedbackReplyView(Long id, String content, String createdAt) {

	public static FeedbackReplyView from(FeedbackReply reply) {
		return new FeedbackReplyView(reply.getId(), reply.getContent(), FeedbackSummary.TIME_FORMAT.format(reply.getCreatedAt()));
	}
}
