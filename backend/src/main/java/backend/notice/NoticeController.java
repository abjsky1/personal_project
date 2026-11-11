package backend.notice;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// 공지 페이지 (JM-65): 언제 무엇이 바뀌었는지 사용자에게 알린다.
// 공지 글은 JSP(notice/list.jsp)에 그대로 적는다. 정책 페이지와 같은 방식이라 DB나 운영자 API가 필요 없다.
// 사용자에게 보이는 변경을 내보내는 PR에서 공지도 한 줄 같이 더한다 (기획서 16절 변경 기록 규칙 7번)
@Controller
public class NoticeController {

	@GetMapping("/notice")
	public String list() {
		return "notice/list";
	}
}
