package backend.feedback.model.dto;

import java.util.List;

// 피드백 목록 한 화면: 글 목록 + "더 보기" 위치 (FeedPage와 같은 방식, nextCursor가 null이면 더 볼 글이 없다)
public record FeedbackPage(List<FeedbackSummary> feedbacks, Long nextCursor) {
}
