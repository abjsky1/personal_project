package backend.feedback.model.entity;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import backend.post.model.entity.PostStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

// 운영자 답변 엔티티: DB의 feedback_replies 테이블과 짝을 이룬다 (JM-77)
// 전에는 답변이 feedbacks.admin_reply 한 칸이라서, 답변을 다시 달면 앞의 답변이 덮어써져 사라졌다.
// 이제 답변 하나가 이 테이블의 한 줄이다. 피드백 하나에 답변이 달린 순서대로 여러 줄 쌓인다.
// 인덱스 (feedback_id, status, id): 상세 화면의 "이 피드백의 살아 있는 답변을 달린 순서대로"를 빠르게 읽는다.
@Entity
@Table(name = "feedback_replies",
		indexes = @Index(name = "idx_feedback_replies_feedback_status_id", columnList = "feedback_id, status, id"))
public class FeedbackReply {

	// 번호가 곧 달린 순서다. 화면에는 번호가 작은 것부터(먼저 단 답변부터) 보여 준다
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// 어느 피드백의 답변인지. 댓글(Comment.postId)과 같이 객체로 잇지 않고 번호만 둔다
	@Column(nullable = false)
	private Long feedbackId;

	@Column(nullable = false, length = 1000)
	private String content;

	// 잘못 단 답변을 지울 때도 다른 삭제와 같이 상태만 바꾼다 (ACTIVE → DELETED_BY_ADMIN, 기획서 5.1절)
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 30)
	private PostStatus status;

	@Column(nullable = false)
	private Instant createdAt;

	private Instant deletedAt;

	protected FeedbackReply() {
	}

	// 지금 다는 답변
	public FeedbackReply(Long feedbackId, String content) {
		this(feedbackId, content, Instant.now());
	}

	// 쓴 시각을 정해서 만든다: 예전 답변을 옮길 때만 쓴다 (FeedbackReplyBackfill)
	public FeedbackReply(Long feedbackId, String content, Instant createdAt) {
		this.feedbackId = feedbackId;
		this.content = content;
		this.status = PostStatus.ACTIVE;
		this.createdAt = createdAt;
	}

	public void deleteByAdmin() {
		this.status = PostStatus.DELETED_BY_ADMIN;
		this.deletedAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public Long getFeedbackId() {
		return feedbackId;
	}

	public String getContent() {
		return content;
	}

	public PostStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}
}
