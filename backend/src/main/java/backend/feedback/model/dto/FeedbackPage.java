package backend.feedback.model.dto;

import java.util.List;

import backend.common.web.PageNav;

// 피드백 목록 한 쪽: 글 목록 + 쪽 번호 줄 재료 (FeedPage와 같은 방식, JM-57)
public record FeedbackPage(List<FeedbackSummary> feedbacks, PageNav nav) {
}
