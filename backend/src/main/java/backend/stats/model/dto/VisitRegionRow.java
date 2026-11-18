package backend.stats.model.dto;

// 시/도별 방문과 글 올리기의 기간 합계 한 줄 (JM-86, StatsMapper.findVisitRegionCounts)
// MyBatis가 SELECT의 열 순서대로 채운다: 열 순서 = 이 record의 순서 (metric, region, hits)
public record VisitRegionRow(String metric, String region, long hits) {
}
