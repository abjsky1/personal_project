package backend.post.model.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import backend.analysis.model.entity.AnalysisStatus;
import backend.analysis.model.entity.FoodAnalysis;
import backend.post.model.entity.Post;

// 목록 화면에 글 하나를 보여 줄 때 필요한 값만 담는 객체 (DTO)
// 엔티티(Post)를 화면에 그대로 넘기지 않는 이유: 비밀번호 해시처럼 화면에 나가면 안 되는 값이 실수로 노출될 수 있다.
//
// record: 값을 담기만 하는 클래스를 짧게 만드는 문법. JSP에서는 ${post.nickname}처럼 꺼내 쓴다.
// imageUrl: 상세 화면용 큰 사진 / thumbnailUrl: 목록용 정사각형 썸네일
// createdAt: 상세 화면의 "11월 11일 18:43" / createdDate: 목록 카드의 "2026.11.11" / createdShort: 휴대폰 카드의 짧은 날짜 (아래 shortWhen)
// analysisStatus, totalKcal: 목록 카드에 보여 줄 분석 상태와 칼로리. 분석 기록이 없으면 null
// commentCount: 목록 카드의 댓글 수 (상세 화면은 댓글 목록 쪽에서 따로 세므로 0으로 둔다)
// likeCount, viewCount: 좋아요 수와 조회수 (JM-59, JM-72)
public record PostSummary(
		Long id,
		String nickname,
		String memo,
		String imageUrl,
		String thumbnailUrl,
		String createdAt,
		String createdDate,
		String createdShort,
		AnalysisStatus analysisStatus,
		BigDecimal totalKcal,
		long commentCount,
		int likeCount,
		int viewCount) {

	private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

	// 작성 시각을 한국 시간으로 바꿔 "10월 29일 14:05" 모양으로 보여 준다. (상세 화면)
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("M월 d일 HH:mm").withZone(KOREA);
	// 목록 카드의 날짜 (JM-72): 연도까지 넣은 "2026.11.11". 공지 목록의 날짜와 같은 모양이다.
	// "2026년 11월 11일"은 카드에 넣기에 길어서 점으로 줄였다. 해가 바뀌어도 어느 해 글인지 알 수 있다 (운영자 요청)
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(KOREA);
	private static final DateTimeFormatter THIS_YEAR_FORMAT = DateTimeFormatter.ofPattern("MM.dd").withZone(KOREA);
	private static final DateTimeFormatter OLDER_FORMAT = DateTimeFormatter.ofPattern("yy.MM.dd").withZone(KOREA);

	// Post 엔티티를 화면용 PostSummary로 바꾼다. analysis: 그 글의 분석 기록 (없으면 null), commentCount: 댓글 수
	public static PostSummary from(Post post, FoodAnalysis analysis, long commentCount) {
		return new PostSummary(
				post.getId(),
				post.getNickname(),
				post.getMemo(),
				// 사진 주소: /images/파일이름 → ImageController가 저장소(폴더 또는 S3)에서 꺼내 보내 준다.
				"/images/" + post.getImageKey(),
				"/images/" + post.getThumbnailKey(),
				TIME_FORMAT.format(post.getCreatedAt()),
				DATE_FORMAT.format(post.getCreatedAt()),
				shortWhen(post.getCreatedAt(), LocalDate.now(KOREA)),
				analysis == null ? null : analysis.getStatus(),
				// 사용자가 고쳐서 확정했으면 확정한 값, 아니면 AI의 첫 답 (JM-74)
				analysis == null ? null : analysis.getShownTotalKcal(),
				commentCount,
				post.getLikeCount(),
				post.getViewCount());
	}

	// 휴대폰 카드처럼 좁은 자리에 넣는 짧은 날짜 (JM-72): "2026.11.11"이 들어가지 않는 자리에 쓴다.
	// 올해 글은 "11.02", 지난해까지의 글은 연도 두 자리를 붙여 "25.12.31". today: 한국 날짜 기준의 오늘
	// 월과 일은 두 자리로 맞춘다: "11.2"는 소수처럼 읽힌다
	static String shortWhen(Instant createdAt, LocalDate today) {
		LocalDate day = createdAt.atZone(KOREA).toLocalDate();
		return (day.getYear() == today.getYear() ? THIS_YEAR_FORMAT : OLDER_FORMAT).format(createdAt);
	}
}
