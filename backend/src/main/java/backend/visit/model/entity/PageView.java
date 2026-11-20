package backend.visit.model.entity;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import backend.visit.model.vo.VisitEntry;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 사용자 흐름의 한 줄 (page_views 테이블, JM-92, 기획서 12절). 사람이 화면을 하나 받았거나 글을 올렸을 때 한 줄.
// 같은 방문의 줄은 visit_key가 같고 seq가 1, 2, 3 ... 으로 이어진다. 들어온 곳(app부터 click_id까지)은 방문의 첫 줄(seq 1)에만 있다.
// IP, 세션 번호, User-Agent 전체, 주소 전체, 올린 글의 번호는 둘 칸이 없다. 지우지 않고 모두 보관한다 (기획서 18절)
// 저장은 PageFlowRecorder가 INSERT 한 문장으로 한다. 이 클래스는 테이블 모양을 정하고(ddl-auto=update) 한 줄의 값을 나른다
// 상태와 종류 칸은 @JdbcTypeCode(VARCHAR)로 MySQL enum이 아닌 글자 칸으로 만든다: 나중에 화면을 더해도 저장이 거절되지 않는다
// (daily_region_counts와 같은 방식, docs/troubleshooting/2026-11-02-enum-check-constraint.md)
@Entity
@Table(name = "page_views",
		uniqueConstraints = @UniqueConstraint(name = "uk_page_views_visit_seq", columnNames = { "visit_key", "seq" }),
		indexes = @Index(name = "idx_page_views_viewed_at", columnList = "viewed_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PageView {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// 방문 번호: 세션에 붙여 둔 무작위 수 (PageFlowVisit)
	@Column(name = "visit_key", nullable = false)
	private long visitKey;

	// 방문 안에서 몇 번째 줄인지 (1부터)
	@Column(nullable = false)
	private int seq;

	@Column(name = "viewed_at", nullable = false)
	private Instant viewedAt;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(length = 20, nullable = false)
	private Screen screen;

	// 피드는 쪽 번호, 글 상세와 공지 글과 피드백 글은 그 글의 번호. 나머지는 비움
	private Long target;

	// 바로 앞 화면: 이 사이트 안에서 눌러 왔으면 그 화면 (Referer의 경로로 정한다). 밖에서 왔거나 모르면 비움
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "prev_screen", length = 20)
	private Screen prevScreen;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(length = 10, nullable = false)
	private Device device;

	// 여기부터 방문의 첫 줄에만 (VisitEntry)
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(length = 20)
	private ClientApp app;

	@Column(name = "ref_host", length = 100)
	private String refHost;

	@Column(name = "utm_source", length = 50)
	private String utmSource;

	@Column(name = "utm_medium", length = 50)
	private String utmMedium;

	@Column(name = "utm_campaign", length = 50)
	private String utmCampaign;

	@Column(name = "click_id", length = 10)
	private String clickId;

	// entry: 방문의 첫 줄이면 들어온 곳, 아니면 null
	public PageView(long visitKey, int seq, Screen screen, Long target, Screen prevScreen, Device device, VisitEntry entry) {
		this.visitKey = visitKey;
		this.seq = seq;
		this.viewedAt = Instant.now();
		this.screen = screen;
		this.target = target;
		this.prevScreen = prevScreen;
		this.device = device;
		if (entry != null) {
			this.app = entry.app();
			this.refHost = entry.refHost();
			this.utmSource = entry.utmSource();
			this.utmMedium = entry.utmMedium();
			this.utmCampaign = entry.utmCampaign();
			this.clickId = entry.clickId();
		}
	}
}
