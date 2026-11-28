package backend.post.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

// 앱 조회 기록의 하루 상한 (JM-90, 기획서 19.3)
class AppViewDailyLimitTest {

	private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

	@Test
	void 하루_상한까지만_받고_한국_날짜가_바뀌면_다시_받는다() {
		// 한국 시간 2026-11-28 12:00
		MovableClock clock = new MovableClock(Instant.parse("2026-11-28T03:00:00Z"));
		AppViewDailyLimit limit = new AppViewDailyLimit(2, clock);

		assertThat(limit.tryCount()).isTrue();
		assertThat(limit.tryCount()).isTrue();
		assertThat(limit.tryCount()).isFalse();
		assertThat(limit.tryCount()).isFalse();

		// 한국 시간 자정이 지나면(11-29 00:00:01) 0부터 다시 센다
		clock.now = Instant.parse("2026-11-28T15:00:01Z");
		assertThat(limit.tryCount()).isTrue();
		assertThat(limit.tryCount()).isTrue();
		assertThat(limit.tryCount()).isFalse();
	}

	@Test
	void UTC_날짜가_바뀌어도_한국_날짜가_같으면_이어서_센다() {
		// 한국 시간 11-28 08:59 → 09:01 (UTC로는 11-27에서 11-28로 넘어간다)
		MovableClock clock = new MovableClock(Instant.parse("2026-11-27T23:59:00Z"));
		AppViewDailyLimit limit = new AppViewDailyLimit(1, clock);

		assertThat(limit.tryCount()).isTrue();
		clock.now = Instant.parse("2026-11-28T00:01:00Z");
		assertThat(limit.tryCount()).isFalse();
	}

	// 테스트에서 시각을 옮길 수 있는 시계 (PageFlowRecorderTest와 같다)
	private static final class MovableClock extends Clock {

		private Instant now;

		private MovableClock(Instant now) {
			this.now = now;
		}

		@Override
		public ZoneId getZone() {
			return KOREA;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
