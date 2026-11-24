package backend.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.annotation.Transactional;

import backend.analysis.model.dto.FoodAnalysisResult;
import backend.analysis.model.dto.FoodAnalysisResult.FoodItem;
import backend.analysis.model.dto.FoodAnalyzerResponse;
import backend.analysis.model.dto.FoodTags;
import backend.analysis.model.dto.FoodTagsResponse;
import backend.analysis.model.dto.UntaggedPost;
import backend.analysis.model.entity.FoodAnalysis;
import backend.analysis.model.entity.FoodTag;
import backend.analysis.model.entity.PostTag;
import backend.analysis.model.repository.FoodAnalysisRepository;
import backend.analysis.model.repository.PostTagRepository;
import backend.image.ImageStorage;
import backend.post.model.entity.Post;
import backend.post.model.repository.PostRepository;
import jakarta.persistence.EntityManager;

// 태그 채우기(JM-83)를 실제 DB로 확인한다: 어떤 글을 찾는지, 한 글을 한 번만 맡는지, 태그와 "채웠음"이 함께 저장되는지.
// AI와 사진 저장소는 가짜다 (실제 AI를 부르지 않는다). 채우기는 일정으로 돌지 않게 꺼 두고(테스트 설정) 여기서 직접 만든다.
// 개발 DB에 태그가 없는 다른 글이 있어도 되도록, 찾은 글 가운데 이 테스트가 만든 글만 본다.
// @Transactional: 테스트가 저장한 글, 분석 기록, 태그는 끝나면 되돌린다
@SpringBootTest
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class PostTagFillerTest {

	private static final Duration STALE = PostTagFiller.CLAIM_STALE_AFTER;
	private static final byte[] JPEG = {1, 2, 3};

	@Autowired
	private FoodAnalysisRepository foodAnalysisRepository;

	@Autowired
	private PostTagRepository postTagRepository;

	@Autowired
	private PostTagService postTagService;

	@Autowired
	private PostRepository postRepository;

	// 맡은 시각을 지난 시각으로 돌려놓을 때 쓴다 (그 칸은 엔티티로 바꾸지 않는다)
	@Autowired
	private EntityManager entityManager;

	private final FoodAnalyzer analyzer = mock(FoodAnalyzer.class);
	private final ImageStorage storage = mock(ImageStorage.class);
	private final FoodAnalysisService analysisService = mock(FoodAnalysisService.class);
	private PostTagFiller filler;

	@BeforeEach
	void setUp() {
		filler = new PostTagFiller(foodAnalysisRepository, analysisService, postTagService, analyzer, storage, 10);
		when(storage.load(any())).thenReturn(Optional.of(JPEG));
		when(analysisService.isDailyLimitReached()).thenReturn(false);
	}

	@Test
	void 분석이_끝났고_태그가_없는_글을_최신_글부터_찾는다() {
		Long older = saveDone("older.jpg");
		Long newer = saveDone("newer.jpg");
		Long tagged = saveDone("tagged.jpg");
		postTagService.replace(tagged, Set.of(FoodTag.MEAL));
		Long pending = foodAnalysisRepository.save(new FoodAnalysis(savePost("pending.jpg"))).getPostId();
		Long deleted = saveDone("deleted.jpg");
		Post deletedPost = postRepository.findById(deleted).orElseThrow();
		deletedPost.deleteByAuthor();
		postRepository.saveAndFlush(deletedPost);

		List<UntaggedPost> found = mine(foodAnalysisRepository.findUntagged(Instant.now().minus(STALE), 1000),
				older, newer, tagged, pending, deleted);

		assertThat(found).containsExactly(new UntaggedPost(newer, "newer.jpg"), new UntaggedPost(older, "older.jpg"));
	}

	@Test
	void 한_글은_한_번만_맡고_맡은_지_3분이_지나면_다시_맡되_세_번까지다() {
		Long id = saveDone("a.jpg");
		Instant now = Instant.now();

		assertThat(foodAnalysisRepository.claimTagging(id, now, now.minus(STALE))).isEqualTo(1);
		// 다른 app이 바로 또 맡으려 하면 0 (AI를 두 번 부르지 않는다). 맡은 글은 찾지도 않는다
		assertThat(foodAnalysisRepository.claimTagging(id, now.plusSeconds(1), now.plusSeconds(1).minus(STALE))).isZero();
		assertThat(mine(foodAnalysisRepository.findUntagged(now.minus(STALE), 1000), id)).isEmpty();

		// 맡은 지 3분이 지나면(맡은 app이 꺼졌다) 다시 맡는다. 세 번째까지
		for (int attempt = 2; attempt <= 3; attempt++) {
			ageTagClaim(id);
			assertThat(foodAnalysisRepository.claimTagging(id, Instant.now(), Instant.now().minus(STALE))).isEqualTo(1);
		}
		ageTagClaim(id);
		assertThat(foodAnalysisRepository.claimTagging(id, Instant.now(), Instant.now().minus(STALE))).isZero();
		assertThat(mine(foodAnalysisRepository.findUntagged(Instant.now().minus(STALE), 1000), id)).isEmpty();
		assertThat(foodAnalysisRepository.findById(id).orElseThrow().getTagAttempts()).isEqualTo(3);
	}

	@Test
	void 사진으로_태그를_골라_저장하고_채웠다고_적고_다시_찾지_않는다(CapturedOutput output) {
		Long id = saveDone("photo.jpg");
		when(analyzer.classify(JPEG)).thenReturn(new FoodTagsResponse(
				new FoodTags("MEAL", "KOREAN", List.of("RICE"), List.of("MEAT"), true, "HOT"), "test-model", 1300, 40));
		Instant before = Instant.now().minusSeconds(1);

		assertThat(filler.fillOne(new UntaggedPost(id, "photo.jpg"))).isEqualTo(PostTagFiller.Outcome.FILLED);

		verify(storage).load("photo.jpg");
		assertThat(postTagRepository.findByPostIdOrderByTag(id)).extracting(PostTag::getTag)
				.containsExactlyInAnyOrder(FoodTag.MEAL, FoodTag.KOREAN, FoodTag.RICE, FoodTag.MEAT, FoodTag.SPICY, FoodTag.HOT);
		FoodAnalysis row = foodAnalysisRepository.findById(id).orElseThrow();
		assertThat(row.getTaggedAt()).isNotNull();
		assertThat(row.getTagAttempts()).isEqualTo(1);
		// 이 호출도 하루 분석 횟수에 센다
		assertThat(foodAnalysisRepository.countByTagCalledAtGreaterThanEqual(before)).isGreaterThanOrEqualTo(1);
		assertThat(mine(foodAnalysisRepository.findUntagged(Instant.now().plusSeconds(600), 1000), id)).isEmpty();
		assertThat(output).contains("Post tags filled: postId=" + id).contains("inputTokens=1300, outputTokens=40");
	}

	@Test
	void AI가_실패하거나_사진이_없으면_맡은_횟수만_늘고_태그는_없다(CapturedOutput output) {
		Long failing = saveDone("fail.jpg");
		when(analyzer.classify(JPEG)).thenThrow(new FoodAnalysisException("HTTP_503"));
		Long missing = saveDone("missing.jpg");
		when(storage.load("missing.jpg")).thenReturn(Optional.empty());

		assertThat(filler.fillOne(new UntaggedPost(failing, "fail.jpg"))).isEqualTo(PostTagFiller.Outcome.FAILED);
		assertThat(filler.fillOne(new UntaggedPost(missing, "missing.jpg"))).isEqualTo(PostTagFiller.Outcome.FAILED);

		for (Long id : List.of(failing, missing)) {
			assertThat(postTagRepository.findByPostIdOrderByTag(id)).isEmpty();
			FoodAnalysis row = foodAnalysisRepository.findById(id).orElseThrow();
			assertThat(row.getTaggedAt()).isNull();
			assertThat(row.getTagAttempts()).isEqualTo(1);
		}
		assertThat(output).contains("Post tag fill failed: postId=" + failing + ", reason=HTTP_503")
				.contains("Post tag fill failed: postId=" + missing + ", reason=IMAGE_NOT_FOUND");
	}

	@Test
	void 다른_app이_맡은_글은_AI를_부르지_않고_건너뛴다() {
		Long id = saveDone("a.jpg");
		foodAnalysisRepository.claimTagging(id, Instant.now(), Instant.now().minus(STALE));

		assertThat(filler.fillOne(new UntaggedPost(id, "a.jpg"))).isEqualTo(PostTagFiller.Outcome.SKIPPED);

		verify(analyzer, never()).classify(any());
	}

	@Test
	void 오늘_분석_상한에_닿으면_AI를_부르지_않는다(CapturedOutput output) {
		saveDone("a.jpg");
		when(analysisService.isDailyLimitReached()).thenReturn(true);
		when(analysisService.getDailyLimit()).thenReturn(500);

		filler.fill();

		verify(analyzer, never()).classify(any());
		assertThat(output).contains("Post tag fill stopped, daily analysis limit reached: limit=500");
	}

	@Test
	void 태그를_다시_채우면_있던_태그를_바꿔_쓴다() {
		Long id = saveDone("a.jpg");
		postTagService.replace(id, Set.of(FoodTag.MEAL, FoodTag.KOREAN, FoodTag.HOT));

		postTagService.replace(id, Set.of(FoodTag.DRINK, FoodTag.COLD));

		assertThat(postTagRepository.findByPostIdOrderByTag(id)).extracting(PostTag::getTag)
				.containsExactlyInAnyOrder(FoodTag.DRINK, FoodTag.COLD);
	}

	private Long saveDone(String imageKey) {
		FoodAnalysis analysis = new FoodAnalysis(savePost(imageKey));
		FoodAnalysisResult result = new FoodAnalysisResult(true, List.of(new FoodItem("공기밥", 210, 310, 68, 5, 1)), "HIGH", "1공기");
		analysis.markDone(new FoodAnalyzerResponse(result, "test-model", 100, 10), "[{\"name\":\"공기밥\"}]", 1000);
		return foodAnalysisRepository.saveAndFlush(analysis).getPostId();
	}

	private Post savePost(String imageKey) {
		return postRepository.save(new Post("태그시험", "hash", null, imageKey, "thumb-" + imageKey, null));
	}

	// 맡은 시각을 4분 전으로 돌려놓는다 (맡은 app이 꺼진 것처럼)
	// JPQL로 바꾼다: 시각을 앱과 같은 방식(Hibernate의 Instant 저장)으로 적는다
	private void ageTagClaim(Long id) {
		entityManager.flush();
		entityManager.createQuery("update FoodAnalysis a set a.tagCalledAt = :t where a.postId = :id")
				.setParameter("t", Instant.now().minus(Duration.ofMinutes(4))).setParameter("id", id).executeUpdate();
		entityManager.clear();
	}

	// 찾은 글 가운데 이 테스트가 만든 글만 (개발 DB의 다른 글은 뺀다)
	private static List<UntaggedPost> mine(List<UntaggedPost> found, Long... ids) {
		Set<Long> own = Set.of(ids);
		return found.stream().filter(post -> own.contains(post.postId())).toList();
	}
}
