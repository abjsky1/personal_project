package backend.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import backend.location.model.vo.Region;
import backend.stats.model.dto.AdminStatsResponse.RegionCount;
import backend.stats.model.dto.AdminStatsResponse.RegionStats;

// 사진 위치로 본 지역별 글 수 (JM-85): 많은 순, 같으면 이름 순으로 10곳까지. 찾지 못한 위치는 따로 센다
class RegionStatsTest {

	private static final Region MYEONGDONG = new Region("서울특별시", "서울특별시 중구", "서울특별시 중구 명동");
	private static final Region CHORYANG = new Region("부산광역시", "부산광역시 동구", "부산광역시 동구 초량3동");
	private static final Region YEONDONG = new Region("제주특별자치도", "제주특별자치도 제주시", "제주특별자치도 제주시 연동");

	@Test
	void 많은_순으로_세고_같으면_이름_순이다() {
		RegionStats stats = RegionStats.of(List.of(Optional.of(YEONDONG), Optional.of(MYEONGDONG), Optional.empty(),
				Optional.of(CHORYANG), Optional.of(MYEONGDONG)));

		assertThat(stats.located()).isEqualTo(5);
		assertThat(stats.unmatched()).isEqualTo(1);
		assertThat(stats.sido().areas()).isEqualTo(3);
		assertThat(stats.sido().top()).containsExactly(new RegionCount("서울특별시", 2),
				new RegionCount("부산광역시", 1), new RegionCount("제주특별자치도", 1));
		assertThat(stats.sigungu().top()).extracting(RegionCount::name)
				.containsExactly("서울특별시 중구", "부산광역시 동구", "제주특별자치도 제주시");
		assertThat(stats.dong().top()).extracting(RegionCount::name)
				.containsExactly("서울특별시 중구 명동", "부산광역시 동구 초량3동", "제주특별자치도 제주시 연동");
	}

	@Test
	void 열_곳까지만_내고_글이_있는_곳의_수는_따로_센다() {
		List<Optional<Region>> found = IntStream.rangeClosed(1, 12)
				.mapToObj(i -> Optional.of(new Region("서울특별시", "서울특별시 중구", "서울특별시 중구 동%02d".formatted(i))))
				.toList();

		RegionStats stats = RegionStats.of(found);

		assertThat(stats.dong().areas()).isEqualTo(12);
		assertThat(stats.dong().top()).hasSize(10);
		assertThat(stats.dong().top().get(0)).isEqualTo(new RegionCount("서울특별시 중구 동01", 1));
		assertThat(stats.dong().top().get(9)).isEqualTo(new RegionCount("서울특별시 중구 동10", 1));
		assertThat(stats.sido().top()).containsExactly(new RegionCount("서울특별시", 12));
	}

	@Test
	void 위치가_없으면_0과_빈_목록이다() {
		RegionStats stats = RegionStats.of(List.of());

		assertThat(stats.located()).isZero();
		assertThat(stats.unmatched()).isZero();
		assertThat(stats.dong().areas()).isZero();
		assertThat(stats.dong().top()).isEmpty();
	}
}
