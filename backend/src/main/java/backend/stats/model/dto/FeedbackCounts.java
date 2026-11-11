package backend.stats.model.dto;

// 피드백의 처리 상태별 수 (삭제되지 않은 것만)
public record FeedbackCounts(long received, long inProgress, long done, long onHold) {
}
