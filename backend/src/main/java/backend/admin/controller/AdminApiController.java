package backend.admin.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import backend.admin.dto.AdminDeleteResponse;
import backend.admin.dto.AdminFeedbackUpdateRequest;
import backend.comment.service.CommentService;
import backend.feedback.model.dto.FeedbackDetail;
import backend.feedback.service.FeedbackService;
import backend.post.model.entity.PostStatus;
import backend.post.service.PostService;
import backend.stats.model.dto.AdminStatsResponse;
import backend.stats.service.StatsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// 운영자 API: 화면 없이 JSON으로만 쓴다 (기획서 18절 결정). 쓰는 법은 기획서 16절 런북.
// 모든 요청은 AdminTokenInterceptor가 먼저 X-Admin-Token 헤더를 검사한다.
// 삭제는 모두 "상태 변경"이고(기획서 5.1절), 화면의 삭제와 같이 POST로 받는다.
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminApiController {

	private final PostService postService;
	private final CommentService commentService;
	private final FeedbackService feedbackService;
	private final StatsService statsService;

	// 게시글 운영자 삭제 (위치 정보는 작성자 삭제 때와 같이 완전히 지운다)
	@PostMapping("/posts/{id}/delete")
	public AdminDeleteResponse deletePost(@PathVariable Long id) {
		postService.deleteByAdmin(id);
		return new AdminDeleteResponse(id, PostStatus.DELETED_BY_ADMIN);
	}

	@PostMapping("/comments/{id}/delete")
	public AdminDeleteResponse deleteComment(@PathVariable Long id) {
		commentService.deleteByAdmin(id);
		return new AdminDeleteResponse(id, PostStatus.DELETED_BY_ADMIN);
	}

	@PostMapping("/feedback/{id}/delete")
	public AdminDeleteResponse deleteFeedback(@PathVariable Long id) {
		feedbackService.deleteByAdmin(id);
		return new AdminDeleteResponse(id, PostStatus.DELETED_BY_ADMIN);
	}

	// 피드백 처리 상태 바꾸기 / 답변 달기. 바뀐 뒤의 피드백 내용을 돌려준다.
	// @RequestBody: 요청 본문의 JSON을 객체로 읽는다. @Valid: 답변 길이 검사
	@PatchMapping("/feedback/{id}")
	public FeedbackDetail updateFeedback(@PathVariable Long id, @Valid @RequestBody AdminFeedbackUpdateRequest request) {
		return feedbackService.updateByAdmin(id, request.progress(), request.reply());
	}

	// 운영 통계 (JM-68). days: 오늘을 포함해 며칠을 볼지 (안 주면 7일, 1~90일)
	@GetMapping("/stats")
	public AdminStatsResponse stats(@RequestParam(defaultValue = "7") int days) {
		return statsService.getStats(days);
	}
}
