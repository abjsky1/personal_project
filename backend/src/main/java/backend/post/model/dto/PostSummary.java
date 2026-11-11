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
// createdAt: "11월 11일 18:43" / createdShort: 카드의 좁은 자리에 넣는 짧은 날짜 (아래 shortWhen)
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
		String createdShort,
		AnalysisStatus analysisStatus,
		BigDecimal totalKcal,
		long commentCount,
		int likeCount,
		int viewCount) {

	private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

	// 작성 시각을 한국 시간으로 바꿔 "10월 29일 14:05" 모양으로 보여 준다.
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("M월 d일 HH:mm").withZone(KOREA);
	private static final DateTimeFormatter TODAY_FORMAT = DateTimeFormatter.ofPattern("HH:mm").withZone(KOREA);
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
				shortWhen(post.getCreatedAt(), LocalDate.now(KOREA)),
				analysis == null ? null : analysis.getStatus(),
				analysis == null ? null : analysis.getTotalKcal(),
				commentCount,
				post.getLikeCount(),
				post.getViewCount());
	}

	// 휴대폰 카드처럼 좁은 자리에 넣는 짧은 날짜 (JM-72).
	// 오늘 올린 글은 시각(18:43), 올해 글은 날짜(11.02), 그 전 글은 연도까지(25.12.31). today: 한국 날짜 기준의 오늘
	// 월과 일은 두 자리로 맞춘다: "11.2"는 소수처럼 읽힌다
	static String shortWhen(Instant createdAt, LocalDate today) {
		LocalDate day = createdAt.atZone(KOREA).toLocalDate();
		if (day.equals(today)) {
			return TODAY_FORMAT.format(createdAt);
		}
		return (day.getYear() == today.getYear() ? THIS_YEAR_FORMAT : OLDER_FORMAT).format(createdAt);
	}
}
