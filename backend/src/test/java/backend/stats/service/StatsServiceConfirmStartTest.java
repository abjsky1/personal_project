package backend.stats.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

// 확정 통계(JM-75)를 셀 시작 시각: 기간의 시작과 고쳐 쓰는 AI 배포 시각(2026-11-13 10:05 한국 시각) 중 늦은 쪽
// 배포 전에 올라온 글은 컬럼 기본값 때문에 모두 "AI가 바로 확정"으로 남아 있어서 세지 않는다
class StatsServiceConfirmStartTest {

	@Test
	void 배포_전에_시작하는_기간은_배포_시각부터_센다() {
		LocalDateTime before = LocalDateTime.of(2026, 11, 1, 15, 0);
		LocalDateTime after = LocalDateTime.of(2026, 11, 20, 15, 0);

		assertThat(StatsService.confirmStart(before)).isEqualTo(StatsService.CONFIRM_RELEASED_UTC);
		assertThat(StatsService.confirmStart(after)).isEqualTo(after);
		assertThat(StatsService.CONFIRM_RELEASED_UTC).isEqualTo(LocalDateTime.of(2026, 11, 13, 1, 5));
	}
}
