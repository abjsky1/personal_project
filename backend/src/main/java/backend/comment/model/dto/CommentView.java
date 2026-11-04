package backend.comment.model.dto;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import backend.comment.model.entity.Comment;

// 화면에 댓글 하나를 보여 줄 때 필요한 값만 담는 DTO (비밀번호 해시 같은 값은 넘기지 않는다, PostSummary와 같은 이유)
public record CommentView(Long id, String nickname, String content, String createdAt) {

	private static final DateTimeFormatter TIME_FORMAT =
			DateTimeFormatter.ofPattern("M월 d일 HH:mm").withZone(ZoneId.of("Asia/Seoul"));

	public static CommentView from(Comment comment) {
		return new CommentView(
				comment.getId(),
				comment.getNickname(),
				comment.getContent(),
				TIME_FORMAT.format(comment.getCreatedAt()));
	}
}
