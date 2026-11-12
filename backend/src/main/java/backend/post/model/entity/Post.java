package backend.post.model.entity;

import java.time.Instant;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

// 게시글 엔티티: 이 클래스 하나가 DB의 posts 테이블 하나와 짝을 이룬다.
// ddl-auto=update 설정 덕분에, 서버가 켜질 때 JPA가 이 클래스를 보고 posts 테이블을 자동으로 만든다.
// 필드 이름은 자동으로 밑줄 이름의 컬럼이 된다. (passwordHash → password_hash, createdAt → created_at)
//
// @Entity: JPA가 관리하는 클래스라는 표시
// @Table: 테이블 이름과 인덱스를 정한다.
// 인덱스 (status, id): 목록 조회("ACTIVE인 글을 id 큰 순서로 20개")를 빠르게 하려고 만든다.
@Entity
@Table(name = "posts", indexes = @Index(name = "idx_posts_status_id", columnList = "status, id"))
public class Post {

	// @Id: 기본 키(PK) / IDENTITY: 번호는 MySQL의 AUTO_INCREMENT가 1, 2, 3...으로 매긴다.
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// nullable = false → NOT NULL, length = 20 → VARCHAR(20)
	@Column(nullable = false, length = 20)
	private String nickname;

	// 비밀번호 원문은 저장하지 않고, BCrypt로 바꾼 값(해시)만 저장한다. 해시에서 원래 비밀번호를 되돌릴 수 없다.
	@Column(nullable = false, length = 100)
	private String passwordHash;

	// 메모는 선택이라 비어 있을 수 있다 (nullable 기본값이 true)
	@Column(length = 300)
	private String memo;

	// 저장한 사진 파일의 이름 (사진 파일 자체는 DB가 아니라 uploads 폴더에 있다)
	// imageKey: 긴 변 1280px 사진 (상세 화면) / thumbnailKey: 600 × 600 썸네일 (목록)
	@Column(nullable = false)
	private String imageKey;

	@Column(nullable = false)
	private String thumbnailKey;

	// 사진을 찍은 시각 (EXIF). 위치 제공에 동의했고 사진에 정보가 있을 때만 채워진다. 식사 시간대 분석용
	private Instant photoTakenAt;

	// @Enumerated(STRING): enum을 숫자(0, 1, 2) 대신 이름("ACTIVE")으로 저장한다.
	// 숫자로 저장하면 나중에 enum 순서를 바꿨을 때 기존 데이터의 의미가 바뀌어 버린다.
	// @JdbcTypeCode(VARCHAR): 컬럼을 MySQL의 ENUM 타입이 아닌 VARCHAR로 만든다.
	// 그냥 두면 ENUM('ACTIVE', ...)으로 만들어지는데, 나중에 상태를 하나 추가하면 ddl-auto=update가 기존 컬럼을 고치지 않아서 저장이 실패한다.
	// (같은 이유로 CHECK 제약도 만들지 않는다: NoColumnCheckMySQLDialect)
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 30)
	private PostStatus status;

	// Instant: 시간대와 상관없는 "한 시점". DB에는 UTC(세계 표준시)로 저장하고, 화면에 보여 줄 때 한국 시간으로 바꾼다.
	@Column(nullable = false)
	private Instant createdAt;

	// 삭제(상태 변경)된 시각. 삭제 전에는 비어 있다(null).
	private Instant deletedAt;

	// 좋아요 수와 조회수 (JM-59, JM-72). 숫자만 둔다: 가입이 없는 서비스라 누가 눌렀는지, 누가 봤는지는 저장하지 않는다.
	// 값을 올리는 것은 이 클래스가 아니라 PostRepository의 UPDATE 한 줄이 한다 (여러 명이 동시에 눌러도 숫자를 잃지 않게).
	// @ColumnDefault("0"): 컬럼을 만들 때 DEFAULT 0을 붙인다. 이미 글이 있는 테이블에 NOT NULL 컬럼을 더하려면
	// 예전 글에 채울 값이 있어야 하는데, 이 기본값 덕분에 ddl-auto=update가 컬럼을 더할 때 예전 글들이 0으로 채워진다.
	@ColumnDefault("0")
	@Column(nullable = false)
	private int likeCount;

	@ColumnDefault("0")
	@Column(nullable = false)
	private int viewCount;

	// JPA는 DB에서 읽은 값을 채울 때 빈 객체부터 만들기 때문에, 매개변수 없는 생성자가 꼭 있어야 한다.
	// protected: 우리 코드에서는 실수로 빈 게시글을 만들지 못하게 막는다.
	protected Post() {
	}

	// 새 글을 만들 때 쓰는 생성자. 상태와 작성 시각은 여기서 정해서, 빠뜨릴 수 없게 한다.
	public Post(String nickname, String passwordHash, String memo, String imageKey, String thumbnailKey, Instant photoTakenAt) {
		this.nickname = nickname;
		this.passwordHash = passwordHash;
		this.memo = memo;
		this.imageKey = imageKey;
		this.thumbnailKey = thumbnailKey;
		this.photoTakenAt = photoTakenAt;
		this.status = PostStatus.ACTIVE;
		this.createdAt = Instant.now();
	}

	// 작성자 삭제: DB에서 지우지 않고 상태만 바꾼다. (기획서 5.1절)
	// 엔티티의 값만 바꾸면, @Transactional이 끝날 때 JPA가 바뀐 것을 알아채고 UPDATE를 실행한다 (변경 감지).
	// → save()를 따로 부르지 않아도 된다.
	public void deleteByAuthor() {
		this.status = PostStatus.DELETED_BY_AUTHOR;
		this.deletedAt = Instant.now();
	}

	// 운영자 삭제 (관리자 API). 누가 지웠는지 상태로 구분된다.
	public void deleteByAdmin() {
		this.status = PostStatus.DELETED_BY_ADMIN;
		this.deletedAt = Instant.now();
	}

	// 메모 수정 (JM-53, 지인 피드백 7번): 작성자가 비밀번호를 확인받고 메모를 고친다. null이면 메모 없음.
	// 고칠 수 있는 값은 메모뿐이다 (운영자 결정). 사진, 닉네임, 작성 시각은 바꾸는 메서드를 두지 않는다.
	public void changeMemo(String memo) {
		this.memo = memo;
	}

	// 값을 꺼내는 메서드(getter)만 두고 값을 바꾸는 메서드(setter)는 두지 않는다.
	// → 값을 바꾸는 일은 위의 deleteByAuthor(), changeMemo()처럼 이름이 분명한 메서드로만 하게 한다.
	public Long getId() {
		return id;
	}

	public String getNickname() {
		return nickname;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public String getMemo() {
		return memo;
	}

	public String getImageKey() {
		return imageKey;
	}

	public String getThumbnailKey() {
		return thumbnailKey;
	}

	public Instant getPhotoTakenAt() {
		return photoTakenAt;
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

	public int getLikeCount() {
		return likeCount;
	}

	public int getViewCount() {
		return viewCount;
	}
}
