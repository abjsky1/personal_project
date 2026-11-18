package backend.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import backend.stats.model.dto.AdminStatsResponse.RegionCount;
import backend.stats.model.dto.AdminStatsResponse.VisitStats;
import backend.stats.model.dto.VisitRegionRow;

// IP로 본 시/도별 방문과 글 올리기 (JM-86): 종류별로 나눠 많은 순으로, 합계도 낸다
class VisitStatsTest {

	@Test
	void 방문과_글_올리기를_나눠_많은_순으로_센다() {
		VisitStats stats = VisitStats.of(List.of(
				new VisitRegionRow("VISIT", "경기도", 3),
				new VisitRegionRow("VISIT", "서울특별시", 5),
				new VisitRegionRow("VISIT", "해외", 3),
				new VisitRegionRow("UPLOAD", "서울특별시", 2),
				new VisitRegionRow("OTHER", "서울특별시", 9)));

		assertThat(stats.visits()).isEqualTo(11);
		assertThat(stats.uploads()).isEqualTo(2);
		assertThat(stats.visitRegions().areas()).isEqualTo(3);
		assertThat(stats.visitRegions().top()).containsExactly(new RegionCount("서울특별시", 5),
				new RegionCount("경기도", 3), new RegionCount("해외", 3));
		assertThat(stats.uploadRegions().top()).containsExactly(new RegionCount("서울특별시", 2));
	}

	@Test
	void 기록이_없으면_0과_빈_목록이다() {
		VisitStats stats = VisitStats.of(List.of());

		assertThat(stats.visits()).isZero();
		assertThat(stats.uploads()).isZero();
		assertThat(stats.visitRegions().top()).isEmpty();
		assertThat(stats.uploadRegions().areas()).isZero();
	}
}
