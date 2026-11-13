package backend.feedback.model.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import backend.feedback.model.entity.FeedbackReply;
import backend.post.model.entity.PostStatus;

// 운영자 답변을 DB에서 읽고 쓰는 Repository (JM-77)
public interface FeedbackReplyRepository extends JpaRepository<FeedbackReply, Long> {

	// 상세 화면: 한 피드백의 살아 있는 답변을 달린 순서대로 (번호가 작은 것이 먼저 단 답변)
	List<FeedbackReply> findByFeedbackIdAndStatusOrderByIdAsc(Long feedbackId, PostStatus status);

	// 목록 화면의 "답변 있음": 화면에 나온 글들 중 답변이 달린 글의 번호를 한 번에 읽는다 (글마다 따로 읽으면 N+1)
	// distinct: 답변이 여러 개인 글도 번호는 한 번만
	@Query("select distinct r.feedbackId from FeedbackReply r where r.feedbackId in :feedbackIds and r.status = :status")
	Set<Long> findRepliedFeedbackIds(@Param("feedbackIds") Collection<Long> feedbackIds, @Param("status") PostStatus status);

	// 운영자 삭제용: 답변 번호와 상태로 찾는다 (이미 지운 답변은 찾지 못한다)
	Optional<FeedbackReply> findByIdAndStatus(Long id, PostStatus status);
}
