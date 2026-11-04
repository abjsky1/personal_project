package backend.feedback.model.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import backend.feedback.model.entity.Feedback;
import backend.post.model.entity.PostStatus;

// 피드백을 DB에서 읽고 쓰는 Repository (PostRepository와 같은 방식)
public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

	// 목록: 최신순(번호 큰 순), cursor보다 작은 번호부터 limit개 (게시글 목록과 같은 커서 방식)
	List<Feedback> findByStatusAndIdLessThanOrderByIdDesc(PostStatus status, Long cursor, Limit limit);

	// 상세·삭제: ACTIVE인 것만
	Optional<Feedback> findByIdAndStatus(Long id, PostStatus status);
}
