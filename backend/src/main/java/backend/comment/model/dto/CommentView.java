package backend.comment.model.dto;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import backend.comment.model.entity.Comment;

// 화면에 댓글 하나를 보여 줄 때 필요한 값만 담는 DTO (비밀번호 해시 같은 값은 넘기지 않는다, PostSummary와 같은 이유)
// parentId: 답글이면 달린 댓글의 번호, 일반 댓글이면 null. replies: 이 댓글에 달린 답글들.
// deletedMessage: 지운 댓글이면 내용 대신 보여 줄 안내 글, 살아 있는 댓글이면 null.
public record CommentView(Long id, Long parentId, String nickname, String content, String createdAt,
		String deletedMessage, List<CommentView> replies) {

	private static final DateTimeFormatter TIME_FORMAT =
			DateTimeFormatter.ofPattern("M월 d일 HH:mm").withZone(ZoneId.of("Asia/Seoul"));

	public static CommentView from(Comment comment, List<CommentView> replies) {
		return switch (comment.getStatus()) {
			case ACTIVE -> new CommentView(comment.getId(), comment.getParentId(), comment.getNickname(), comment.getContent(),
					TIME_FORMAT.format(comment.getCreatedAt()), null, replies);
			case DELETED_BY_AUTHOR -> deleted(comment, "작성자에 의해 삭제된 댓글입니다.", replies);
			case DELETED_BY_ADMIN -> deleted(comment, "관리자에 의해 삭제된 댓글입니다.", replies);
		};
	}

	// 지운 댓글의 닉네임, 내용, 시간은 화면으로 넘기지 않는다. 답글이 남아 있어서 자리만 지킨다.
	private static CommentView deleted(Comment comment, String message, List<CommentView> replies) {
		return new CommentView(comment.getId(), comment.getParentId(), null, null, null, message, replies);
	}
}
