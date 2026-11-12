package backend.post.controller;

import java.net.URI;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import backend.analysis.service.FoodAnalysisService;
import backend.comment.model.dto.CommentCreateForm;
import backend.comment.service.CommentService;
import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;
import backend.common.web.Visitors;
import backend.post.model.dto.PostCreateForm;
import backend.post.model.dto.PostMemoForm;
import backend.post.model.dto.PostSummary;
import backend.post.service.PostService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// 게시글 화면을 담당하는 Controller
// @Controller: 메서드가 돌려준 문자열을 JSP 이름으로 보고 그 화면을 보여 준다.
// (앞뒤에 prefix, suffix가 붙는다: "posts/list" → /WEB-INF/views/posts/list.jsp)
// @RequiredArgsConstructor(Lombok): final 필드(postService)를 받는 생성자를 자동으로 만들어, Spring이 PostService를 넣어 준다.
@Controller
@RequiredArgsConstructor
public class PostController {

	private final PostService postService;
	private final FoodAnalysisService foodAnalysisService;
	private final CommentService commentService;

	// 첫 화면 = 게시글 목록 (쪽 번호 방식, JM-57)
	// @RequestParam(defaultValue = "1"): 주소의 ?page=값 을 받는다. 없으면 1쪽. 숫자가 아니면 400 에러 화면
	// Model: JSP로 넘겨줄 값을 담는 상자. 여기서 넣은 "feed"를 JSP에서 ${feed}로 꺼낸다.
	@GetMapping("/")
	public String list(@RequestParam(defaultValue = "1") int page, Model model) {
		model.addAttribute("feed", postService.getFeed(page));
		return "posts/list";
	}

	// 글쓰기 화면
	// 빈 form 객체를 넘겨 두면, JSP의 입력칸들이 이 객체와 연결된다.
	@GetMapping("/posts/new")
	public String newForm(Model model) {
		model.addAttribute("form", new PostCreateForm());
		return "posts/new";
	}

	// 글쓰기 form 제출
	// @ModelAttribute("form"): form에서 보낸 값을 PostCreateForm에 채운다. ("form"은 JSP에서 쓰는 이름)
	// @Valid: PostCreateForm에 적어 둔 규칙(@NotBlank, @Size)으로 검사한다.
	// BindingResult: 검사 결과(어긋난 칸과 메시지)를 담는다. 반드시 @Valid 붙은 값 바로 뒤에 둬야 한다.
	@PostMapping("/posts")
	public String create(@Valid @ModelAttribute("form") PostCreateForm form, BindingResult bindingResult) {
		validateImage(form.getImage(), bindingResult);

		// 어긋난 칸이 있으면 저장하지 않고 글쓰기 화면을 다시 보여 준다. (입력한 닉네임, 메모는 그대로 남고, 각 칸 아래에 메시지가 나온다)
		// 사진 칸은 브라우저 보안 규칙 때문에 다시 채울 수 없어서 사진은 다시 골라야 한다.
		if (bindingResult.hasErrors()) {
			return "posts/new";
		}

		Long postId;
		try {
			postId = postService.create(form);
		} catch (BusinessException e) {
			// 사진 검사에 걸린 경우(진짜 사진이 아님, 해상도가 너무 큼)는 에러 화면 대신 글쓰기 화면의 사진 칸 아래에 메시지를 보여 준다.
			// 그 밖의 에러는 그대로 던져서 에러 화면으로 보낸다.
			ErrorCode code = e.getErrorCode();
			if (code != ErrorCode.INVALID_IMAGE && code != ErrorCode.IMAGE_TOO_LARGE) {
				throw e;
			}
			bindingResult.rejectValue("image", code.name(), code.getMessage());
			return "posts/new";
		}

		// redirect: 저장 후 방금 올린 글의 상세 주소로 이동하라고 브라우저에 알린다. 거기서 분석 결과가 뜨는 것을 바로 볼 수 있다.
		// 화면을 바로 보여 주지 않고 이동시키는 이유: 새로고침을 눌렀을 때 같은 글이 한 번 더 저장되는 것을 막는다.
		return "redirect:/posts/" + postId;
	}

	// 상세 화면
	// @PathVariable: 주소 안의 {id} 자리 값을 받는다. (/posts/3 → id = 3)
	// 없거나 삭제된 글이면 Service가 POST_NOT_FOUND 예외를 던지고, PageExceptionHandler가 에러 화면(404)을 보여 준다.
	// commentCursor: 댓글 "더 보기"를 눌렀을 때 이어서 볼 위치 (없으면 첫 댓글부터)
	// @RequestHeader: 요청 머리말의 값을 받는다. User-Agent = 브라우저 종류, Referer = 바로 앞 화면의 주소 (둘 다 없을 수 있다)
	// → 이번에 연 것을 조회수로 셀지 정하는 데 쓴다 (아래 countsAsView, JM-72)
	@GetMapping("/posts/{id}")
	public String detail(@PathVariable Long id, @RequestParam(required = false) Long commentCursor,
			@RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
			@RequestHeader(value = HttpHeaders.REFERER, required = false) String referer, Model model) {
		model.addAttribute("post", postService.getDetail(id, countsAsView(id, userAgent, referer)));
		model.addAttribute("analysis", foodAnalysisService.getView(id));
		model.addAttribute("comments", commentService.getComments(id, commentCursor));
		// 댓글 입력칸과 연결할 빈 form. 검사에 걸려 돌아온 경우에는 CommentController가 flash로 보낸 입력값이 이미 들어 있어서 덮어쓰지 않는다.
		if (!model.containsAttribute("commentForm")) {
			model.addAttribute("commentForm", new CommentCreateForm());
		}
		return "posts/detail";
	}

	// 메모 수정 화면 (JM-53, 지인 피드백 7번). 고칠 수 있는 것은 메모뿐이다.
	// 지금 메모를 칸에 채워서 보여 준다. 비밀번호는 여기서 묻지 않고 저장할 때 확인한다:
	// 메모는 상세 화면에서 누구나 보는 값이라 이 화면을 여는 것만으로 새로 드러나는 것이 없다.
	// 이 화면을 여는 것은 조회수로 세지 않는다 (getDetail의 둘째 값 false).
	@GetMapping("/posts/{id}/edit")
	public String editForm(@PathVariable Long id, Model model) {
		PostSummary post = postService.getDetail(id, false);
		PostMemoForm form = new PostMemoForm();
		form.setMemo(post.memo());
		model.addAttribute("post", post);
		model.addAttribute("form", form);
		return "posts/edit";
	}

	// 메모 수정 form 제출
	// 메모가 너무 길거나 비밀번호가 틀리면 저장하지 않고 수정 화면을 다시 보여 준다. (고쳐 쓰던 메모는 칸에 그대로 남는다)
	@PostMapping("/posts/{id}/edit")
	public String edit(@PathVariable Long id, @Valid @ModelAttribute("form") PostMemoForm form, BindingResult bindingResult,
			Model model) {
		if (!bindingResult.hasErrors()) {
			try {
				postService.updateMemo(id, form.getMemo(), form.getPassword());
				// 저장 후에는 그 글의 상세로 이동한다. (화면을 바로 보여 주면 새로고침할 때 같은 요청이 한 번 더 간다)
				return "redirect:/posts/" + id;
			} catch (BusinessException e) {
				// 비밀번호가 틀린 경우만 비밀번호 칸 아래에 메시지를 보여 주고, 다른 에러(글 없음 등)는 그대로 던져서 에러 화면으로 보낸다.
				if (e.getErrorCode() != ErrorCode.INVALID_PASSWORD) {
					throw e;
				}
				bindingResult.rejectValue("password", e.getErrorCode().name(), e.getErrorCode().getMessage());
			}
		}
		// 수정 화면 위쪽에 보여 줄 글(사진, 닉네임). 없거나 지운 글이면 여기서 에러 화면(404)으로 간다.
		model.addAttribute("post", postService.getDetail(id, false));
		return "posts/edit";
	}

	// 삭제 (HTML form은 GET, POST만 보낼 수 있어서 DELETE 대신 POST로 받는다)
	// @RequestParam: form의 name="password" 값을 받는다.
	// RedirectAttributes: redirect한 다음 화면에 한 번만 보여 줄 값(flash)을 넘길 때 쓴다.
	@PostMapping("/posts/{id}/delete")
	public String delete(@PathVariable Long id, @RequestParam String password, RedirectAttributes redirectAttributes) {
		try {
			postService.deleteByAuthor(id, password);
		} catch (BusinessException e) {
			// 비밀번호가 틀린 경우만 여기서 처리하고, 다른 에러(글 없음 등)는 그대로 던져서 에러 화면으로 보낸다.
			if (e.getErrorCode() != ErrorCode.INVALID_PASSWORD) {
				throw e;
			}
			// 상세 화면으로 돌아가서 삭제 칸 아래에 메시지를 보여 준다. (새로고침해도 메시지는 다시 나오지 않는다)
			redirectAttributes.addFlashAttribute("deleteError", e.getErrorCode().getMessage());
			return "redirect:/posts/" + id;
		}
		return "redirect:/";
	}

	// 이번에 연 것을 조회수로 셀지 정한다 (JM-72).
	// - 사람이 연 것만 센다. 검색 엔진, 링크 미리보기, 캡처 도구는 뺀다 (Visitors)
	// - 같은 글 화면에서 넘어온 것은 세지 않는다: 댓글을 쓰거나 지우면 서버가 이 화면을 새로 열어 주는데,
	//   그때마다 올리면 조회수가 댓글 수만큼 부풀려진다
	// - 작성 화면에서 넘어온 것도 세지 않는다: 글을 올린 사람의 첫 화면과, 분석이 끝나 저절로 새로 열리는 화면이다
	//   (/posts는 입력 검사에 한 번 걸렸다가 다시 올린 경우의 작성 화면 주소)
	// - 이 글의 메모 수정 화면에서 넘어온 것도 세지 않는다: 작성자가 메모를 저장했거나 그냥 돌아온 화면이다 (JM-53)
	// 같은 사람이 나갔다가 다시 들어오면 또 센다 (가려 내려면 쿠키나 IP 저장이 필요하다)
	private boolean countsAsView(Long id, String userAgent, String referer) {
		if (!Visitors.isPerson(userAgent)) {
			return false;
		}
		String from = pathOf(referer);
		String thisPost = "/posts/" + id;
		return !from.equals(thisPost) && !from.equals(thisPost + "/edit") && !from.equals("/posts/new") && !from.equals("/posts");
	}

	// 주소에서 경로만 꺼낸다 (https://jjikmuk.com/posts/3?commentCursor=5 → /posts/3). 없거나 주소 모양이 아니면 빈 글자
	private String pathOf(String url) {
		if (url == null || url.isBlank()) {
			return "";
		}
		try {
			String path = URI.create(url).getPath();
			return path == null ? "" : path;
		} catch (IllegalArgumentException e) {
			return "";
		}
	}

	// 사진을 골랐는지는 @NotBlank 같은 규칙으로 검사하기 어려워서 직접 검사하고, 어긋나면 BindingResult에 메시지를 추가한다.
	private void validateImage(MultipartFile image, BindingResult bindingResult) {
		if (image == null || image.isEmpty()) {
			// rejectValue(칸 이름, 에러 종류, 메시지): 그 칸 아래에 보여 줄 메시지를 추가한다.
			bindingResult.rejectValue("image", "required", "사진을 골라 주세요.");
		}
		// 사진 형식(진짜 JPEG, PNG인지)은 파일 내용을 봐야 알 수 있어서 ImageProcessor가 검사한다.
	}
}
