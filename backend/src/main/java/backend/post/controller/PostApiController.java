package backend.post.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;
import backend.common.web.AppAgent;
import backend.post.model.dto.AppFeedPage;
import backend.post.model.dto.AppPostDetail;
import backend.post.service.PostService;
import lombok.RequiredArgsConstructor;

// 앱이 부르는 글 API (JM-90, 기획서 19.2). 웹 화면과 같은 글을 JSON으로 준다. 에러도 JSON이다 (ApiExceptionHandler)
// 로그인은 없다 (앱의 로그인은 회원제를 정할 때 함께 정한다). 읽기 둘과 앱 조회 기록 하나뿐이다.
// 웹의 숫자에는 섞이지 않는다 (기획서 19.3): 방문 수(JM-86)와 사용자 흐름(JM-92)은 /api/**를 세지 않고(VisitConfig),
// 웹 조회수(JM-72)는 HTML 상세 화면(PostController.detail)에서만 오른다
@RestController
@RequiredArgsConstructor
public class PostApiController {

	private final PostService postService;

	// 피드 한 쪽. ?page=N (없으면 1쪽, 숫자가 아니면 400). 마지막 쪽 뒤의 번호는 빈 목록이다
	@GetMapping("/api/posts")
	public AppFeedPage feed(@RequestParam(defaultValue = "1") int page) {
		return postService.getAppFeed(page);
	}

	// 글 하나. 없거나 지운 글이면 404 POST_NOT_FOUND
	@GetMapping("/api/posts/{id}")
	public AppPostDetail detail(@PathVariable Long id) {
		return postService.getAppDetail(id);
	}

	// 앱에서 글 상세를 한 번 열었다는 기록. 앱은 상세 화면을 처음 열 때만 부른다 (새로 고침은 세지 않는다)
	// 응답 내용은 없다(204). 이름표를 먼저 본다: 앱 이름표(User-Agent)가 없거나 모양이 다르면 글이 없어도 400 INVALID_APP_AGENT,
	// 이름표가 맞고 없거나 지운 글이면 404
	// 하루에 받는 기록 수에 상한이 있다 (AppViewDailyLimit, 사용자 흐름 기록과 같은 방식). 넘은 기록은 남기지 않고 응답은 그대로 204다.
	// 글마다의 횟수 제한(좋아요의 LikeRateLimiter)은 두지 않는다: 사용자에게 보이는 숫자가 아니라 운영자가 참고하는 기록이다
	@PostMapping("/api/posts/{id}/app-view")
	public ResponseEntity<Void> appView(@PathVariable Long id,
			@RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
		AppAgent agent = AppAgent.parse(userAgent).orElseThrow(() -> new BusinessException(ErrorCode.INVALID_APP_AGENT));
		postService.recordAppView(id, agent);
		return ResponseEntity.noContent().build();
	}
}
