package backend.notice.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 공지 조회수 (notice_views 테이블, JM-65). 공지 글은 notices.json(git)에 있고, DB에는 공지 번호별 조회수만 둔다
@Entity
@Table(name = "notice_views")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NoticeView {

	// notices.json의 id
	@Id
	@Column(name = "notice_id")
	private Long noticeId;

	@Column(name = "view_count", nullable = false)
	private long viewCount;

	public NoticeView(Long noticeId, long viewCount) {
		this.noticeId = noticeId;
		this.viewCount = viewCount;
	}
}
