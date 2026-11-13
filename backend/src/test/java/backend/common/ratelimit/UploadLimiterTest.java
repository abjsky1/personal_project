package backend.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

import org.junit.jupiter.api.Test;

import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;

// 업로드 횟수를 세는 규칙(UploadLimiter)을 서버 없이 바로 확인하는 테스트 (JM-79)
// 한도 숫자는 테스트마다 작게 바꿔서 넣고, 시계와 "오늘 올라온 글 수"도 가짜로 넣는다.
class UploadLimiterTest {

	private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
	private static final String WIFI = "203.0.113.7";

	@Test
	void 같은_브라우저는_1분에_5번까지_올리고_6번째는_막힌다() {
		UploadLimiter limiter = limiter(5, 30, 200, 500);

		for (int i = 0; i < 5; i++) {
			limiter.check("browser-a", WIFI);
		}

		assertRejected(() -> limiter.check("browser-a", WIFI), ErrorCode.TOO_MANY_UPLOADS);
	}

	// 2026-11-13 발표 때 막혔던 경우: 같은 와이파이(IP 하나)에서 20명이 한꺼번에 올린다
	@Test
	void 같은_와이파이의_20명이_한꺼번에_올려도_막히지_않는다() {
		UploadLimiter limiter = limiter(5, 30, 200, 500);

		for (int person = 0; person < 20; person++) {
			for (int i = 0; i < 3; i++) {
				String browser = "browser-" + person;
				assertThatCode(() -> limiter.check(browser, WIFI)).doesNotThrowAnyException();
			}
		}
	}

	@Test
	void 같은_브라우저는_하루_상한을_넘으면_내일까지_막힌다() {
		UploadLimiter limiter = limiter(1000, 3, 200, 500);

		for (int i = 0; i < 3; i++) {
			limiter.check("browser-a", WIFI);
		}

		assertRejected(() -> limiter.check("browser-a", WIFI), ErrorCode.TOO_MANY_UPLOADS_TODAY);
		// 같은 와이파이의 다른 사람은 그대로 올릴 수 있다
		assertThatCode(() -> limiter.check("browser-b", WIFI)).doesNotThrowAnyException();
	}

	// 쿠키를 지워 가며(브라우저를 바꿔 가며) 올리는 스크립트는 IP 하루 상한에 걸린다
	@Test
	void 같은_IP는_브라우저가_달라도_하루_상한을_넘으면_막힌다() {
		UploadLimiter limiter = limiter(5, 30, 4, 500);

		for (int i = 0; i < 4; i++) {
			limiter.check("browser-" + i, WIFI);
		}

		assertRejected(() -> limiter.check("browser-new", WIFI), ErrorCode.TOO_MANY_UPLOADS_TODAY);
		// 다른 IP는 따로 센다
		assertThatCode(() -> limiter.check("browser-new", "198.51.100.9")).doesNotThrowAnyException();
	}

	@Test
	void 서비스_전체_하루_상한은_오늘_올라온_글_수로_세고_한국_자정부터_센다() {
		List<Instant> askedSince = new ArrayList<>();
		long[] postsToday = {499};
		ToLongFunction<Instant> postsSince = since -> {
			askedSince.add(since);
			return postsToday[0];
		};
		UploadLimiter limiter = new UploadLimiter(5, 30, 200, 500, clockAt("2026-11-13T18:00:00"), postsSince);

		// 499개면 통과, 500개가 되면 막힌다
		assertThatCode(() -> limiter.check("browser-a", WIFI)).doesNotThrowAnyException();
		postsToday[0] = 500;
		assertRejected(() -> limiter.check("browser-b", WIFI), ErrorCode.UPLOADS_FULL_TODAY);

		// "오늘"은 한국 시간 자정부터다
		assertThat(askedSince).allSatisfy(since -> assertThat(since).isEqualTo(kst("2026-11-13T00:00:00")));
	}

	@Test
	void 막힌_시도는_횟수에_들어가지_않는다() {
		UploadLimiter limiter = limiter(1, 2, 200, 500);

		limiter.check("browser-a", WIFI);
		// 1분 한도에 막힌 시도는 하루 횟수를 올리지 않는다.
		// 올렸다면 둘째 시도로 하루 2번을 다 써서, 셋째 시도는 하루 한도(TOO_MANY_UPLOADS_TODAY)에 걸렸을 것이다
		assertRejected(() -> limiter.check("browser-a", WIFI), ErrorCode.TOO_MANY_UPLOADS);
		assertRejected(() -> limiter.check("browser-a", WIFI), ErrorCode.TOO_MANY_UPLOADS);

		// IP 횟수도 마찬가지다: 막힌 시도를 세지 않으므로 IP 하루 상한이 2여도 같은 와이파이의 다른 사람은 올릴 수 있다
		UploadLimiter shared = limiter(1, 30, 2, 500);
		shared.check("browser-a", WIFI);
		assertRejected(() -> shared.check("browser-a", WIFI), ErrorCode.TOO_MANY_UPLOADS);
		assertThatCode(() -> shared.check("browser-b", WIFI)).doesNotThrowAnyException();
	}

	@Test
	void 한국_날짜가_바뀌면_하루_횟수를_처음부터_센다() {
		MovableClock clock = new MovableClock(kst("2026-11-13T23:59:00"));
		UploadLimiter limiter = new UploadLimiter(1000, 2, 200, 500, clock, since -> 0);

		limiter.check("browser-a", WIFI);
		limiter.check("browser-a", WIFI);
		assertRejected(() -> limiter.check("browser-a", WIFI), ErrorCode.TOO_MANY_UPLOADS_TODAY);

		// 한국 시간 자정이 지났다
		clock.now = kst("2026-11-14T00:00:30");
		assertThatCode(() -> limiter.check("browser-a", WIFI)).doesNotThrowAnyException();
	}

	private static UploadLimiter limiter(int browserPerMinute, int browserPerDay, int ipPerDay, int totalPerDay) {
		return new UploadLimiter(browserPerMinute, browserPerDay, ipPerDay, totalPerDay, clockAt("2026-11-13T18:00:00"), since -> 0);
	}

	private static void assertRejected(Runnable upload, ErrorCode expected) {
		assertThatThrownBy(upload::run)
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(expected);
	}

	private static Clock clockAt(String koreanTime) {
		return Clock.fixed(kst(koreanTime), KOREA);
	}

	private static Instant kst(String koreanTime) {
		return LocalDateTime.parse(koreanTime).atZone(KOREA).toInstant();
	}

	// 테스트 안에서 시각을 옮길 수 있는 시계 (자정이 지나는 것을 흉내 낸다)
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
