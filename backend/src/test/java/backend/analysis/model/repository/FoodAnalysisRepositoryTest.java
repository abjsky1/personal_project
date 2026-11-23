package backend.analysis.model.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

import backend.analysis.model.dto.PendingAnalysis;
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
		Instant now = Instant.now();

		assertThat(foodAnalysisRepository.claimAnalysis(id, now, now.minus(STALE))).isEqualTo(1);
		// 같은 글을 다른 일꾼이 또 맡으려 하면 0 (AI를 부르지 않는다)
		assertThat(foodAnalysisRepository.claimAnalysis(id, now.plusSeconds(1), now.plusSeconds(1).minus(STALE))).isZero();
		assertThat(foodAnalysisRepository.findById(id).orElseThrow().getStatus()).hasToString("PENDING");
	}

	@Test
	void 맡은_지_오래됐는데_아직_PENDING이면_다시_맡을_수_있다() {
		Long id = foodAnalysisRepository.save(new FoodAnalysis(savePost())).getPostId();
		Instant now = Instant.now();
		foodAnalysisRepository.claimAnalysis(id, now, now.minus(STALE));
		// 맡은 일꾼의 app이 분석 중에 죽었다: 맡은 시각이 한 시간 전인 채로 PENDING
		setTimes(id, null, now.minus(Duration.ofHours(1)));

		assertThat(foodAnalysisRepository.claimAnalysis(id, now, now.minus(STALE))).isEqualTo(1);
	}

	@Test
	void 끝난_분석은_맡을_수_없다() {
		FoodAnalysis failed = new FoodAnalysis(savePost());
		failed.markFailed("HTTP_503", 100);
		Long id = foodAnalysisRepository.save(failed).getPostId();
		Instant now = Instant.now();

		assertThat(foodAnalysisRepository.claimAnalysis(id, now, now.minus(STALE))).isZero();
	}

	// ---- 다시 넣을 분석 찾기 (JM-110) ----

	@Test
	void 오래되고_아무도_맡지_않았거나_맡은_지_오래된_PENDING만_오래된_것부터_찾는다() {
		Instant now = Instant.now();
		// 개발 DB에 이미 있는 PENDING보다 앞에 오도록 만든 시각을 2000년으로 돌려놓는다 (오래된 것부터 찾으므로)
		Instant old = Instant.parse("2000-01-01T00:00:00Z");
		Long unclaimed = savePending(old, null);
		Long staleClaimed = savePending(old.plusSeconds(1), now.minus(Duration.ofHours(1)));
		Long recentlyClaimed = savePending(old.plusSeconds(2), now.minusSeconds(30));
		Long tooNew = savePending(now.minusSeconds(30), null);
		Long deletedPost = savePending(old.plusSeconds(3), null);
		Post deleted = postRepository.findById(deletedPost).orElseThrow();
		deleted.deleteByAuthor();
		FoodAnalysis failed = new FoodAnalysis(savePost());
		failed.markFailed("HTTP_503", 100);
		Long finished = foodAnalysisRepository.save(failed).getPostId();
		setTimes(finished, old.plusSeconds(4), null);
		entityManager.flush();

		List<PendingAnalysis> found = foodAnalysisRepository.findStalePending(now.minus(Duration.ofMinutes(3)), now.minus(STALE), 100);

		List<Long> mine = found.stream().map(PendingAnalysis::postId)
				.filter(List.of(unclaimed, staleClaimed, recentlyClaimed, tooNew, deletedPost, finished)::contains).toList();
		assertThat(mine).containsExactly(unclaimed, staleClaimed);
		assertThat(found.get(0)).isEqualTo(new PendingAnalysis(unclaimed, "test.jpg"));
	}

	@Test
	void 한_번에_찾는_수는_limit까지다() {
		Instant now = Instant.now();
		Instant old = Instant.parse("2000-01-01T00:00:00Z");
		Long first = savePending(old, null);
		Long second = savePending(old.plusSeconds(1), null);
		savePending(old.plusSeconds(2), null);

		List<PendingAnalysis> found = foodAnalysisRepository.findStalePending(now.minus(Duration.ofMinutes(3)), now.minus(STALE), 2);

		assertThat(found).extracting(PendingAnalysis::postId).containsExactly(first, second);
	}

	private static final Duration STALE = Duration.ofMinutes(3);

	// PENDING 분석을 만들고 만든 시각과 맡은 시각을 정한다
	private Long savePending(Instant createdAt, Instant claimedAt) {
		Long id = foodAnalysisRepository.save(new FoodAnalysis(savePost())).getPostId();
		setTimes(id, createdAt, claimedAt);
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
