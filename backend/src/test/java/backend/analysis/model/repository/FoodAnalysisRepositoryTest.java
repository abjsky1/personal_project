package backend.analysis.model.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

import backend.analysis.model.dto.PendingAnalysis;
import backend.analysis.model.entity.AnalysisStatus;
import backend.analysis.model.entity.FoodAnalysis;
import backend.post.model.entity.Post;
import backend.post.model.repository.PostRepository;

// 하루 분석 횟수를 세는 쿼리, 분석 맡기와 다시 넣을 분석을 찾는 쿼리(JM-110)를 실제 DB로 확인하는 테스트
@SpringBootTest
@Transactional
class FoodAnalysisRepositoryTest {

	@Autowired
	private FoodAnalysisRepository foodAnalysisRepository;

	@Autowired
	private PostRepository postRepository;

	// 만든 시각과 맡은 시각을 지난 시각으로 돌려놓을 때 쓴다 (엔티티에는 그 값을 바꾸는 메서드가 없다)
	@Autowired
	private EntityManager entityManager;

	@Test
	void AI를_부른_건만_센다() {
		Instant since = Instant.now().minusSeconds(60);
		// 개발 DB에 이미 있는 기록이 있을 수 있어서, 절대 숫자가 아니라 늘어난 양을 본다.
		long before = foodAnalysisRepository.countByCompletedAtGreaterThanEqual(since);

		FoodAnalysis failed = new FoodAnalysis(savePost());
		failed.markFailed("HTTP_503", 100);
		foodAnalysisRepository.save(failed);

		FoodAnalysis limitExceeded = new FoodAnalysis(savePost());
		limitExceeded.markLimitExceeded();
		foodAnalysisRepository.save(limitExceeded);

		foodAnalysisRepository.save(new FoodAnalysis(savePost()));

		// 세 건 중 AI를 부른 것은 실패한 한 건뿐이다. (실패한 호출도 요금이 나갈 수 있어서 센다)
		assertThat(foodAnalysisRepository.countByCompletedAtGreaterThanEqual(since)).isEqualTo(before + 1);
	}

	@Test
	void 기준_시각_전에_끝난_건은_세지_않는다() {
		FoodAnalysis failed = new FoodAnalysis(savePost());
		failed.markFailed("HTTP_503", 100);
		foodAnalysisRepository.save(failed);

		assertThat(foodAnalysisRepository.countByCompletedAtGreaterThanEqual(Instant.now().plusSeconds(60))).isZero();
	}

	// ---- 분석 맡기 (JM-110) ----

	@Test
	void PENDING인_글은_한_번만_맡을_수_있다() {
		Long id = foodAnalysisRepository.save(new FoodAnalysis(savePost())).getPostId();
		Instant now = micros(Instant.now());

		assertThat(foodAnalysisRepository.claimAnalysis(id, now, now.minus(STALE))).isEqualTo(1);
		// 같은 글을 다른 일꾼이 또 맡으려 하면 0 (AI를 부르지 않는다)
		assertThat(foodAnalysisRepository.claimAnalysis(id, now.plusSeconds(1), now.plusSeconds(1).minus(STALE))).isZero();
		FoodAnalysis row = foodAnalysisRepository.findById(id).orElseThrow();
		assertThat(row.getStatus()).isEqualTo(AnalysisStatus.PENDING);
		assertThat(row.getClaimCount()).isEqualTo(1);
	}

	@Test
	void 맡은_지_오래됐는데_아직_PENDING이면_다시_맡을_수_있다() {
		Long id = foodAnalysisRepository.save(new FoodAnalysis(savePost())).getPostId();
		Instant now = micros(Instant.now());
		foodAnalysisRepository.claimAnalysis(id, now, now.minus(STALE));
		// 맡은 일꾼의 app이 분석 중에 꺼졌다: 맡은 시각이 한 시간 전인 채로 PENDING
		setTimes(id, null, now.minus(Duration.ofHours(1)));

		assertThat(foodAnalysisRepository.claimAnalysis(id, now, now.minus(STALE))).isEqualTo(1);
		assertThat(foodAnalysisRepository.findById(id).orElseThrow().getClaimCount()).isEqualTo(2);
	}

	@Test
	void 맡을_수_있는_횟수를_다_쓴_글은_더_맡지_못한다() {
		Long id = foodAnalysisRepository.save(new FoodAnalysis(savePost())).getPostId();
		Instant now = micros(Instant.now());
		for (int i = 0; i < FoodAnalysis.MAX_CLAIMS; i++) {
			assertThat(foodAnalysisRepository.claimAnalysis(id, now, now.minus(STALE))).isEqualTo(1);
			setTimes(id, null, now.minus(Duration.ofHours(1)));
		}

		// 맡은 지 오래됐어도, 같은 자리에서 계속 끊기는 글에 AI를 끝없이 부르지 않는다
		assertThat(foodAnalysisRepository.claimAnalysis(id, now, now.minus(STALE))).isZero();
	}

	@Test
	void 끝난_분석은_맡을_수_없다() {
		FoodAnalysis failed = new FoodAnalysis(savePost());
		failed.markFailed("HTTP_503", 100);
		Long id = foodAnalysisRepository.save(failed).getPostId();
		Instant now = micros(Instant.now());

		assertThat(foodAnalysisRepository.claimAnalysis(id, now, now.minus(STALE))).isZero();
	}

	@Test
	void 맡은_시각이_그대로일_때만_아직_내가_맡은_글이다() {
		Long id = foodAnalysisRepository.save(new FoodAnalysis(savePost())).getPostId();
		Instant mine = micros(Instant.now());
		foodAnalysisRepository.claimAnalysis(id, mine, mine.minus(STALE));

		// DB를 거쳐도 마이크로초까지 같은 값이라 비교가 맞는다
		assertThat(foodAnalysisRepository.existsByPostIdAndStatusAndClaimedAt(id, AnalysisStatus.PENDING, mine)).isTrue();
		// 다른 일꾼이 다시 맡으면 시각이 바뀌어 내 것이 아니다
		setTimes(id, null, mine.minus(Duration.ofHours(1)));
		Instant other = mine.plusSeconds(1);
		foodAnalysisRepository.claimAnalysis(id, other, other.minus(STALE));
		assertThat(foodAnalysisRepository.existsByPostIdAndStatusAndClaimedAt(id, AnalysisStatus.PENDING, mine)).isFalse();
	}

	@Test
	void 실패는_내가_맡은_PENDING일_때만_UPDATE_한_번으로_적히고_하루_횟수에_센다() {
		Long id = foodAnalysisRepository.save(new FoodAnalysis(savePost())).getPostId();
		Instant mine = micros(Instant.now());
		foodAnalysisRepository.claimAnalysis(id, mine, mine.minus(STALE));
		Instant since = Instant.now().minusSeconds(60);
		long before = foodAnalysisRepository.countByCompletedAtGreaterThanEqual(since);

		// 다른 시각(다른 일꾼)으로는 바뀌지 않는다
		assertThat(foodAnalysisRepository.markFailedIfClaimed(id, "HTTP_503", 100, Instant.now(), mine.plusSeconds(1))).isZero();
		assertThat(foodAnalysisRepository.markFailedIfClaimed(id, "HTTP_503", 100, Instant.now(), mine)).isEqualTo(1);

		FoodAnalysis row = foodAnalysisRepository.findById(id).orElseThrow();
		assertThat(row.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(row.getErrorMessage()).isEqualTo("HTTP_503");
		assertThat(row.getAttemptCount()).isEqualTo(1);
		assertThat(foodAnalysisRepository.countByCompletedAtGreaterThanEqual(since)).isEqualTo(before + 1);
	}

	@Test
	void 횟수를_다_쓰고_마지막으로_맡은_지_오래된_PENDING만_STUCK으로_끝낸다() {
		Instant now = micros(Instant.now());
		Long exhaustedOld = savePendingWithClaims(FoodAnalysis.MAX_CLAIMS, now.minus(Duration.ofHours(1)));
		Long exhaustedRecent = savePendingWithClaims(FoodAnalysis.MAX_CLAIMS, now.minusSeconds(30));
		Long notExhausted = savePendingWithClaims(FoodAnalysis.MAX_CLAIMS - 1, now.minus(Duration.ofHours(1)));

		foodAnalysisRepository.failExhausted(now.minus(STALE));

		FoodAnalysis gaveUp = foodAnalysisRepository.findById(exhaustedOld).orElseThrow();
		assertThat(gaveUp.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(gaveUp.getErrorMessage()).isEqualTo("STUCK");
		// AI를 부른 것이 아니라서 끝난 시각은 적지 않는다 (하루 분석 횟수에 세지 않는다)
		assertThat(gaveUp.getCompletedAt()).isNull();
		// 마지막 분석이 아직 도는 중일 수 있는 글과, 아직 횟수가 남은 글은 그대로
		assertThat(foodAnalysisRepository.findById(exhaustedRecent).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.PENDING);
		assertThat(foodAnalysisRepository.findById(notExhausted).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.PENDING);
	}

	// ---- 다시 넣을 분석 찾기 (JM-110) ----
	// 기준 시각 셋을 서로 다르게 준다: 하나를 다른 것과 바꿔 넘기는 실수도 걸리게

	@Test
	void 다시_넣을_분석은_기간_안의_오래되고_맡지_않았거나_오래_맡은_PENDING을_오래된_것부터_찾는다() {
		Instant now = micros(Instant.now());
		// 개발 DB에 이미 있는 PENDING보다 앞에 오도록 만든 시각을 2000년으로 돌려놓고, 찾는 기간도 그때부터로 준다
		Instant old = Instant.parse("2000-01-01T00:00:00Z");
		Instant createdAfter = old.minusSeconds(60);
		Instant createdBefore = now.minus(Duration.ofMinutes(3));
		Instant staleBefore = now.minus(Duration.ofMinutes(10));
		// 오래된 것부터 찾는지 보려고, 만든 시각이 늦은 글을 먼저 저장한다 (저장 순서와 만든 시각의 순서를 다르게)
		Long staleClaimed = savePending(old.plusSeconds(1), now.minus(Duration.ofHours(1)));
		Long unclaimed = savePending(old, null);
		Long claimedFiveMinutesAgo = savePending(old.plusSeconds(2), now.minus(Duration.ofMinutes(5)));
		Long tooNew = savePending(now.minus(Duration.ofMinutes(1)), null);
		Long createdFiveMinutesAgo = savePending(now.minus(Duration.ofMinutes(5)), null);
		Long tooOld = savePending(old.minusSeconds(3600), null);
		Long deletedPost = savePending(old.plusSeconds(3), null);
		Post deleted = postRepository.findById(deletedPost).orElseThrow();
		deleted.deleteByAuthor();
		FoodAnalysis failed = new FoodAnalysis(savePost());
		failed.markFailed("HTTP_503", 100);
		Long finished = foodAnalysisRepository.save(failed).getPostId();
		setTimes(finished, old.plusSeconds(4), null);
		Long exhausted = savePendingWithClaims(FoodAnalysis.MAX_CLAIMS, now.minus(Duration.ofHours(1)));
		setTimes(exhausted, old.plusSeconds(5), now.minus(Duration.ofHours(1)));
		entityManager.flush();

		List<PendingAnalysis> found = foodAnalysisRepository.findStalePending(createdAfter, createdBefore, staleBefore, 100);

		List<Long> all = List.of(staleClaimed, unclaimed, claimedFiveMinutesAgo, tooNew, createdFiveMinutesAgo, tooOld, deletedPost,
				finished, exhausted);
		List<Long> mine = found.stream().map(PendingAnalysis::postId).filter(all::contains).toList();
		assertThat(mine).containsExactly(unclaimed, staleClaimed, createdFiveMinutesAgo);
		assertThat(found.get(0)).isEqualTo(new PendingAnalysis(unclaimed, "test.jpg"));
	}

	@Test
	void 한_번에_찾는_수는_limit까지다() {
		Instant now = micros(Instant.now());
		Instant old = Instant.parse("2000-01-01T00:00:00Z");
		Long second = savePending(old.plusSeconds(1), null);
		Long first = savePending(old, null);
		savePending(old.plusSeconds(2), null);

		List<PendingAnalysis> found = foodAnalysisRepository.findStalePending(
				old.minusSeconds(60), now.minus(Duration.ofMinutes(3)), now.minus(Duration.ofMinutes(10)), 2);

		assertThat(found).extracting(PendingAnalysis::postId).containsExactly(first, second);
	}

	private static final Duration STALE = Duration.ofMinutes(3);

	// DB 칸(DATETIME(6))에 그대로 들어가는 값으로 자른다 (서비스도 맡을 때 이렇게 자른다)
	private static Instant micros(Instant instant) {
		return instant.truncatedTo(ChronoUnit.MICROS);
	}

	// PENDING 분석을 만들고 만든 시각과 맡은 시각을 정한다
	private Long savePending(Instant createdAt, Instant claimedAt) {
		Long id = foodAnalysisRepository.save(new FoodAnalysis(savePost())).getPostId();
		setTimes(id, createdAt, claimedAt);
		return id;
	}

	// claims번 맡은 PENDING 분석을 만들고, 마지막으로 맡은 시각을 lastClaimedAt으로 둔다
	private Long savePendingWithClaims(int claims, Instant lastClaimedAt) {
		Long id = foodAnalysisRepository.save(new FoodAnalysis(savePost())).getPostId();
		Instant now = micros(Instant.now());
		for (int i = 0; i < claims; i++) {
			foodAnalysisRepository.claimAnalysis(id, now, now.minus(STALE));
			setTimes(id, null, now.minus(Duration.ofHours(1)));
		}
		setTimes(id, null, lastClaimedAt);
		return id;
	}

	// 만든 시각(createdAt, null이면 그대로)과 맡은 시각(claimedAt)을 직접 바꾼다
	private void setTimes(Long id, Instant createdAt, Instant claimedAt) {
		entityManager.flush();
		if (createdAt != null) {
			entityManager.createQuery("update FoodAnalysis a set a.createdAt = :t where a.postId = :id")
					.setParameter("t", createdAt).setParameter("id", id).executeUpdate();
		}
		entityManager.createQuery("update FoodAnalysis a set a.claimedAt = :t where a.postId = :id")
				.setParameter("t", claimedAt).setParameter("id", id).executeUpdate();
		entityManager.clear();
	}

	private Post savePost() {
		return postRepository.save(new Post("테스터", "hash", null, "test.jpg", "test-thumb.jpg", null));
	}
}
