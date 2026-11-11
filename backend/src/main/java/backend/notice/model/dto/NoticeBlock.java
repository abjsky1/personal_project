package backend.notice.model.dto;

import java.util.List;

// 공지 본문의 한 덩어리: 문단 하나(text) 또는 점 목록 하나(items). JSP는 items가 비어 있으면 문단, 있으면 목록으로 그린다
public record NoticeBlock(String text, List<String> items) {
}
