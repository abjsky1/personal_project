// 요청 횟수 제한(rate limit)과 관련된 공통 코드를 두는 패키지
package backend.common.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

// 글 올리기(POST /posts)를 같은 IP에서 너무 자주 하지 못하게 막는다. (기획서 7절: 1분에 3번, 하루 30번)
// 주소가 공개되면 누구나 올릴 수 있어서, 도배로 저장 공간과 (나중에 붙일) AI 분석 한도를 다 써 버리는 것을 막는다.
//
// HandlerInterceptor: Controller 메서드가 실행되기 "직전"에 끼어드는 Spring MVC 기능
// preHandle()에서 예외를 던지면 Controller는 실행되지 않고, PageExceptionHandler가 에러 화면(429)을 보여 준다.
// (필터와 달리 Spring MVC 안에서 실행되므로 우리가 만든 에러 처리를 그대로 쓸 수 있다)
//
// 토큰 버킷 방식 (Bucket4j): IP마다 "토큰이 든 양동이"를 하나씩 준다.
// - 글을 올릴 때마다 토큰을 1개 꺼낸다. 토큰이 없으면 거절한다.
// - 토큰은 시간이 지나면 다시 채워진다. (1분 양동이: 1분에 3개 / 하루 양동이: 하루에 30개)
// - 두 양동이 모두에서 토큰을 꺼낼 수 있어야 통과한다.
@Slf4j
@Component
public class UploadRateLimitInterceptor implements HandlerInterceptor {

	private final int perMinute;
	private final int perDay;

	// IP별 양동이를 보관하는 메모리 저장소 (Caffeine)
	// expireAfterAccess(1일): 하루 동안 요청이 없던 IP의 양동이는 자동으로 지운다 → 메모리가 끝없이 늘지 않는다.
	// maximumSize: 그래도 너무 많아지면 오래된 것부터 지운다.
	// 서버 메모리에만 있으므로 서버를 다시 켜면 횟수가 초기화된다. (서버 1대로 운영하는 지금은 이걸로 충분)
	private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
			.expireAfterAccess(Duration.ofDays(1))
			.maximumSize(100_000)
			.build();

	public UploadRateLimitInterceptor(
			@Value("${app.rate-limit.upload-per-minute}") int perMinute,
			@Value("${app.rate-limit.upload-per-day}") int perDay) {
		this.perMinute = perMinute;
		this.perDay = perDay;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		// 글 올리기(POST)만 센다. 글쓰기 화면을 여는 것(GET)은 세지 않는다.
		if (!"POST".equals(request.getMethod())) {
			return true;
		}

		// getRemoteAddr(): 요청을 보낸 쪽의 IP
		// 배포 서버에서는 Nginx를 거쳐 오지만, application-prod.properties의 forward-headers-strategy 설정 덕분에
		// Nginx의 IP가 아니라 진짜 사용자 IP가 나온다.
		String ip = request.getRemoteAddr();
		// get(키, 없을 때 만드는 방법): 이 IP의 양동이가 없으면 새로 만들어 넣고, 있으면 그대로 꺼낸다.
		Bucket bucket = buckets.get(ip, key -> newBucket());

		// tryConsume(1): 토큰 1개를 꺼내 본다. 꺼냈으면 true(통과), 없으면 false(거절)
		if (!bucket.tryConsume(1)) {
			// IP 원문은 로그에 남기지 않고 해시로 남긴다. (같은 IP인지는 구분되지만 원래 IP는 알 수 없다, 기획서 8절)
			log.warn("Upload rate limited: ipHash={}", hash(ip));
			throw new BusinessException(ErrorCode.TOO_MANY_UPLOADS);
		}
		return true;
	}

	// 양동이 두 개(1분용, 하루용)를 함께 쓰는 Bucket을 만든다.
	// refillGreedy: 1분에 3개를 조금씩 나눠서 계속 채운다 (20초마다 1개)
	// refillIntervally: 하루가 지나면 30개를 한꺼번에 채운다
	private Bucket newBucket() {
		return Bucket.builder()
				.addLimit(limit -> limit.capacity(perMinute).refillGreedy(perMinute, Duration.ofMinutes(1)))
				.addLimit(limit -> limit.capacity(perDay).refillIntervally(perDay, Duration.ofDays(1)))
				.build();
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
