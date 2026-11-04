package backend.admin.dto;

import backend.feedback.model.entity.FeedbackProgress;
import jakarta.validation.constraints.Size;

// 피드백 처리 API(PATCH /api/admin/feedback/{id})의 요청 본문(JSON)
// 둘 다 선택이라 하나만 보내도 된다. 예: {"progress": "DONE"} / {"reply": "반영했어요"} / 둘 다
// progress에 없는 값(예: "FINISHED")을 보내면 JSON을 읽는 단계에서 400이 난다.
public record AdminFeedbackUpdateRequest(
		FeedbackProgress progress,
		@Size(max = 1000, message = "답변은 1,000자까지 쓸 수 있어요.") String reply) {
}
