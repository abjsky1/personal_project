package backend.post.model.entity;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import backend.common.web.AppAgent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 앱에서 글 상세를 연 기록 (app_post_views 테이블, JM-90, 기획서 19.3). 앱이 상세 화면을 처음 열 때마다 한 줄
// 웹 조회수(posts.view_count)와 섞지 않고 처음부터 따로 센다 (운영자 결정). 운영자의 개발 시험은 DEBUG 빌드라 빼고 셀 수 있다.
// 남기는 값은 글 번호, 플랫폼, 빌드, 앱 버전, 시각뿐이다. IP와 기기 번호는 남기지 않는다.
// 글 번호를 외래 키로 잇지 않는다: 기록 표라서 글이 어떻게 되든 줄은 그대로 둔다 (글은 지워도 상태만 바뀐다)
@Entity
@Table(name = "app_post_views", indexes = @Index(name = "idx_app_post_views_post_id", columnList = "post_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppPostView {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "post_id", nullable = false)
	private Long postId;

	// enum은 이름으로, 컬럼은 VARCHAR로 저장한다 (값을 더해도 저장이 실패하지 않게. Post.status와 같은 까닭)
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 10)
	private AppAgent.Platform platform;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 10)
	private AppAgent.Build build;

	// 앱 이름표의 버전 (AppAgent가 29자까지만 받는다)
	@Column(nullable = false, length = 30)
	private String appVersion;

	@Column(nullable = false)
	private Instant viewedAt;

	public AppPostView(Long postId, AppAgent agent) {
		this.postId = postId;
		this.platform = agent.platform();
		this.build = agent.build();
		this.appVersion = agent.appVersion();
		this.viewedAt = Instant.now();
	}
}
