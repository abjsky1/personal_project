package backend.analysis.model.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import backend.analysis.model.entity.FoodAnalysis;
import backend.post.model.entity.Post;
import backend.post.model.repository.PostRepository;

// 하루 분석 횟수를 세는 쿼리를 실제 DB로 확인하는 테스트
@SpringBootTest
@Transactional
class FoodAnalysisRepositoryTest {

	@Autowired
	private FoodAnalysisRepository foodAnalysisRepository;

	@Autowired
	private PostRepository postRepository;

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

	private Post savePost() {
		return postRepository.save(new Post("테스터", "hash", null, "test.jpg", "test-thumb.jpg", null));
	}
}
