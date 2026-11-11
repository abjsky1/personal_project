package backend.stats.model.dto;

// 남아 있는 글 수와 그중 위치가 저장된 글 수 (위치 제공에 동의했고 사진에 GPS가 있는 글)
public record LocationCounts(long activePosts, long withLocation) {
}
