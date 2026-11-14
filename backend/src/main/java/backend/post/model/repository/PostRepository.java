package backend.post.model.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import backend.post.model.entity.Post;
import backend.post.model.entity.PostStatus;

// 게시글을 DB에서 읽고 쓰는 Repository
// JpaRepository<엔티티, 기본 키 타입>를 상속하면 save(), findById() 같은 기본 메서드를 Spring이 자동으로 만들어 준다.
// interface만 선언하면 실제 구현은 Spring이 실행할 때 만들어 넣는다.
public interface PostRepository extends JpaRepository<Post, Long> {

	// 메서드 이름을 규칙대로 지으면 Spring Data JPA가 이름을 읽고 SQL을 만들어 준다.
	// findBy Status + Pageable → SELECT ... FROM posts WHERE status = ? ORDER BY ? LIMIT ? OFFSET ?
	//
	// 목록 쪽 번호 방식 (JM-57): Pageable에 "몇 쪽, 한 쪽에 몇 개, 정렬"을 담아 넘기면 그 쪽만 가져온다.
	// Page로 받으면 전체 글 수(COUNT 쿼리 한 번 더)도 같이 와서 전체 쪽 수를 알 수 있다.
	// 처음엔 "더 보기" 커서 방식(id < 마지막 번호)이었는데, 화면에 "이전"이 없어 앞 쪽으로 돌아가기 어렵다는 의견으로 바꿨다.
	Page<Post> findByStatus(PostStatus status, Pageable pageable);

	// 어느 시각 이후에 올라온 글 수 (상태와 상관없이, 지운 글도 센다) → SELECT COUNT(*) FROM posts WHERE created_at >= ?
	// 업로드 하루 전체 상한에 쓴다 (UploadLimiter, JM-79). DB에서 세므로 서버를 다시 켜도 0으로 돌아가지 않는다
	long countByCreatedAtGreaterThanEqual(Instant since);

	// 번호와 상태가 모두 맞는 글 하나 → SELECT ... FROM posts WHERE id = ? AND status = ?
	// Optional: 결과가 없을 수도 있다는 표시. 없으면 비어 있는 Optional이 돌아온다 (null 대신)
	// 삭제된 글을 실수로 보여 주지 않도록, 상세, 삭제는 항상 이 메서드로 ACTIVE인 글만 찾는다.
	Optional<Post> findByIdAndStatus(Long id, PostStatus status);

	// 조회수 1 올리기 (JM-72) → UPDATE posts SET view_count = view_count + 1 WHERE id = ? AND status = ?
	// 글을 읽어서 +1 한 뒤 저장하면, 두 사람이 동시에 열었을 때 둘 다 같은 숫자를 읽고 같은 값으로 저장해서 하나를 잃는다.
	// UPDATE 한 번으로 DB가 직접 더하게 하면 잃지 않는다. (공지 조회수 NoticeViewRepository와 같은 방식)
	// @Modifying: 조회가 아니라 값을 바꾸는 쿼리라는 표시. 돌려주는 값은 바뀐 행 수 (글이 없거나 삭제됐으면 0)
	// clearAutomatically: 실행 뒤 JPA가 기억하고 있던 글을 잊게 한다 → 바로 다시 읽으면 올라간 숫자가 나온다
	@Modifying(clearAutomatically = true)
	@Query("update Post p set p.viewCount = p.viewCount + 1 where p.id = :id and p.status = :status")
	int incrementViewCount(@Param("id") Long id, @Param("status") PostStatus status);

	// 좋아요 1 올리기 (JM-59). 조회수와 같은 방식
	@Modifying(clearAutomatically = true)
	@Query("update Post p set p.likeCount = p.likeCount + 1 where p.id = :id and p.status = :status")
	int incrementLikeCount(@Param("id") Long id, @Param("status") PostStatus status);
}
