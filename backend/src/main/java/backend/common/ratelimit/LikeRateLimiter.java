package backend.common.ratelimit;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;
import io.github.bucket4j.Bucket;
import lombok.extern.slf4j.Slf4j;

// 좋아요(POST /api/posts/{id}/like)를 스크립트로 부풀리지 못하게 막는다 (JM-59).
//
// 가입이 없는 서비스라 "한 사람이 한 번"을 서버가 알 수 없다. 그래서 둘로 나눠 막는다.
// - 평범한 사용: 누른 글을 브라우저가 기억해서 다시 누를 수 없게 한다 (detail.jsp)
// - 스크립트로 숫자를 부풀리는 것: 여기서 "글 하나가 받을 수 있는 좋아요 수"를 1분, 하루 단위로 제한한다
//   (업로드 제한과 같은 토큰 버킷 방식. 다만 양동이를 IP가 아니라 글 번호마다 둔다)
//
// IP로 막지 않는 이유
// 1. 처리방침에 접속 IP를 쓰는 목적을 "업로드 횟수 제한"으로 적어 두었다. 목적을 넓히려면 방침을 바꾸고 7일 전에 알려야 한다.
// 2. 같은 와이파이(교실, 사무실, 집)를 쓰는 사람들은 IP가 같아서, IP로 막으면 서로의 좋아요를 막게 된다.
// 그래서 누가 눌렀는지는 어디에도 두지 않고, 글 번호별 횟수만 서버 메모리에 둔다. 서버를 다시 켜면 처음부터 다시 센다.
// 한계: 마음먹고 스크립트를 돌리면 한 글의 숫자를 하루 상한까지는 올릴 수 있다. 실제로 생기면 그때 방침을 바꿔 IP 제한을 더한다.
@Slf4j
@Component
public class LikeRateLimiter {

	private final int perMinute;
	private final int perDay;

	// 글 번호별 양동이. 하루 동안 좋아요가 없던 글의 양동이는 자동으로 지운다 → 메모리가 끝없이 늘지 않는다.
	private final Cache<Long, Bucket> buckets = Caffeine.newBuilder()
			.expireAfterAccess(Duration.ofDays(1))
			.maximumSize(100_000)
			.build();

	public LikeRateLimiter(
			@Value("${app.rate-limit.like-per-post-per-minute}") int perMinute,
			@Value("${app.rate-limit.like-per-post-per-day}") int perDay) {
		this.perMinute = perMinute;
		this.perDay = perDay;
	}

	// 통과하면 그냥 돌아오고, 이 글의 한도를 넘었으면 TOO_MANY_LIKES 예외(429)를 던진다.
	public void check(Long postId) {
		// get(키, 없을 때 만드는 방법): 이 글의 양동이가 없으면 새로 만들어 넣고, 있으면 그대로 꺼낸다.
		Bucket bucket = buckets.get(postId, key -> newBucket());
		if (!bucket.tryConsume(1)) {
			log.warn("Like rate limited: postId={}", postId);
			throw new BusinessException(ErrorCode.TOO_MANY_LIKES);
		}
	}

	// 1분 양동이는 조금씩 계속 채우고, 하루 양동이는 하루가 지나면 한꺼번에 채운다 (UploadRateLimitInterceptor와 같음)
	private Bucket newBucket() {
		return Bucket.builder()
				.addLimit(limit -> limit.capacity(perMinute).refillGreedy(perMinute, Duration.ofMinutes(1)))
				.addLimit(limit -> limit.capacity(perDay).refillIntervally(perDay, Duration.ofDays(1)))
				.build();
	}
}
