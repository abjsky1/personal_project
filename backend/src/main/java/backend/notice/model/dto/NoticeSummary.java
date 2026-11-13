package backend.notice.model.dto;

// 공지 목록의 한 줄: 맨 왼쪽에 번호, [분류] 제목, 오른쪽에 조회수와 날짜("2026.11.11")
// id: 공지 번호 (notices.json의 id, 주소 /notice/번호의 번호와 같다). 목록의 맨 왼쪽에 보여 준다 (JM-76)
// deleted: 지운 공지의 자리인지 (JM-76). 그 번호의 공지가 notices.json에 없으면 줄을 빼지 않고
//   번호와 "삭제된 공지입니다."만 보여 준다 → 공지를 지워도 다른 공지의 번호와 자리가 당겨지지 않는다
public record NoticeSummary(long id, String category, String title, String date, long views, boolean deleted) {

	// 지운 공지의 자리: 번호만 있다
	public static NoticeSummary deleted(long id) {
		return new NoticeSummary(id, null, null, null, 0, true);
	}
}
