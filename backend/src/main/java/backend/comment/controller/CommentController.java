package backend.comment.controller;

import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import backend.comment.model.dto.CommentCreateForm;
import backend.comment.service.CommentService;
import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// 댓글 저장, 삭제 요청을 받는 Controller
// 댓글은 따로 화면이 없고 글 상세 화면(PostController)에 붙어 있어서, 여기서는 처리한 뒤 항상 그 글의 상세로 돌려보낸다.
@Controller
@RequiredArgsConstructor
public class CommentController {

	private final CommentService commentService;

	// 댓글 저장. 글 번호는 주소에서 받는다 (/posts/12/comments → 12번 글의 댓글)
	@PostMapping("/posts/{postId}/comments")
	public String create(@PathVariable Long postId, @Valid @ModelAttribute("commentForm") CommentCreateForm form,
			BindingResult bindingResult, RedirectAttributes redirectAttributes) {
		if (bindingResult.hasErrors()) {
			// 검사에 걸리면 상세 화면으로 돌아가 댓글 칸 아래에 메시지를 보여 준다.
			// 상세 화면은 글, 분석, 댓글 목록을 모두 읽어서 그려야 하므로(PostController.detail) 여기서 직접 그리지 않고 redirect한다.
			// flash에 입력값(form)과 검사 결과를 실어 보내면, 상세 화면이 한 번만 그 값으로 칸을 채우고 메시지를 보여 준다.
			// MODEL_KEY_PREFIX + "commentForm": Spring의 form 태그가 "commentForm의 검사 결과"를 찾는 정해진 이름
			redirectAttributes.addFlashAttribute("commentForm", form);
			redirectAttributes.addFlashAttribute(BindingResult.MODEL_KEY_PREFIX + "commentForm", bindingResult);
			return "redirect:/posts/" + postId + "#comments";
		}

		commentService.create(postId, form);
		// #comments: 상세 화면이 열리면 댓글 부분으로 바로 내려가게 한다
		return "redirect:/posts/" + postId + "#comments";
	}

	// 답글 저장 (/posts/12/comments/5/replies → 12번 글의 5번 댓글에 다는 답글)
	// commentCursor: 댓글 "더 보기"로 넘어간 화면에서 달았을 때 그 화면으로 돌아오기 위한 위치
	@PostMapping("/posts/{postId}/comments/{commentId}/replies")
	public String reply(@PathVariable Long postId, @PathVariable Long commentId,
			@RequestParam(required = false) Long commentCursor,
			@Valid @ModelAttribute("replyForm") CommentCreateForm form, BindingResult bindingResult,
			RedirectAttributes redirectAttributes) {
		String detail = "redirect:/posts/" + postId + (commentCursor == null ? "" : "?commentCursor=" + commentCursor);

		if (bindingResult.hasErrors()) {
			// 답글 칸은 댓글마다 있어서 Spring의 form 태그로 잇지 않고, 어느 댓글의 칸인지와 메시지를 따로 넘긴다.
			redirectAttributes.addFlashAttribute("replyForm", form);
			redirectAttributes.addFlashAttribute("replyErrorId", commentId);
			redirectAttributes.addFlashAttribute("replyErrors",
					bindingResult.getFieldErrors().stream().map(FieldError::getDefaultMessage).toList());
			return detail + "#comment-" + commentId;
		}

		Long replyId = commentService.createReply(postId, commentId, form);
		return detail + "#comment-" + replyId;
	}

	// 댓글 삭제 (게시글 삭제와 같이 POST로 받는다)
	@PostMapping("/posts/{postId}/comments/{commentId}/delete")
	public String delete(@PathVariable Long postId, @PathVariable Long commentId, @RequestParam String password,
			RedirectAttributes redirectAttributes) {
		try {
			commentService.deleteByAuthor(postId, commentId, password);
		} catch (BusinessException e) {
			// 비밀번호가 틀린 경우만 상세 화면으로 돌아가 그 댓글 아래에 메시지를 보여 준다. 다른 에러(댓글 없음 등)는 에러 화면으로.
			if (e.getErrorCode() != ErrorCode.INVALID_PASSWORD) {
				throw e;
			}
			redirectAttributes.addFlashAttribute("commentDeleteError", e.getErrorCode().getMessage());
			// 어느 댓글 아래에 메시지를 보여 줄지
			redirectAttributes.addFlashAttribute("commentDeleteErrorId", commentId);
			return "redirect:/posts/" + postId + "#comment-" + commentId;
		}
		return "redirect:/posts/" + postId + "#comments";
	}
}
