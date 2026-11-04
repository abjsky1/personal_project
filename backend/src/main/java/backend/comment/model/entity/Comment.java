// 댓글 도메인의 엔티티를 두는 패키지
package backend.comment.model.entity;

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

// 댓글 엔티티: DB의 comments 테이블과 짝을 이룬다. (서버가 켜질 때 JPA가 테이블을 만든다, Post와 같은 방식)
// 인덱스 (post_id, status, id): 상세 화면의 댓글 목록 조회("이 글의 ACTIVE 댓글을 오래된 순으로 20개")를 빠르게 한다.
@Entity
@Table(name = "comments", indexes = @Index(name = "idx_comments_post_status_id", columnList = "post_id, status, id"))
public class Comment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// 어느 글의 댓글인지. 글 객체(Post)를 통째로 잇지 않고 번호만 둔다.
	// 댓글을 보여 줄 때 글의 다른 정보는 필요 없고, 객체로 이으면 지연 로딩·N+1 같은 신경 쓸 거리만 늘기 때문이다.
	@Column(nullable = false)
	private Long postId;

	@Column(nullable = false, length = 20)
	private String nickname;

	// 비밀번호는 BCrypt 해시로만 저장한다 (Post와 같음)
	@Column(nullable = false, length = 100)
	private String passwordHash;

	@Column(nullable = false, length = 500)
	private String content;

	// 상태 값은 게시글과 같은 것을 쓴다 (ACTIVE / DELETED_BY_AUTHOR / DELETED_BY_ADMIN, 기획서 5.1절)
	// VARCHAR로 만들고 CHECK 제약을 두지 않는 이유는 Post.status와 같다.
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 30)
	private PostStatus status;

	@Column(nullable = false)
	private Instant createdAt;

	private Instant deletedAt;

	protected Comment() {
	}

	public Comment(Long postId, String nickname, String passwordHash, String content) {
		this.postId = postId;
		this.nickname = nickname;
		this.passwordHash = passwordHash;
		this.content = content;
		this.status = PostStatus.ACTIVE;
		this.createdAt = Instant.now();
	}

	// 작성자 삭제: DB에서 지우지 않고 상태만 바꾼다. (변경 감지로 UPDATE가 나간다, Post.deleteByAuthor와 같음)
	public void deleteByAuthor() {
		this.status = PostStatus.DELETED_BY_AUTHOR;
		this.deletedAt = Instant.now();
	}

	// 운영자 삭제 (관리자 API)
	public void deleteByAdmin() {
		this.status = PostStatus.DELETED_BY_ADMIN;
		this.deletedAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public Long getPostId() {
		return postId;
	}

	public String getNickname() {
		return nickname;
	}

	public String getPasswordHash() {
		return passwordHash;
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
