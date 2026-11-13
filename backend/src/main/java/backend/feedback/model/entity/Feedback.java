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

// 피드백 엔티티: DB의 feedbacks 테이블과 짝을 이룬다 (Post, Comment와 같은 방식, 서버가 켜질 때 JPA가 테이블을 만든다)
// 인덱스 (status, id): 처음에는 목록 조회("ACTIVE인 피드백을 최신순으로 20개")용이었다.
// JM-76부터 목록은 삭제된 글의 번호와 자리도 지키려고 상태를 가리지 않고 번호(기본 키) 순으로 읽는다. 인덱스는 그대로 둔다
@Entity
@Table(name = "feedbacks", indexes = @Index(name = "idx_feedbacks_status_id", columnList = "status, id"))
public class Feedback {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 20)
	private String nickname;

	@Column(nullable = false, length = 100)
	private String passwordHash;

	@Column(nullable = false, length = 100)
	private String title;

	@Column(nullable = false, length = 2000)
	private String content;

	// 삭제 여부 (게시글, 댓글과 같은 상태 값)
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 30)
	private PostStatus status;

	// 운영자의 처리 상태 (접수 → 진행 중 → 반영 완료 / 보류)
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 20)
	private FeedbackProgress progress;

	// JM-77 전의 운영자 답변 칸 (답변을 하나만 담을 수 있어서, 다시 달면 앞의 답변이 덮어써졌다).
	// 이제는 여기에 쓰지 않는다: 답변은 FeedbackReply(feedback_replies 테이블)에 한 줄씩 쌓인다.
	// 예전 답변을 새 테이블로 옮길 때만 읽는다 (FeedbackReplyBackfill). 옮겨진 것을 확인한 뒤에는 이 두 칸을 지워도 된다
	@Column(length = 1000)
	private String adminReply;

	private Instant repliedAt;

	@Column(nullable = false)
	private Instant createdAt;

	private Instant deletedAt;

	protected Feedback() {
	}

	public Feedback(String nickname, String passwordHash, String title, String content) {
		this.nickname = nickname;
		this.passwordHash = passwordHash;
		this.title = title;
		this.content = content;
		this.status = PostStatus.ACTIVE;
		this.progress = FeedbackProgress.RECEIVED;
		this.createdAt = Instant.now();
	}

	// 작성자 삭제: 상태만 바꾼다 (기획서 5.1절)
	public void deleteByAuthor() {
		this.status = PostStatus.DELETED_BY_AUTHOR;
		this.deletedAt = Instant.now();
	}

	// ---- 아래는 운영자용. 운영자 API(AdminApiController)가 부른다 ----

	public void deleteByAdmin() {
		this.status = PostStatus.DELETED_BY_ADMIN;
		this.deletedAt = Instant.now();
	}

	public void changeProgress(FeedbackProgress progress) {
		this.progress = progress;
	}

	public Long getId() {
		return id;
	}

	public String getNickname() {
		return nickname;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public String getTitle() {
		return title;
	}

	public String getContent() {
		return content;
	}

	public PostStatus getStatus() {
		return status;
	}

	public FeedbackProgress getProgress() {
		return progress;
	}

	public String getAdminReply() {
		return adminReply;
	}

	public Instant getRepliedAt() {
		return repliedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}
}
