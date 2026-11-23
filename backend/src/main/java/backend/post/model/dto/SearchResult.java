package backend.post.model.dto;

// 검색 결과 한 쪽 (JM-52): 피드와 같은 모양(글 목록 + 쪽 번호 줄)에 찾은 글의 전체 수를 더한 것.
// 화면은 피드와 같은 posts/list.jsp를 쓴다. feed는 피드와 같은 이름으로 넘기고, total은 "검색 결과 N개" 줄에 쓴다
public record SearchResult(FeedPage feed, long total) {
}
