package backend.feedback.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;
import backend.feedback.model.dto.FeedbackCreateForm;
import backend.feedback.service.FeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// 피드백 게시판 화면을 담당하는 Controller (PostController와 같은 구조: 목록 / 작성 화면 / 저장 / 상세 / 삭제)
@Controller
@RequiredArgsConstructor
public class FeedbackController {

	private final FeedbackService feedbackService;

	// 목록 (최신순, "더 보기"는 ?cursor=마지막 번호)
	@GetMapping("/feedback")
	public String list(@RequestParam(required = false) Long cursor, Model model) {
		model.addAttribute("page", feedbackService.getPage(cursor));
		return "feedback/list";
	}

	// 작성 화면
	@GetMapping("/feedback/new")
	public String newForm(Model model) {
		model.addAttribute("form", new FeedbackCreateForm());
		return "feedback/new";
	}

	// 저장. 검사에 걸리면 작성 화면을 다시 보여 준다 (입력값은 남고 칸 아래에 메시지)
	@PostMapping("/feedback")
	public String create(@Valid @ModelAttribute("form") FeedbackCreateForm form, BindingResult bindingResult) {
		if (bindingResult.hasErrors()) {
			return "feedback/new";
		}
		Long id = feedbackService.create(form);
		// 저장 뒤 상세로 이동 (새로고침해도 다시 저장되지 않게)
		return "redirect:/feedback/" + id;
	}

	// 상세
	@GetMapping("/feedback/{id}")
	public String detail(@PathVariable Long id, Model model) {
		model.addAttribute("feedback", feedbackService.getDetail(id));
		return "feedback/detail";
	}

	// 삭제 (POST). 비밀번호가 틀리면 상세로 돌아가 메시지, 맞으면 목록으로
	@PostMapping("/feedback/{id}/delete")
	public String delete(@PathVariable Long id, @RequestParam String password, RedirectAttributes redirectAttributes) {
		try {
			feedbackService.deleteByAuthor(id, password);
		} catch (BusinessException e) {
			if (e.getErrorCode() != ErrorCode.INVALID_PASSWORD) {
				throw e;
			}
			redirectAttributes.addFlashAttribute("deleteError", e.getErrorCode().getMessage());
			return "redirect:/feedback/" + id;
		}
		return "redirect:/feedback";
	}
}
