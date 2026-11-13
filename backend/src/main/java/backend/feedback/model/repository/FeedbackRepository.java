package backend.feedback.model.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import backend.feedback.model.entity.Feedback;
import backend.post.model.entity.PostStatus;

// 피드백을 DB에서 읽고 쓰는 Repository (PostRepository와 같은 방식)
public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

	// 목록은 따로 만든 메서드 없이 JpaRepository의 findAll(Pageable)을 쓴다 (JM-76).
	// 전에는 findByStatus로 ACTIVE인 글만 읽었는데, 삭제된 글도 번호와 자리를 지키려고 상태를 가리지 않고 번호순으로 읽는다.
	// 삭제된 글의 내용을 화면으로 넘기지 않는 일은 FeedbackSummary가 한다.

	// 상세, 삭제: ACTIVE인 것만
	Optional<Feedback> findByIdAndStatus(Long id, PostStatus status);
}
