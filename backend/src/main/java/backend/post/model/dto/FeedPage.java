package backend.post.model.dto;

import java.util.List;

import backend.common.web.PageNav;

// 목록 화면 한 쪽에 보여 줄 내용: 글 목록 + 쪽 번호 줄 재료(nav: 현재 쪽, 전체 쪽 수, 이전·다음 가능 여부)
// 처음엔 "더 보기" 위치(nextCursor)를 넘겼는데 쪽 번호 방식으로 바꿨다 (JM-57, 기획서 18절)
public record FeedPage(List<PostSummary> posts, PageNav nav) {
}
