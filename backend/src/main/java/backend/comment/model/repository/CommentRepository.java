package backend.comment.model.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import backend.comment.model.dto.CommentCount;
import backend.comment.model.entity.Comment;
import backend.post.model.entity.PostStatus;

// 댓글을 DB에서 읽고 쓰는 Repository (PostRepository와 같은 방식: 메서드 이름으로 SQL을 만든다)
public interface CommentRepository extends JpaRepository<Comment, Long> {

	// 한 글의 댓글을 오래된 순으로 가져온다 (기획서 5절: 댓글은 오래된 순, 20개씩 커서 페이징)
	// → SELECT ... FROM comments WHERE post_id = ? AND status = ? AND id > ? ORDER BY id ASC LIMIT ?
	// 목록(최신순, id < cursor)과 반대로, 지금 화면의 마지막 댓글 번호(cursor)보다 큰 번호를 이어서 가져온다.
	List<Comment> findByPostIdAndStatusAndIdGreaterThanOrderByIdAsc(Long postId, PostStatus status, Long cursor, Limit limit);

	// 삭제할 댓글 찾기: 번호·글 번호·상태가 모두 맞아야 한다 (다른 글의 댓글 번호를 넣어도 찾지 못하게)
	Optional<Comment> findByIdAndPostIdAndStatus(Long id, Long postId, PostStatus status);

	// 한 글의 댓글 수 (상세 화면의 "댓글 N개")
	long countByPostIdAndStatus(Long postId, PostStatus status);

	// 글 여러 개의 댓글 수를 한 번에 센다 (목록 카드의 "댓글 3"). 글마다 따로 세면 글 20개에 쿼리가 20번 나간다 (N+1).
	// @Query: 메서드 이름 규칙으로 만들기 어려운 쿼리는 직접 적는다 (JPQL: 테이블 대신 엔티티 이름을 쓰는 SQL 비슷한 문법)
	// 결과는 CommentCount(글 번호, 개수) 모양으로 받는다. as 뒤의 이름이 CommentCount의 getter 이름과 짝을 이룬다.
	@Query("select c.postId as postId, count(c) as count from Comment c"
			+ " where c.postId in :postIds and c.status = :status group by c.postId")
	List<CommentCount> countByPostIds(@Param("postIds") Collection<Long> postIds, @Param("status") PostStatus status);
}
