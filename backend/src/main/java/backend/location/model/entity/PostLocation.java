// 위치 도메인의 엔티티를 두는 패키지
package backend.location.model.entity;

import java.math.BigDecimal;
import java.time.Instant;

import backend.location.model.vo.GeoPoint;
import backend.post.model.entity.Post;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

// 게시글의 촬영 위치 (post_locations 테이블)
// 사용자가 "위치 제공"에 동의하고, 사진에 GPS 정보가 있을 때만 만들어진다. (기획서 6.2절)
// 게시글과 1:1이고, 게시글이 삭제되면 이 행은 상태 변경이 아니라 완전히 지운다. (동의 철회로 본다, 기획서 5.1절)
// 좌표는 화면·로그·공개 API 어디에도 내보내지 않는다.
@Entity
@Table(name = "post_locations")
public class PostLocation {

	// 기본 키 = 게시글 번호. 위치는 게시글 하나에 하나뿐이라 따로 번호를 만들지 않고 게시글 번호를 그대로 쓴다.
	@Id
	private Long postId;

	// @OneToOne: 게시글과 1:1 관계 / fetch = LAZY: 위치를 읽을 때 게시글까지 바로 읽어 오지는 않는다
	// @MapsId: 위의 postId 값을 이 게시글의 id에서 가져온다 → post_id 컬럼 하나가 기본 키이자 posts를 가리키는 외래 키가 된다
	@MapsId
	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "post_id")
	private Post post;

	// DECIMAL(9,6): 전체 9자리 중 소수점 아래 6자리 (예: 126.978012)
	@Column(nullable = false, precision = 9, scale = 6)
	private BigDecimal latitude;

	@Column(nullable = false, precision = 9, scale = 6)
	private BigDecimal longitude;

	// 위치를 어디서 얻었는지. 지금은 사진의 EXIF뿐이다. (나중에 브라우저 위치 기능을 쓰면 GEOLOCATION)
	@Column(nullable = false, length = 20)
	private String source;

	// 사용자가 동의한 시각
	@Column(nullable = false)
	private Instant consentedAt;

	protected PostLocation() {
	}

	public PostLocation(Post post, GeoPoint point, Instant consentedAt) {
		this.post = post;
		this.latitude = point.latitude();
		this.longitude = point.longitude();
		this.source = "EXIF";
		this.consentedAt = consentedAt;
	}

	public Long getPostId() {
		return postId;
	}

	// DB의 두 컬럼을 다시 GeoPoint(값 객체) 하나로 묶어서 돌려준다.
	public GeoPoint getPoint() {
		return new GeoPoint(latitude, longitude);
	}

	public String getSource() {
		return source;
	}

	public Instant getConsentedAt() {
		return consentedAt;
	}
}
