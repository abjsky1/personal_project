package backend.post.model.repository;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import backend.post.model.entity.Post;
import backend.post.model.entity.PostStatus;

// 게시글을 DB에서 읽고 쓰는 Repository
// JpaRepository<엔티티, 기본 키 타입>를 상속하면 save(), findById() 같은 기본 메서드를 Spring이 자동으로 만들어 준다.
// interface만 선언하면 실제 구현은 Spring이 실행할 때 만들어 넣는다.
public interface PostRepository extends JpaRepository<Post, Long> {

	// 메서드 이름을 규칙대로 지으면 Spring Data JPA가 이름을 읽고 SQL을 만들어 준다.
	// findBy Status And IdLessThan OrderBy IdDesc
	// → SELECT ... FROM posts WHERE status = ? AND id < ? ORDER BY id DESC LIMIT ?
	//
	// 목록 "더 보기"(커서 방식): 지금 화면의 마지막 글 번호(cursor)보다 작은 번호의 글을 이어서 가져온다.
	// 페이지 번호(1, 2, 3쪽) 방식과 달리, 그사이 새 글이 올라와도 같은 글이 두 번 보이거나 빠지지 않는다.
	// Limit: 최대 몇 개를 가져올지
	List<Post> findByStatusAndIdLessThanOrderByIdDesc(PostStatus status, Long cursor, Limit limit);
}
