package backend.post.model.dto;

import java.util.List;

// 앱의 피드 한 쪽 (GET /api/posts?page=N, JM-90, 기획서 19.2)
// posts: 이 쪽의 글 (웹 피드와 같은 글, 같은 순서). 마지막 쪽 뒤의 번호면 빈 목록이다 → 앱은 빈 목록을 보고 그만 받는다
// page: 이 쪽의 번호 (1부터), totalPages: 전체 쪽 수 (글이 하나도 없으면 0)
public record AppFeedPage(List<AppPostCard> posts, int page, int totalPages) {
}
