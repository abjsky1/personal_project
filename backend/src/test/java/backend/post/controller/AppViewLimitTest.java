package backend.post.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import backend.post.model.entity.Post;
import backend.post.model.repository.PostRepository;

// 앱 조회 기록의 하루 상한(AppViewDailyLimit)이 실제 API에 걸리는지 (JM-90, 기획서 19.3). 상한을 2로 낮춰서 본다.
// 오늘 받은 수는 서버 메모리에 쌓인다. 이 설정을 쓰는 테스트가 이 클래스 하나뿐이라 0부터 센다
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.app-view.daily-limit=2")
class AppViewLimitTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void 하루_상한을_넘은_기록은_남기지_않고_응답은_그대로_204다() throws Exception {
		String key = UUID.randomUUID().toString();
		Post post = postRepository.save(new Post("앱시험", "hash", null, key + ".jpg", key + "-thumb.jpg", null));

		for (int i = 0; i < 3; i++) {
			mockMvc.perform(post("/api/posts/" + post.getId() + "/app-view").header("User-Agent", "JjikmukApp/1.0.0+1 (IOS 27.2; DEBUG)"))
					.andExpect(status().isNoContent());
		}

		Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_post_views WHERE post_id = ?", Integer.class, post.getId());
		assertThat(rows).isEqualTo(2);
	}
}
