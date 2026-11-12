package backend.post.controller;

import java.net.URI;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

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

import backend.analysis.model.entity.ConfirmMode;
import backend.analysis.service.FoodAnalysisService;
import backend.comment.model.dto.CommentCreateForm;
import backend.comment.service.CommentService;
import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;
import backend.common.web.Visitors;
import backend.post.model.dto.PostCreateForm;
import backend.post.model.dto.PostMemoForm;
import backend.post.service.PostService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
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

	// 세션에 넣어 두는 값의 이름: 이 브라우저가 "내가 보고 확정"으로 올린 글의 번호들 (JM-74).
	// 세션 = 서버가 브라우저마다 따로 들고 있는 작은 보관함. 브라우저에는 보관함 번호(세션 쿠키)만 있고 내용은 서버에 있다.
	// 30분 동안 요청이 없거나 브라우저를 닫으면 사라지고, 서버를 다시 켜도(배포) 사라진다. 그 뒤에는 비밀번호로 확인한다.
	private static final String UPLOADED_POSTS = "uploadedPosts";

	// 확정 칸으로 돌아가 고쳐서 다시 보낼 수 있는 에러들 (JM-74). 여기에 없는 에러(글 없음, 이미 확정한 글 등)는 에러 화면으로 간다.
	private static final Set<ErrorCode> CONFIRM_FORM_ERRORS = EnumSet.of(
			ErrorCode.INVALID_PASSWORD, ErrorCode.INVALID_CONFIRMATION, ErrorCode.CONFIRMATION_TOO_LARGE,
			ErrorCode.TYPED_ALREADY_USED, ErrorCode.TYPED_NOT_FOOD, ErrorCode.TYPED_AI_FAILED, ErrorCode.TYPED_AI_UNAVAILABLE);

	// 확정 칸에 다시 채워 줄 값의 모양: 고른 것(후보의 순서, typed, -1), 양(숫자 또는 빈칸), 직접 적은 이름(한 줄, 30자까지)
	private static final Pattern PICK_PATTERN = Pattern.compile("\\d{1,2}|typed|-1");
	private static final Pattern AMOUNT_PATTERN = Pattern.compile("(\\d{1,5}(\\.\\d{1,2})?)?");
	private static final Pattern NAME_PATTERN = Pattern.compile(".{0,30}");
	// 다시 채워 줄 값의 개수 상한 (사진 한 장의 음식 수는 이보다 훨씬 적다)
	private static final int MAX_REFILL = 50;

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
	public String create(@Valid @ModelAttribute("form") PostCreateForm form, BindingResult bindingResult, HttpServletRequest request) {
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

		// "내가 보고 확정"으로 올린 글은 이 브라우저가 올렸다는 것을 세션에 적어 둔다 (JM-74).
		// → 올린 직후에는 확정 칸이 펼쳐져서 나오고, 비밀번호를 다시 넣지 않고 확정할 수 있다.
		if (form.getConfirmMode() == ConfirmMode.USER) {
			rememberUploader(request.getSession(), postId);
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
			@RequestHeader(value = HttpHeaders.REFERER, required = false) String referer, HttpServletRequest request, Model model) {
		model.addAttribute("post", postService.getDetail(id, countsAsView(id, userAgent, referer)));
		model.addAttribute("analysis", foodAnalysisService.getView(id));
		// 이 글을 방금 올린 브라우저인지 (JM-74). 그러면 화면이 확정 칸을 펼쳐서 보여 주고 비밀번호 칸을 빼 준다.
		model.addAttribute("confirmTrusted", isUploader(request, id));
		model.addAttribute("comments", commentService.getComments(id, commentCursor));
		// 댓글 입력칸과 연결할 빈 form. 검사에 걸려 돌아온 경우에는 CommentController가 flash로 보낸 입력값이 이미 들어 있어서 덮어쓰지 않는다.
		if (!model.containsAttribute("commentForm")) {
			model.addAttribute("commentForm", new CommentCreateForm());
		}
		return "posts/detail";
	}

	// 메모 수정 (JM-53, 지인 피드백 7번). 고칠 수 있는 것은 메모뿐이다.
	// 따로 수정 화면이 없다: 상세 화면의 메모 아래 "메모 수정"을 누르면 그 자리에 칸이 펼쳐지고, 거기서 보낸 값을 여기서 받는다.
	// 저장했든 못 했든 그 글의 상세로 돌아간다. (화면을 바로 보여 주면 새로고침할 때 같은 요청이 한 번 더 간다)
	// @Valid: PostMemoForm에 적어 둔 규칙(메모 300자, 비밀번호 빈칸)으로 검사하고, 걸린 내용은 바로 뒤의 BindingResult에 담긴다.
	@PostMapping("/posts/{id}/memo")
	public String updateMemo(@PathVariable Long id, @Valid @ModelAttribute PostMemoForm form, BindingResult bindingResult,
			RedirectAttributes redirectAttributes) {
		String error = null;
		if (bindingResult.hasErrors()) {
			error = bindingResult.getFieldErrors().get(0).getDefaultMessage();
		} else {
			try {
				postService.updateMemo(id, form.getMemo(), form.getPassword());
			} catch (BusinessException e) {
				// 비밀번호가 틀린 경우만 여기서 처리하고, 다른 에러(글 없음 등)는 그대로 던져서 에러 화면으로 보낸다.
				if (e.getErrorCode() != ErrorCode.INVALID_PASSWORD) {
					throw e;
				}
				error = e.getErrorCode().getMessage();
			}
		}
		if (error == null) {
			return "redirect:/posts/" + id;
		}
		// 저장하지 못했으면 상세 화면으로 돌아가서 메모 수정 칸을 펼친 채 메시지를 보여 준다. (삭제의 deleteError와 같은 방식)
		// 고쳐 쓰던 메모도 함께 넘겨서 칸에 다시 채운다. flash라서 한 번만 보이고 새로고침하면 사라진다.
		redirectAttributes.addFlashAttribute("memoError", error);
		redirectAttributes.addFlashAttribute("memoDraft", form.getMemo() == null ? "" : form.getMemo());
		// #memo-edit: 돌아간 화면에서 메모 수정 칸 자리로 바로 내려간다
		return "redirect:/posts/" + id + "#memo-edit";
	}

	// AI 분석을 고쳐서 확정 (JM-74, 고쳐 쓰는 AI). 상세 화면의 분석 자리에 펼쳐지는 확정 칸에서 보낸 값을 받는다. 글마다 한 번만 할 수 있다.
	// pick: 음식마다 고른 것 (후보의 순서, "typed" = 직접 적기, "-1" = 이 음식 아님), amount: 음식마다 양(g), name: 음식마다 직접 적은 이름.
	// 이름이 같은 칸이 음식 수만큼 온다. @RequestParam List<String>으로 받지 않고 보낸 그대로 꺼내는(valuesOf) 이유:
	//   @RequestParam은 값이 하나뿐이면 쉼표로 쪼개서 목록을 만든다. 음식이 하나인 글에서 이름에 쉼표를 적으면 둘로 나뉘고,
	//   빈 값 하나는 빈 목록이 돼서, 음식 수와 칸 수가 맞지 않게 된다.
	// 양도 숫자가 아니라 글자로 받는다: 비우거나 잘못 적어 보내도 에러 화면이 아니라 확정 칸의 메시지로 알려 주려고.
	// 이 글을 방금 올린 브라우저면 비밀번호 없이 확정되고, 아니면 글의 비밀번호가 맞아야 한다.
	// 저장했든 못 했든 그 글의 상세로 돌아간다 (메모 수정과 같은 방식).
	@PostMapping("/posts/{id}/confirm")
	public String confirm(@PathVariable Long id, @RequestParam(required = false) String password, HttpServletRequest request,
			RedirectAttributes redirectAttributes) {
		List<String> picks = valuesOf(request, "pick");
		List<String> amounts = valuesOf(request, "amount");
		List<String> names = valuesOf(request, "name");
		try {
			postService.confirmAnalysis(id, password == null ? "" : password, picks, amounts, names, isUploader(request, id));
		} catch (BusinessException e) {
			// 확정 칸에서 고쳐서 다시 보낼 수 있는 경우만 여기서 처리하고, 다른 에러(글 없음, 이미 확정한 글 등)는 그대로 던져서 에러 화면으로 보낸다.
			ErrorCode code = e.getErrorCode();
			if (!CONFIRM_FORM_ERRORS.contains(code)) {
				throw e;
			}
			// 상세 화면으로 돌아가서 확정 칸을 펼친 채 메시지를 보여 준다. 고르던 값도 함께 넘겨 다시 채운다 (flash: 한 번만 보인다)
			redirectAttributes.addFlashAttribute("confirmError", code.getMessage());
			redirectAttributes.addFlashAttribute("confirmPicks", refill(picks, PICK_PATTERN));
			redirectAttributes.addFlashAttribute("confirmAmounts", refill(amounts, AMOUNT_PATTERN));
			redirectAttributes.addFlashAttribute("confirmNames", refill(names, NAME_PATTERN));
			return "redirect:/posts/" + id + "#analysis-confirm";
		}
		return "redirect:/posts/" + id;
	}

	// 이름이 같은 칸의 값을 보낸 그대로, 보낸 순서대로 꺼낸다. 하나도 없으면 빈 목록
	private List<String> valuesOf(HttpServletRequest request, String name) {
		String[] values = request.getParameterValues(name);
		return values == null ? List.of() : List.of(values);
	}

	// 이 브라우저가 올린 글로 적어 둔다. 세션에는 글 번호만 들어간다.
	@SuppressWarnings("unchecked")
	private void rememberUploader(HttpSession session, Long postId) {
		Set<Long> uploaded = (Set<Long>) session.getAttribute(UPLOADED_POSTS);
		if (uploaded == null) {
			uploaded = new HashSet<>();
		}
		uploaded.add(postId);
		session.setAttribute(UPLOADED_POSTS, uploaded);
	}

	// 이 요청을 보낸 브라우저가 그 글을 올린 브라우저인지. getSession(false): 세션이 없으면 새로 만들지 않고 null을 받는다.
	@SuppressWarnings("unchecked")
	private boolean isUploader(HttpServletRequest request, Long postId) {
		HttpSession session = request.getSession(false);
		if (session == null) {
			return false;
		}
		Set<Long> uploaded = (Set<Long>) session.getAttribute(UPLOADED_POSTS);
		return uploaded != null && uploaded.contains(postId);
	}

	// 화면에 다시 채울 값은 정해 둔 모양일 때만 넘긴다. 하나라도 모양이 다르면 넘기지 않아서(null), 화면은 처음 값으로 그려진다.
	// 직접 만든 요청으로 엉뚱한 글자나 아주 긴 값이 와도, 그것이 세션(flash)에 쌓이거나 화면에 그대로 채워지지 않게 하려는 것이다.
	private List<String> refill(List<String> values, Pattern pattern) {
		boolean wellFormed = !values.isEmpty() && values.size() <= MAX_REFILL
				&& values.stream().allMatch(value -> pattern.matcher(value).matches());
		return wellFormed ? values : null;
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
			// 상세 화면으로 돌아가서 "글 삭제" 칸을 펼친 채 메시지를 보여 준다. (새로고침해도 메시지는 다시 나오지 않는다)
			redirectAttributes.addFlashAttribute("deleteError", e.getErrorCode().getMessage());
			// #post-delete: 돌아간 화면에서 삭제 칸 자리로 바로 내려간다 (평소에는 접혀 있는 칸이라, 그냥 돌아가면 메시지를 놓치기 쉽다)
			return "redirect:/posts/" + id + "#post-delete";
		}
		return "redirect:/";
	}

	// 이번에 연 것을 조회수로 셀지 정한다 (JM-72).
	// - 사람이 연 것만 센다. 검색 엔진, 링크 미리보기, 캡처 도구는 뺀다 (Visitors)
	// - 같은 글 화면에서 넘어온 것은 세지 않는다: 댓글을 쓰거나 지우거나 메모를 고치면 서버가 이 화면을 새로 열어 주는데,
	//   그때마다 올리면 조회수가 댓글 수만큼 부풀려진다
	// - 작성 화면에서 넘어온 것도 세지 않는다: 글을 올린 사람의 첫 화면과, 분석이 끝나 저절로 새로 열리는 화면이다
	//   (/posts는 입력 검사에 한 번 걸렸다가 다시 올린 경우의 작성 화면 주소)
	// 같은 사람이 나갔다가 다시 들어오면 또 센다 (가려 내려면 쿠키나 IP 저장이 필요하다)
	private boolean countsAsView(Long id, String userAgent, String referer) {
		if (!Visitors.isPerson(userAgent)) {
			return false;
		}
		String from = pathOf(referer);
		return !from.equals("/posts/" + id) && !from.equals("/posts/new") && !from.equals("/posts");
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
