package backend.common.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToLongFunction;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;
import backend.post.model.repository.PostRepository;
import io.github.bucket4j.Bucket;
import lombok.extern.slf4j.Slf4j;

// 글 올리기 횟수를 세고, 한도를 넘었으면 막는다 (기획서 7절). UploadRateLimitInterceptor가 글을 올릴 때마다 부른다.
//
// 처음(S4)에는 IP마다 1분 3번, 하루 30번이었다. 같은 와이파이(카페, 강의실, 발표장)를 쓰는 사람들은 밖에서 보면 IP가 하나라서,
// 2026-11-13 발표 때 20명 가까이가 한도 하나를 나눠 쓰다가 막혔다 (JM-79). 그래서 사람 단위로 세고, IP와 서비스 전체에는 하루 상한만 둔다.
// - 브라우저마다: 1분 5번, 하루 30번. 가입이 없어서 사람을 알 수 없으므로 브라우저(세션 쿠키)로 구분한다
// - IP마다: 하루 200번. 1분 제한은 없다 → 여러 사람이 한꺼번에 올려도 막히지 않는다.
//   쿠키를 지워 가며 스크립트로 도배하는 것(브라우저 한도를 피하는 방법)을 막는 바깥 울타리다
// - 서비스 전체: 하루 500개. 오늘 올라온 글 수를 DB에서 센다 → 배포로 서버를 다시 켜도 0으로 돌아가지 않는다
// 하루는 한국 날짜 기준이다 (자정이 지나면 처음부터 센다). 브라우저와 IP의 횟수는 서버 메모리에만 둔다.
// 쿠키를 막은 브라우저는 올릴 때마다 새 세션이 생겨서 브라우저 한도에는 걸리지 않지만, IP와 전체 상한은 그대로 걸린다.
@Slf4j
@Component
public class UploadLimiter {

	private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

	private final int browserPerMinute;
	private final int browserPerDay;
	private final int ipPerDay;
	private final int totalPerDay;
	private final Clock clock;
	// 어느 시각 이후에 올라온 글 수 (운영에서는 DB를 센다. 테스트에서는 원하는 수를 돌려주게 바꿔 넣는다)
	private final ToLongFunction<Instant> postsSince;

	// 브라우저별 1분 양동이 (토큰 버킷 방식, Bucket4j). 1분에 5개가 조금씩 다시 채워진다 (12초마다 1개)
	// 10분 동안 쓰지 않은 양동이는 자동으로 지운다 → 메모리가 끝없이 늘지 않는다
	private final Cache<String, Bucket> minuteBuckets = Caffeine.newBuilder()
			.expireAfterAccess(Duration.ofMinutes(10))
			.maximumSize(100_000)
			.build();

	// 하루 횟수. 키는 "날짜|ip|IP" 또는 "날짜|browser|세션 번호"이고 값은 그날 올린 횟수다.
	// 날짜가 키에 들어 있어서 자정이 지나면 새 키로 처음부터 센다. 지난 날짜의 키는 이틀 뒤에 자동으로 지운다
	private final Cache<String, AtomicInteger> dailyCounts = Caffeine.newBuilder()
			.expireAfterWrite(Duration.ofDays(2))
			.maximumSize(200_000)
			.build();

	// 서버에서 쓰는 생성자: 한도는 application.properties에서, 오늘 올라온 글 수는 DB에서 가져온다
	// @Autowired: 생성자가 둘이라 Spring이 어느 것을 쓸지 알려 준다
	@Autowired
	public UploadLimiter(
			@Value("${app.rate-limit.upload-per-browser-per-minute}") int browserPerMinute,
			@Value("${app.rate-limit.upload-per-browser-per-day}") int browserPerDay,
			@Value("${app.rate-limit.upload-per-ip-per-day}") int ipPerDay,
			@Value("${app.rate-limit.upload-total-per-day}") int totalPerDay,
			PostRepository postRepository) {
		this(browserPerMinute, browserPerDay, ipPerDay, totalPerDay, Clock.system(KOREA), postRepository::countByCreatedAtGreaterThanEqual);
	}

	// 테스트용: 시계와 "글 수 세기"를 바꿔 넣는다 (날짜가 바뀌는 것, 전체 상한에 닿은 것을 흉내 낸다)
	UploadLimiter(int browserPerMinute, int browserPerDay, int ipPerDay, int totalPerDay, Clock clock,
			ToLongFunction<Instant> postsSince) {
		this.browserPerMinute = browserPerMinute;
		this.browserPerDay = browserPerDay;
		this.ipPerDay = ipPerDay;
		this.totalPerDay = totalPerDay;
		this.clock = clock;
		this.postsSince = postsSince;
	}

	// 글 올리기 한 번을 센다. browser: 브라우저를 구분하는 값(세션 번호), ip: 보낸 쪽 IP
	// 통과하면 횟수를 하나 올리고 돌아온다. 한도에 걸리면 BusinessException(429 에러 화면)을 던지고 횟수는 올리지 않는다.
	// 여러 요청이 동시에 들어오면 하루 한도를 한두 번 넘길 수 있지만, 도배를 막는 장치라 그 정도는 괜찮다 (분석 하루 상한과 같은 생각)
	public void check(String browser, String ip) {
		LocalDate today = LocalDate.now(clock);

		// 1. 서비스 전체: 오늘(한국 시간 자정부터) 올라온 글 수
		if (postsSince.applyAsLong(today.atStartOfDay(KOREA).toInstant()) >= totalPerDay) {
			reject(ErrorCode.UPLOADS_FULL_TODAY, "total", ip);
		}

		// 2. 하루 횟수: 같은 IP(와이파이) 전체, 그리고 이 브라우저
		AtomicInteger ipToday = dailyCounts.get(today + "|ip|" + ip, key -> new AtomicInteger());
		AtomicInteger browserToday = dailyCounts.get(today + "|browser|" + browser, key -> new AtomicInteger());
		if (ipToday.get() >= ipPerDay) {
			reject(ErrorCode.TOO_MANY_UPLOADS_TODAY, "ip-day", ip);
		}
		if (browserToday.get() >= browserPerDay) {
			reject(ErrorCode.TOO_MANY_UPLOADS_TODAY, "browser-day", ip);
		}

		// 3. 이 브라우저의 1분 횟수. tryConsume(1): 토큰 1개를 꺼내 본다. 꺼냈으면 통과, 없으면 거절
		if (!minuteBuckets.get(browser, key -> newMinuteBucket()).tryConsume(1)) {
			reject(ErrorCode.TOO_MANY_UPLOADS, "browser-minute", ip);
		}

		ipToday.incrementAndGet();
		browserToday.incrementAndGet();
	}

	// refillGreedy: 1분에 browserPerMinute개를 조금씩 나눠서 계속 채운다
	private Bucket newMinuteBucket() {
		return Bucket.builder()
				.addLimit(limit -> limit.capacity(browserPerMinute).refillGreedy(browserPerMinute, Duration.ofMinutes(1)))
				.build();
	}

	// 어느 한도에 걸렸는지 로그에 남기고 막는다. IP 원문은 로그에 남기지 않고 해시로 남긴다 (같은 IP인지는 구분되지만 원래 IP는 알 수 없다, 기획서 8절)
	private void reject(ErrorCode errorCode, String limit, String ip) {
		log.warn("Upload rate limited: limit={}, ipHash={}", limit, hash(ip));
		throw new BusinessException(errorCode);
	}

	// SHA-256 해시의 앞 12글자. 로그에서 "같은 IP가 반복해서 막혔는지"를 보는 데는 이걸로 충분하다.
	private String hash(String ip) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(ip.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest).substring(0, 12);
		} catch (NoSuchAlgorithmException e) {
			// SHA-256은 모든 자바에 들어 있어서 실제로는 일어나지 않는다.
			throw new IllegalStateException(e);
		}
	}
}
