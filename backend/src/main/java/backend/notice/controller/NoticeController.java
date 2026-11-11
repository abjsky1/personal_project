package backend.notice.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import backend.notice.service.NoticeService;
import lombok.RequiredArgsConstructor;

// 공지 게시판 (JM-65): 목록은 한 줄에 [안내] 제목, 오른쪽에 조회수와 날짜. 누르면 상세.
// 공지 글은 src/main/resources/notices.json에 있다. 사용자에게 보이는 변경을 내보내는 PR에서 그 파일 맨 위에 공지를 더한다 (기획서 16절 변경 기록 규칙 7번)
@Controller
@RequiredArgsConstructor
public class NoticeController {

	private final NoticeService noticeService;

	// 목록 (최신순, 쪽 번호 방식 ?page=2. 없으면 1쪽)
	@GetMapping("/notice")
	public String list(@RequestParam(defaultValue = "1") int page, Model model) {
		model.addAttribute("page", noticeService.getPage(page));
		return "notice/list";
	}

	// 상세. 사람이 열면 조회수 1 (봇, 캡처 도구는 User-Agent로 거른다)
	@GetMapping("/notice/{id}")
	public String detail(@PathVariable long id,
			@RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent, Model model) {
		model.addAttribute("notice", noticeService.getDetail(id, userAgent));
		return "notice/detail";
	}
}
