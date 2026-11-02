package backend.analysis.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import backend.analysis.model.entity.FoodAnalysis;
import backend.analysis.model.repository.FoodAnalysisRepository;
import backend.post.model.entity.Post;
import backend.post.model.repository.PostRepository;

// 상세 화면이 2초마다 부르는 분석 상태 API 테스트
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FoodAnalysisApiControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private FoodAnalysisRepository foodAnalysisRepository;

	@Test
	void 분석_중이면_PENDING을_알려준다() throws Exception {
		Post post = savePost();
		foodAnalysisRepository.save(new FoodAnalysis(post));

		mockMvc.perform(get("/api/posts/" + post.getId() + "/analysis"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PENDING"));
	}

	@Test
	void 끝났으면_바뀐_상태를_알려준다() throws Exception {
		Post post = savePost();
		FoodAnalysis analysis = new FoodAnalysis(post);
		analysis.markFailed("HTTP_503", 100);
		foodAnalysisRepository.save(analysis);

		mockMvc.perform(get("/api/posts/" + post.getId() + "/analysis"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"))
				// 실패 이유 같은 내부 정보는 내보내지 않는다.
				.andExpect(jsonPath("$.errorMessage").doesNotExist());
	}

	@Test
	void 없는_글은_JSON으로_404를_돌려준다() throws Exception {
		mockMvc.perform(get("/api/posts/999999999/analysis"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void 삭제된_글은_분석_기록이_있어도_404를_돌려준다() throws Exception {
		Post post = savePost();
		foodAnalysisRepository.save(new FoodAnalysis(post));
		post.deleteByAuthor();

		mockMvc.perform(get("/api/posts/" + post.getId() + "/analysis"))
				.andExpect(status().isNotFound());
	}

	@Test
	void 분석_기록이_없는_옛날_글은_404를_돌려준다() throws Exception {
		Post post = savePost();

		mockMvc.perform(get("/api/posts/" + post.getId() + "/analysis"))
				.andExpect(status().isNotFound());
	}

	private Post savePost() {
		return postRepository.save(new Post("테스터", "hash", null, "test.jpg", "test-thumb.jpg", null));
	}
}
