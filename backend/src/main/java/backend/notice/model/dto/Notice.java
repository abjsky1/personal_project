package backend.notice.model.dto;

import java.time.LocalDate;
import java.util.List;

// 공지 하나 (notices.json에서 읽은 것). 조회수는 DB(notice_views)에 따로 있다
public record Notice(long id, String category, String title, LocalDate date, List<NoticeBlock> blocks) {
}
