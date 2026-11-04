package backend.comment.model.dto;

// 글 번호별 댓글 수. CommentRepository.countByPostIds의 결과 한 줄을 담는다.
// interface로 두면 Spring Data가 쿼리 결과(select ... as postId, ... as count)를 이 모양으로 바로 만들어 준다 (프로젝션).
public interface CommentCount {

	Long getPostId();

	long getCount();
}
