package backend.admin.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import backend.admin.auth.AdminAuthService;
import backend.post.service.PostService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

// 관리자 화면 (JM-82). 운영자 API(/api/admin, 화면 없음)와 달리 브라우저로 보고 누르는 화면이다.
// 주소는 어디에도 링크하지 않는다. 들어오는 사람 확인은 AdminAuthService, 로그인 뒤의 문지기는 AdminSessionInterceptor가 한다.
// - /admin/login: 등록한 노트북 브라우저에서, 관리자 비밀번호로
// - /admin/setup: 처음 한 번, 이 브라우저를 관리자 기기로 등록
// - /admin/posts: 글 목록. 번호, 사진, 닉네임, 시각, AI 결과를 보고 지운다
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminPageController {

	private final AdminAuthService adminAuthService;
	private final PostService postService;

	@GetMapping({"", "/"})
	public String home() {
		return "redirect:/admin/posts";
	}

	// 등록한 기기가 있는데 이 브라우저가 그 기기가 아니면, 비밀번호 칸 없이 안내만 보여 준다 (403)
	@GetMapping("/login")
	public String loginForm(HttpServletRequest request, HttpServletResponse response, Model model) {
		adminAuthService.requireEnabled();
		if (adminAuthService.isLoggedIn(request.getSession(false))) {
			return "redirect:/admin/posts";
		}
		return loginPage(request, response, model);
	}

	@PostMapping("/login")
	public String login(@RequestParam(defaultValue = "") String password, HttpServletRequest request,
			HttpServletResponse response, Model model) {
		adminAuthService.requireEnabled();
		switch (adminAuthService.login(request, password)) {
			case LOGGED_IN:
				return "redirect:/admin/posts";
			case NEEDS_REGISTRATION:
				return "redirect:/admin/setup";
			case BLOCKED:
				response.setStatus(429);
				model.addAttribute("error", "비밀번호를 여러 번 틀려서 15분 동안 막혔습니다. 잠시 후 다시 해 주세요.");
				break;
			case UNKNOWN_DEVICE:
				break;
			default:
				model.addAttribute("error", "비밀번호가 맞지 않습니다.");
		}
		return loginPage(request, response, model);
	}

	private String loginPage(HttpServletRequest request, HttpServletResponse response, Model model) {
		boolean registered = adminAuthService.deviceRegistered();
		boolean thisDevice = registered && adminAuthService.isRegisteredDevice(request);
		model.addAttribute("deviceRegistered", registered);
		model.addAttribute("unknownDevice", registered && !thisDevice);
		if (registered && !thisDevice) {
			response.setStatus(403);
		}
		return "admin/login";
	}

	@GetMapping("/setup")
	public String setupForm(HttpServletRequest request, Model model) {
		adminAuthService.requireEnabled();
		if (!adminAuthService.registrationPending(request.getSession(false))) {
			return "redirect:/admin/login";
		}
		model.addAttribute("deviceLabel", AdminAuthService.deviceLabel(request.getHeader("User-Agent")));
		return "admin/setup";
	}

	@PostMapping("/setup")
	public String setup(HttpServletRequest request, HttpServletResponse response) {
		adminAuthService.requireEnabled();
		if (adminAuthService.registerDevice(request, response)) {
			return "redirect:/admin/posts";
		}
		return "redirect:/admin/login";
	}

	@PostMapping("/logout")
	public String logout(HttpServletRequest request, RedirectAttributes redirectAttributes) {
		adminAuthService.logout(request.getSession(false));
		redirectAttributes.addFlashAttribute("message", "로그아웃했습니다.");
		return "redirect:/admin/login";
	}

	// 글 목록: 피드와 같은 순서(최신순, 24개씩)로, 지우지 않은 글을 보여 준다
	@GetMapping("/posts")
	public String posts(@RequestParam(defaultValue = "1") int page, HttpServletRequest request, Model model) {
		model.addAttribute("feed", postService.getFeed(page));
		model.addAttribute("csrf", adminAuthService.csrfToken(request.getSession(false)));
		return "admin/posts";
	}

	// 운영자 삭제: 운영자 API의 글 삭제와 같다 (상태만 바꾸고, 위치 정보는 완전히 지운다)
	@PostMapping("/posts/{id}/delete")
	public String deletePost(@PathVariable Long id, @RequestParam(defaultValue = "1") int page,
			RedirectAttributes redirectAttributes) {
		postService.deleteByAdmin(id);
		redirectAttributes.addFlashAttribute("message", id + "번 글을 지웠습니다.");
		return "redirect:/admin/posts?page=" + page;
	}
}
