package backend.post.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import backend.analysis.model.dto.FoodAnalysisResult;
import backend.analysis.model.dto.FoodAnalyzerResponse;
import backend.analysis.model.entity.FoodAnalysis;
import backend.analysis.model.repository.FoodAnalysisRepository;
import backend.post.model.entity.Post;
import backend.post.model.repository.PostRepository;

// 사진 검토를 시작하기 전에 올라온 글 표시하기(JM-80)를 확인하는 테스트.
// 그런 글은 운영자가 이미 확인했으므로 가리지 않고, 기준 시각 뒤에 올라온 글부터 가린다 (2026-11-15 운영자 결정)
@SpringBootTest
@Transactional
class PhotoReviewBackfillTest {

	@Autowired
	private PhotoReviewBackfill backfill;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private FoodAnalysisRepository foodAnalysisRepository;

	@Test
	void 검토를_시작하기_전에_올라온_음식_아님_글은_올린_것으로_표시하고_뒤의_글은_가린다() {
		Post old = notFoodPost();
		ReflectionTestUtils.setField(old, "createdAt", PhotoReviewBackfill.REVIEW_START.minusSeconds(3600));
		postRepository.saveAndFlush(old);
		Post recent = notFoodPost();
		foodAnalysisRepository.flush();

		int approved = backfill.approveOldHeldPhotos();

		assertThat(approved).isPositive();
		assertThat(postRepository.findById(old.getId()).orElseThrow().getPhotoApprovedAt()).isNotNull();
		assertThat(postRepository.findById(recent.getId()).orElseThrow().getPhotoApprovedAt()).isNull();
		// 서버가 다시 켜져도 바꿀 것이 없다
		assertThat(backfill.approveOldHeldPhotos()).isZero();
	}

	private Post notFoodPost() {
		String key = UUID.randomUUID().toString();
		Post post = postRepository.save(new Post("검토시험", "hash", null, key + ".jpg", key + "-thumb.jpg", null));
		FoodAnalysis analysis = new FoodAnalysis(post);
		analysis.markNotFood(new FoodAnalyzerResponse(
				new FoodAnalysisResult(false, List.of(), "HIGH", "음식이 아니에요"), "test-model", 1300, 50), 1500);
		foodAnalysisRepository.save(analysis);
		return post;
	}
}
