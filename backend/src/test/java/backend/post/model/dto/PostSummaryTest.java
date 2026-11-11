package backend.post.model.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

// 휴대폰 카드에 넣는 짧은 날짜 (JM-72). 해가 바뀔 때 지난해 글을 어떻게 보여 주는지가 핵심이라 따로 검사한다.
// Spring을 띄우지 않는 순수한 계산 테스트라 아주 빠르다. "오늘"을 인자로 넘기므로 언제 돌려도 결과가 같다.
class PostSummaryTest {

	private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

	@Test
	void 올해_글의_짧은_날짜는_월과_일_두_자리씩이다() {
		Instant created = LocalDateTime.of(2026, 11, 2, 9, 5).atZone(KOREA).toInstant();

		assertThat(PostSummary.shortWhen(created, LocalDate.of(2026, 11, 11))).isEqualTo("11.02");
	}

	@Test
	void 해가_바뀌면_지난해_글에는_연도_두_자리가_붙는다() {
		Instant created = LocalDateTime.of(2026, 12, 31, 23, 50).atZone(KOREA).toInstant();

		assertThat(PostSummary.shortWhen(created, LocalDate.of(2027, 1, 1))).isEqualTo("26.12.31");
	}

	@Test
	void 날짜는_한국_시간으로_센다() {
		// 세계 표준시(UTC)로는 12월 31일 15:30이지만, 한국 시간으로는 이미 1월 1일 00:30이다
		Instant created = Instant.parse("2026-12-31T15:30:00Z");

		assertThat(PostSummary.shortWhen(created, LocalDate.of(2027, 1, 1))).isEqualTo("01.01");
	}
}
