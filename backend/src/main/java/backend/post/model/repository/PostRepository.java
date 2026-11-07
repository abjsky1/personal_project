package backend.post.model.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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

	// 번호와 상태가 모두 맞는 글 하나 → SELECT ... FROM posts WHERE id = ? AND status = ?
	// Optional: 결과가 없을 수도 있다는 표시. 없으면 비어 있는 Optional이 돌아온다 (null 대신)
	// 삭제된 글을 실수로 보여 주지 않도록, 상세·삭제는 항상 이 메서드로 ACTIVE인 글만 찾는다.
	Optional<Post> findByIdAndStatus(Long id, PostStatus status);
}
