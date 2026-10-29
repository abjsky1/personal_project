package backend.post.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import backend.image.LocalImageStorage;
import backend.post.model.dto.PostCreateForm;
import backend.post.service.PostService;
import jakarta.validation.Valid;

// 게시글 화면을 담당하는 Controller
// @Controller: 메서드가 돌려준 문자열을 JSP 이름으로 보고 그 화면을 보여 준다.
// (앞뒤에 prefix·suffix가 붙는다: "posts/list" → /WEB-INF/views/posts/list.jsp)
@Controller
public class PostController {

	private final PostService postService;

	public PostController(PostService postService) {
		this.postService = postService;
	}

	// 첫 화면 = 게시글 목록
	// @RequestParam(required = false): 주소의 ?cursor=값 을 받는다. 없으면 null (첫 화면)
	// Model: JSP로 넘겨줄 값을 담는 상자. 여기서 넣은 "feed"를 JSP에서 ${feed}로 꺼낸다.
	@GetMapping("/")
	public String list(@RequestParam(required = false) Long cursor, Model model) {
		model.addAttribute("feed", postService.getFeed(cursor));
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

		// 어긋난 칸이 있으면 저장하지 않고 글쓰기 화면을 다시 보여 준다. (입력한 닉네임·메모는 그대로 남고, 각 칸 아래에 메시지가 나온다)
		// 사진 칸은 브라우저 보안 규칙 때문에 다시 채울 수 없어서 사진은 다시 골라야 한다.
		if (bindingResult.hasErrors()) {
			return "posts/new";
		}

		postService.create(form);

		// redirect: 저장 후 목록 주소로 이동하라고 브라우저에 알린다.
		// 화면을 바로 보여 주지 않고 이동시키는 이유: 새로고침을 눌렀을 때 같은 글이 한 번 더 저장되는 것을 막는다.
		return "redirect:/";
	}

	// 사진은 @NotBlank 같은 규칙으로 검사하기 어려워서 직접 검사하고, 어긋나면 BindingResult에 메시지를 추가한다.
	private void validateImage(MultipartFile image, BindingResult bindingResult) {
		if (image == null || image.isEmpty()) {
			// rejectValue(칸 이름, 에러 종류, 메시지): 그 칸 아래에 보여 줄 메시지를 추가한다.
			bindingResult.rejectValue("image", "required", "사진을 골라 주세요.");
		} else if (!LocalImageStorage.isSupported(image.getContentType())) {
			bindingResult.rejectValue("image", "type", "JPEG 또는 PNG 사진만 올릴 수 있어요.");
		}
	}
}
