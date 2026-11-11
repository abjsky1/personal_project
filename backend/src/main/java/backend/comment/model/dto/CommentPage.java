package backend.comment.model.dto;

import java.util.List;

// 상세 화면의 댓글 부분 한 번에 보여 줄 내용: 댓글 목록 + "더 보기"를 누르면 이어서 볼 위치(nextCursor) + 전체 댓글 수
// nextCursor가 null이면 더 볼 댓글이 없다 (FeedPage와 같은 방식). totalCount는 "댓글 N개" 제목에 쓴다 (답글 포함).
public record CommentPage(List<CommentView> comments, Long nextCursor, long totalCount) {
}
