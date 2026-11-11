package backend.notice.model.dto;

import java.util.List;

// notices.json 한 칸의 모양 그대로 (JM-65). 날짜는 글자("2026-11-11")로 받아 NoticeCatalog가 LocalDate로 바꾼다.
// body: 본문 줄들. "- "로 시작하는 줄이 이어지면 점 목록이 된다
public record NoticeSource(long id, String category, String title, String date, List<String> body) {
}
