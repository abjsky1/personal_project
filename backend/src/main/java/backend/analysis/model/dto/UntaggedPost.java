package backend.analysis.model.dto;

// 태그를 채울 글 한 건 (JM-83). 태그를 고르는 데 필요한 두 값만 담는다: 글 번호와 사진의 키.
// FoodAnalysisRepository.findUntagged가 분석 기록과 글을 이어서 한 번에 채운다 (select new ...).
public record UntaggedPost(Long postId, String imageKey) {
}
