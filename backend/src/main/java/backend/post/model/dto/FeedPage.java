package backend.post.model.dto;

import java.util.List;

// 목록 화면 한 번에 보여 줄 내용: 글 목록 + "더 보기"를 누르면 이어서 볼 위치(nextCursor)
// nextCursor가 null이면 더 볼 글이 없다는 뜻이라 "더 보기" 링크를 숨긴다.
public record FeedPage(List<PostSummary> posts, Long nextCursor) {
}
