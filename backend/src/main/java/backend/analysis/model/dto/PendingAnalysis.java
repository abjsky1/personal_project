package backend.analysis.model.dto;

// 다시 줄에 넣을 분석 한 건 (JM-110). 분석을 다시 시작하는 데 필요한 두 값만 담는다: 글 번호와 분석할 사진의 키.
// FoodAnalysisRepository.findStalePending이 분석 기록과 글을 이어서 한 번에 채운다 (select new ...).
public record PendingAnalysis(Long postId, String imageKey) {
}
