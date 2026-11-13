package backend.feedback.model.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import backend.feedback.model.entity.Feedback;
import backend.post.model.entity.PostStatus;

// 피드백을 DB에서 읽고 쓰는 Repository (PostRepository와 같은 방식)
public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

	// 목록은 따로 만든 메서드 없이 JpaRepository의 findAll(Pageable)을 쓴다 (JM-76).
	// 전에는 findByStatus로 ACTIVE인 글만 읽었는데, 삭제된 글도 번호와 자리를 지키려고 상태를 가리지 않고 번호순으로 읽는다.
	// 삭제된 글의 내용을 화면으로 넘기지 않는 일은 FeedbackSummary가 한다.

	// 상세, 삭제: ACTIVE인 것만
	Optional<Feedback> findByIdAndStatus(Long id, PostStatus status);

	// 예전 답변 옮기기용 (JM-77, FeedbackReplyBackfill):
	// 예전 칸(admin_reply)에 답변이 있는데 feedback_replies에는 그 글의 답변이 한 줄도 없는 글 = 아직 옮기지 않은 글
	@Query("select f from Feedback f where f.adminReply is not null"
			+ " and not exists (select r.id from FeedbackReply r where r.feedbackId = f.id) order by f.id asc")
	List<Feedback> findWithOldReplyNotMoved();
}
