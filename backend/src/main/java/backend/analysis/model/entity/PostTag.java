package backend.analysis.model.entity;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

// 글의 음식 태그 한 줄 (post_tags 테이블, JM-83). 글 하나에 태그 여러 줄: (12, MEAL), (12, KOREAN), (12, RICE), (12, SPICY) ...
// 묶음마다 칸을 두지 않고 한 표에 한 줄씩 두는 이유: 묶음이나 값을 더하고 빼도 표를 고치지 않아도 되고,
// 피드에서 "이 태그를 가진 글"을 인덱스(tag, post_id)로 바로 찾을 수 있다 (② 화면의 필터).
// 글과 이어 두지(@ManyToOne) 않고 번호만 둔다: 읽을 때 글을 함께 불러올 일이 없고, 글은 지워도 상태만 바뀌어 남는다
// Persistable: 새로 만든 줄은 "새 줄"이라고 알려서, 저장할 때 Spring Data가 같은 키의 줄이 있는지 먼저 읽지(SELECT) 않고 바로 INSERT하게 한다.
// (기본 키를 직접 채우는 엔티티는 Spring Data가 새 줄인지 몰라서 줄마다 SELECT를 한 번씩 더 한다)
@Entity
@Table(name = "post_tags", indexes = @Index(name = "idx_post_tags_tag_post", columnList = "tag, post_id"))
@IdClass(PostTag.Key.class)
public class PostTag implements Persistable<PostTag.Key> {

	// 기본 키 = (글 번호, 태그). 같은 글에 같은 태그가 두 번 들어가지 않는다
	@Id
	@Column(name = "post_id")
	private Long postId;

	// @JdbcTypeCode(VARCHAR): 붙이지 않으면 Hibernate가 MySQL의 ENUM 칸을 만든다. ENUM 칸은 값을 더할 때 ddl-auto=update가 고치지 못해
	// 새 태그를 저장하지 못한다 (다른 enum 칸과 같은 규칙, S1에서 겪음. JM-83에서 처음 만든 표도 ENUM으로 생겨 다시 만들었다)
	@Id
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(length = 20)
	private FoodTag tag;

	@Column(nullable = false)
	private Instant createdAt;

	// 새로 만든 줄인지 (DB에서 읽었거나 저장한 뒤에는 false). 칸이 아니다 (@Transient)
	@Transient
	private boolean fresh = true;

	protected PostTag() {
	}

	@Override
	public Key getId() {
		return new Key(postId, tag);
	}

	@Override
	public boolean isNew() {
		return fresh;
	}

	// @PostLoad, @PostPersist: DB에서 읽은 뒤, 저장한 뒤에 JPA가 부른다
	@PostLoad
	@PostPersist
	void markStored() {
		this.fresh = false;
	}

	public PostTag(Long postId, FoodTag tag, Instant createdAt) {
		this.postId = postId;
		this.tag = tag;
		this.createdAt = createdAt;
	}

	public Long getPostId() {
		return postId;
	}

	public FoodTag getTag() {
		return tag;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	// 두 칸짜리 기본 키 (@IdClass). JPA가 같은 줄인지 비교할 때 equals와 hashCode를 쓴다
	public static class Key implements Serializable {

		private Long postId;
		private FoodTag tag;

		protected Key() {
		}

		public Key(Long postId, FoodTag tag) {
			this.postId = postId;
			this.tag = tag;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Key key && Objects.equals(postId, key.postId) && tag == key.tag;
		}

		@Override
		public int hashCode() {
			return Objects.hash(postId, tag);
		}
	}
}
