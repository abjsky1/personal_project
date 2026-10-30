package backend.post.model.dto;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import backend.post.model.entity.Post;

// 목록 화면에 글 하나를 보여 줄 때 필요한 값만 담는 객체 (DTO)
// 엔티티(Post)를 화면에 그대로 넘기지 않는 이유: 비밀번호 해시처럼 화면에 나가면 안 되는 값이 실수로 노출될 수 있다.
//
// record: 값을 담기만 하는 클래스를 짧게 만드는 문법. JSP에서는 ${post.nickname}처럼 꺼내 쓴다.
// imageUrl: 상세 화면용 큰 사진 / thumbnailUrl: 목록용 정사각형 썸네일
public record PostSummary(Long id, String nickname, String memo, String imageUrl, String thumbnailUrl, String createdAt) {

	// 작성 시각을 한국 시간으로 바꿔 "10월 29일 14:05" 모양으로 보여 준다.
	private static final DateTimeFormatter TIME_FORMAT =
			DateTimeFormatter.ofPattern("M월 d일 HH:mm").withZone(ZoneId.of("Asia/Seoul"));

	// Post 엔티티를 화면용 PostSummary로 바꾼다.
	public static PostSummary from(Post post) {
		return new PostSummary(
				post.getId(),
				post.getNickname(),
				post.getMemo(),
				// 사진 주소: /images/파일이름 → ImageController가 저장소(폴더 또는 S3)에서 꺼내 보내 준다.
				"/images/" + post.getImageKey(),
				"/images/" + post.getThumbnailKey(),
				TIME_FORMAT.format(post.getCreatedAt()));
	}
}
