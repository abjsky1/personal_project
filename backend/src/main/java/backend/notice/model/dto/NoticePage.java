package backend.notice.model.dto;

import java.util.List;

import backend.common.web.PageNav;

// 공지 목록 한 쪽: 공지들 + 쪽 번호 줄 재료 (피드백 목록과 같은 모양)
public record NoticePage(List<NoticeSummary> notices, PageNav nav) {
}
