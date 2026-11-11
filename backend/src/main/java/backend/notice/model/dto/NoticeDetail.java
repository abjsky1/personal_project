package backend.notice.model.dto;

import java.util.List;

// 공지 상세 화면용 값
public record NoticeDetail(long id, String category, String title, String date, long views, List<NoticeBlock> blocks) {
}
