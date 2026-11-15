package backend.post.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import backend.admin.AdminTokenInterceptor;
import backend.analysis.model.dto.FoodAnalysisResult;
import backend.analysis.model.dto.FoodAnalysisResult.FoodItem;
import backend.analysis.model.dto.FoodAnalyzerResponse;
import backend.analysis.model.entity.FoodAnalysis;
import backend.analysis.model.repository.FoodAnalysisRepository;
import backend.post.model.dto.FeedPage;
import backend.post.model.dto.PostSummary;
import backend.post.model.entity.Post;
import backend.post.model.repository.PostRepository;
import backend.post.service.PostService;

// 사진 검토 (JM-80): AI가 음식이 아니라고 본 글은 운영자가 올리기 전까지 피드와 글 화면에서 사진을 가린다.
// MockMvc는 JSP를 그리지 않으므로 화면에 넘기는 값(photoHeld)으로 확인한다. 그려진 화면은 로컬 서버에서 확인했다 (SPRINTS 기록)
// @TestPropertySource: 운영자 API(사진 올리기)를 부르려고 이 테스트에서만 운영자 토큰을 정해 둔다
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.admin.token=test-admin-token")
class PhotoReviewTest {

	private static final String TOKEN = "test-admin-token";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private FoodAnalysisRepository foodAnalysisRepository;

	@Autowired
	private PostService postService;

	@Test
	void AI가_음식이_아니라고_본_글만_피드와_글_화면에서_사진을_가린다() throws Exception {
		Post held = notFoodPost();
		Post done = donePost();
		// 분석 기록이 없는 옛날 글
		Post old = savePost();

		FeedPage feed = (FeedPage) mockMvc.perform(get("/")).andExpect(status().isOk()).andReturn()
				.getModelAndView().getModel().get("feed");
		assertThat(summaryOf(feed, held).photoHeld()).isTrue();
		assertThat(summaryOf(feed, done).photoHeld()).isFalse();
		assertThat(summaryOf(feed, old).photoHeld()).isFalse();

		assertThat(detailOf(held).photoHeld()).isTrue();
		assertThat(detailOf(done).photoHeld()).isFalse();
	}

	@Test
	void 운영자가_올리면_사진이_다시_보이고_가린_글이_아니면_409다() throws Exception {
		Post held = notFoodPost();

		mockMvc.perform(post("/api/admin/posts/" + held.getId() + "/approve").header(AdminTokenInterceptor.HEADER, TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(held.getId()))
				.andExpect(jsonPath("$.photoHeld").value(false));
		assertThat(postRepository.findById(held.getId()).orElseThrow().getPhotoApprovedAt()).isNotNull();
		assertThat(detailOf(held).photoHeld()).isFalse();

		// 이미 올린 글과 음식으로 본 글은 올릴 것이 없다
		mockMvc.perform(post("/api/admin/posts/" + held.getId() + "/approve").header(AdminTokenInterceptor.HEADER, TOKEN))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("POST_NOT_HELD"));
		mockMvc.perform(post("/api/admin/posts/" + donePost().getId() + "/approve").header(AdminTokenInterceptor.HEADER, TOKEN))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("POST_NOT_HELD"));
	}

	@Test
	void 토큰이_없으면_사진을_올리지_못한다() throws Exception {
		Post held = notFoodPost();

		mockMvc.perform(post("/api/admin/posts/" + held.getId() + "/approve"))
				.andExpect(status().isUnauthorized());

		assertThat(postRepository.findById(held.getId()).orElseThrow().getPhotoApprovedAt()).isNull();
	}

	@Test
	void 검토할_사진에는_가린_글만_최신순으로_나온다() {
		Post first = notFoodPost();
		Post second = notFoodPost();
		Post approved = notFoodPost();
		approved.approvePhoto();
		Post deleted = notFoodPost();
		deleted.deleteByAdmin();
		Post done = donePost();

		List<PostSummary> held = postService.getPhotoHeldPosts();

		// 개발 DB에 가린 글이 이미 있어도 되도록, 이번에 만든 글의 순서와 빠진 글만 본다
		assertThat(held).extracting(PostSummary::id)
				.containsSubsequence(second.getId(), first.getId())
				.doesNotContain(approved.getId(), deleted.getId(), done.getId());
		assertThat(held).allMatch(PostSummary::photoHeld);
	}

	private Post savePost() {
		String key = UUID.randomUUID().toString();
		return postRepository.save(new Post("검토시험", "hash", null, key + ".jpg", key + "-thumb.jpg", null));
	}

	private Post notFoodPost() {
		Post post = savePost();
		FoodAnalysis analysis = new FoodAnalysis(post);
		analysis.markNotFood(new FoodAnalyzerResponse(
				new FoodAnalysisResult(false, List.of(), "HIGH", "음식이 아니에요"), "test-model", 1300, 50), 1500);
		foodAnalysisRepository.save(analysis);
		return post;
	}

	private Post donePost() {
		Post post = savePost();
		FoodAnalysis analysis = new FoodAnalysis(post);
		analysis.markDone(new FoodAnalyzerResponse(
				new FoodAnalysisResult(true, List.of(new FoodItem("공기밥", 210, 310, 68, 5, 1)), "HIGH", "1공기"),
				"test-model", 1300, 100), "[]", 2000);
		foodAnalysisRepository.save(analysis);
		return post;
	}

	private PostSummary detailOf(Post post) throws Exception {
		return (PostSummary) mockMvc.perform(get("/posts/" + post.getId())).andExpect(status().isOk()).andReturn()
				.getModelAndView().getModel().get("post");
	}

	private static PostSummary summaryOf(FeedPage feed, Post post) {
		return feed.posts().stream().filter(summary -> summary.id().equals(post.getId())).findFirst().orElseThrow();
	}
}
