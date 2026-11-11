package backend.post.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import backend.post.model.entity.Post;
import backend.post.model.repository.PostRepository;

// 좋아요 API를 실제로 불러 보는 테스트 (JM-59)
// 글 하나가 1분에 받을 수 있는 좋아요를 3번으로 낮춰서 횟수 제한까지 빠르게 확인한다.
// 횟수는 서버 메모리에 글 번호별로 쌓인다. 테스트마다 새 글을 만들어 쓰므로 서로 영향을 주지 않는다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
		"app.rate-limit.like-per-post-per-minute=3",
		"app.rate-limit.like-per-post-per-day=30" })
class PostLikeApiTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	void 좋아요를_누르면_1_오르고_오른_수를_돌려준다() throws Exception {
		Post post = savePost();

		mockMvc.perform(post(likeUrl(post)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.likeCount").value(1));
		mockMvc.perform(post(likeUrl(post)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.likeCount").value(2));

		assertThat(likeCountOf(post)).isEqualTo(2);
	}

	@Test
	void 없는_글이나_삭제된_글에는_좋아요를_누를_수_없다() throws Exception {
		mockMvc.perform(post("/api/posts/999999999/like"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));

		Post deleted = savePost();
		deleted.deleteByAuthor();
		postRepository.saveAndFlush(deleted);

		mockMvc.perform(post(likeUrl(deleted))).andExpect(status().isNotFound());
		assertThat(likeCountOf(deleted)).isZero();
	}

	@Test
	void 한_글에_1분_제한을_넘게_눌리면_429로_거절하고_숫자는_오르지_않는다() throws Exception {
		Post post = savePost();

		// 1~3번째: 통과
		for (int i = 0; i < 3; i++) {
			mockMvc.perform(post(likeUrl(post))).andExpect(status().isOk());
		}
		// 4번째: 막힘
		mockMvc.perform(post(likeUrl(post)))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("TOO_MANY_LIKES"));
		assertThat(likeCountOf(post)).isEqualTo(3);
	}

	@Test
	void 횟수는_글마다_따로_센다() throws Exception {
		Post busy = savePost();
		for (int i = 0; i < 3; i++) {
			mockMvc.perform(post(likeUrl(busy))).andExpect(status().isOk());
		}
		mockMvc.perform(post(likeUrl(busy))).andExpect(status().isTooManyRequests());

		// 한 글이 막혀도 다른 글에는 누를 수 있다
		Post other = savePost();
		mockMvc.perform(post(likeUrl(other)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.likeCount").value(1));
	}

	private String likeUrl(Post post) {
		return "/api/posts/" + post.getId() + "/like";
	}

	// DB에 저장된 좋아요 수를 다시 읽는다 (UPDATE로 올린 값이라 메모리의 post 객체에는 반영되지 않는다)
	private int likeCountOf(Post post) {
		return postRepository.findById(post.getId()).orElseThrow().getLikeCount();
	}

	private Post savePost() {
		return postRepository.save(new Post("테스터", passwordEncoder.encode("1234"), null, "test.jpg", "test-thumb.jpg", null));
	}
}
