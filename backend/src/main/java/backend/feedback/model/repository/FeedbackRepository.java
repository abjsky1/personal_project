package backend.feedback.model.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import backend.feedback.model.entity.Feedback;
import backend.post.model.entity.PostStatus;

// 피드백을 DB에서 읽고 쓰는 Repository (PostRepository와 같은 방식)
public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

	// 목록: 쪽 번호 방식 (게시글 목록과 같은 방식, JM-57). Pageable에 쪽 번호, 개수, 정렬(최신순)을 담아 넘긴다
	Page<Feedback> findByStatus(PostStatus status, Pageable pageable);

	// 상세, 삭제: ACTIVE인 것만
	Optional<Feedback> findByIdAndStatus(Long id, PostStatus status);
}
