package backend.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

// 정책 페이지 (이용약관, 개인정보처리방침). 글은 JSP에 그대로 적혀 있고, 여기서는 어느 화면을 보여 줄지만 고른다.
// 문의 이메일은 화면에 직접 적지 않고 설정(app.contact-email)에서 받아 넘긴다 → 바뀌면 설정만 고치면 된다.
@Controller
public class PolicyController {

	private final String contactEmail;

	public PolicyController(@Value("${app.contact-email}") String contactEmail) {
		this.contactEmail = contactEmail;
	}

	// 이용약관 (S3-4)
	@GetMapping("/terms")
	public String terms(Model model) {
		model.addAttribute("contactEmail", contactEmail);
		return "common/terms";
	}

	// 개인정보처리방침
	@GetMapping("/privacy")
	public String privacy(Model model) {
		model.addAttribute("contactEmail", contactEmail);
		return "common/privacy";
	}
}
