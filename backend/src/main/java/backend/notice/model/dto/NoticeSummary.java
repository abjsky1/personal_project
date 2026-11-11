package backend.notice.model.dto;

// 공지 목록의 한 줄: [분류] 제목, 오른쪽에 조회수와 날짜("2026.11.11")
public record NoticeSummary(long id, String category, String title, String date, long views) {
}
