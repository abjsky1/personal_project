package backend.post.service;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import backend.analysis.model.entity.AnalysisStatus;
import backend.post.model.entity.Post;
import backend.post.model.entity.PostStatus;
import lombok.RequiredArgsConstructor;

// 사진 주소(/images/파일이름)로 이 사진을 내줘도 되는지 정한다 (JM-95)
// 사이트에 보이는 글의 사진만 내준다. 아래 셋은 내주지 않는다 (ImageController가 404):
// - 지운 글(작성자 삭제, 운영자 삭제)의 사진. 글 화면이 404인데 사진만 열리면 안 된다
// - 사진을 가린 글(JM-80, Post.isPhotoHeld)의 사진. 피드와 글 화면이 가린 사진을 주소로는 열 수 있었다
// - 어느 글의 사진도 아닌 파일
// 운영자는 관리자 화면에서 /admin/images/파일이름 으로 본다 (ImageController)
//
// 사진 요청마다 부르므로 가볍게 한다
// - JdbcTemplate으로 한 문장만 보낸다 (트랜잭션 없이, autocommit). 트랜잭션을 열면 여닫는 문장이 더 간다 (PageFlowRecorder와 같은 까닭)
// - 본 사진 이름과 썸네일 이름에 색인이 있다 (Post의 @Table). MySQL은 두 색인을 함께 쓴다 (index_merge)
// - 글을 엔티티로 읽지 않고 정하는 데 필요한 세 값만 읽는다
@Component
@RequiredArgsConstructor
public class PhotoVisibility {

	// 분석 기록은 글과 번호가 같다 (food_analyses.post_id). 분석 기록이 없는 옛날 글도 찾게 LEFT JOIN
	// 사진 이름은 저장할 때마다 새로 만든 무작위 이름(UUID)이라 한 글에만 있다 → 한 줄만 읽는다
	private static final String FIND = "SELECT p.status, p.photo_approved_at, a.status FROM posts p"
			+ " LEFT JOIN food_analyses a ON a.post_id = p.id WHERE p.image_key = ? OR p.thumbnail_key = ? LIMIT 1";

	private final JdbcTemplate jdbcTemplate;

	// key: 사진 파일 이름 (모양은 ImageController가 먼저 확인한다). 본 사진이든 썸네일이든 같은 글의 기준을 따른다
	public boolean isPublic(String key) {
		List<Boolean> found = jdbcTemplate.query(FIND,
				(row, rowNum) -> isPublic(row.getString(1), row.getTimestamp(2), row.getString(3)), key, key);
		return !found.isEmpty() && found.get(0);
	}

	// 글 상태, 운영자가 사진을 올린 시각(없으면 null), 분석 상태(분석 기록이 없으면 null)로 정한다
	static boolean isPublic(String postStatus, Timestamp photoApprovedAt, String analysisStatus) {
		if (!PostStatus.ACTIVE.name().equals(postStatus)) {
			return false;
		}
		AnalysisStatus analysis = analysisStatus == null ? null : AnalysisStatus.valueOf(analysisStatus);
		return !Post.isPhotoHeld(analysis, photoApprovedAt == null ? null : photoApprovedAt.toInstant());
	}
}
