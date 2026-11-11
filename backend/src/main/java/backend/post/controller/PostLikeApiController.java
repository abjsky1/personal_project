package backend.post.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import backend.common.ratelimit.LikeRateLimiter;
import backend.post.model.dto.LikeResponse;
import backend.post.service.PostService;
import lombok.RequiredArgsConstructor;

// 좋아요 (JM-59, 피드백 9번). 화면을 새로 열지 않고 숫자만 바꾸려고 JSON으로 답한다 (에러도 JSON: ApiExceptionHandler)
@RestController
@RequiredArgsConstructor
public class PostLikeApiController {

	private final PostService postService;
	private final LikeRateLimiter likeRateLimiter;

	// 좋아요 1 올리기. 상세 화면의 JavaScript가 부른다 (detail.jsp).
	// 취소는 없다: 누가 눌렀는지 서버가 모르기 때문에, 취소를 받으면 남이 누른 것까지 누구나 뺄 수 있게 된다.
	// 없는 글, 삭제된 글이면 404, 한 글에 짧은 시간 동안 너무 많이 눌리면 429 (LikeRateLimiter)
	@PostMapping("/api/posts/{id}/like")
	public LikeResponse like(@PathVariable Long id) {
		likeRateLimiter.check(id);
		return new LikeResponse(postService.like(id));
	}
}
