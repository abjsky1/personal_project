package backend.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import backend.analysis.model.dto.FoodAnalysisResult;
import backend.analysis.model.dto.FoodAnalyzerResponse;
import backend.analysis.model.entity.FoodAnalysis;
import backend.analysis.model.repository.FoodAnalysisRepository;
import backend.post.model.entity.Post;
import backend.post.model.repository.PostRepository;

// 사진 주소(/images/파일이름)가 사이트에 보이는 글의 사진만 저장소에서 꺼내 보내는지 검사하는 테스트 (JM-95)
// 테스트에서는 app.image-storage 설정이 없으므로 내 PC 폴더 저장소(LocalImageStorage)가 쓰인다.
// 사진 주소는 글을 JdbcTemplate으로 찾는다 → 테스트에서 만든 글과 바꾼 값은 flush로 DB에 먼저 보낸다 (테스트가 끝나면 되돌아간다)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.upload-dir=build/test-uploads")
class ImageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ImageStorage imageStorage;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private FoodAnalysisRepository foodAnalysisRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void 설정이_없으면_내_PC_폴더_저장소를_쓴다() {
		assertThat(imageStorage).isInstanceOf(LocalImageStorage.class);
	}

	@Test
	void 보이는_글의_사진과_썸네일은_JPEG로_보내고_브라우저가_오래_저장해_두게_한다() throws Exception {
		byte[] image = TestImages.jpeg(50, 50);
		byte[] thumbnail = TestImages.jpeg(20, 20);
		Post post = savePost(imageStorage.saveJpeg(image), imageStorage.saveJpeg(thumbnail));

		mockMvc.perform(get("/images/" + post.getImageKey()))
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.IMAGE_JPEG))
				.andExpect(content().bytes(image))
				// max-age=31536000 = 365일(초 단위)
				.andExpect(header().string("Cache-Control", Matchers.containsString("max-age=31536000")));
		mockMvc.perform(get("/images/" + post.getThumbnailKey()))
				.andExpect(status().isOk())
				.andExpect(content().bytes(thumbnail));
	}

	@Test
	void 분석_중이거나_분석이_끝난_글의_사진도_보낸다() throws Exception {
		Post pending = savePost(savedPhoto(), savedPhoto());
		foodAnalysisRepository.saveAndFlush(new FoodAnalysis(pending));
		Post approved = notFoodPost();
		approved.approvePhoto();
		postRepository.flush();

		mockMvc.perform(get("/images/" + pending.getThumbnailKey())).andExpect(status().isOk());
		mockMvc.perform(get("/images/" + approved.getThumbnailKey())).andExpect(status().isOk());
	}

	@Test
	void 지운_글의_사진과_썸네일은_404이고_브라우저가_기억하지_않게_한다() throws Exception {
		Post byAuthor = savePost(savedPhoto(), savedPhoto());
		byAuthor.deleteByAuthor();
		Post byAdmin = savePost(savedPhoto(), savedPhoto());
		byAdmin.deleteByAdmin();
		postRepository.flush();

		for (String key : List.of(byAuthor.getImageKey(), byAuthor.getThumbnailKey(), byAdmin.getImageKey(), byAdmin.getThumbnailKey())) {
			mockMvc.perform(get("/images/" + key))
					.andExpect(status().isNotFound())
					.andExpect(header().string("Cache-Control", "no-store"));
		}
		// 파일은 저장소에 그대로 있다 (지운 글의 사진 파일은 지우지 않는다, 기획서 5.1절). 주소로만 내주지 않는다
		assertThat(imageStorage.load(byAuthor.getImageKey())).isPresent();
	}

	@Test
	void 가린_글의_사진은_404이고_운영자가_올리면_같은_주소가_바로_200이다() throws Exception {
		Post held = notFoodPost();

		mockMvc.perform(get("/images/" + held.getImageKey()))
				.andExpect(status().isNotFound())
				.andExpect(header().string("Cache-Control", "no-store"));
		mockMvc.perform(get("/images/" + held.getThumbnailKey())).andExpect(status().isNotFound());

		held.approvePhoto();
		postRepository.flush();

		mockMvc.perform(get("/images/" + held.getImageKey())).andExpect(status().isOk());
		mockMvc.perform(get("/images/" + held.getThumbnailKey())).andExpect(status().isOk());
	}

	@Test
	void 어느_글의_사진도_아닌_파일과_파일이_없는_글은_404() throws Exception {
		String orphan = savedPhoto();
		Post missing = savePost(UUID.randomUUID() + ".jpg", UUID.randomUUID() + ".jpg");

		mockMvc.perform(get("/images/" + orphan)).andExpect(status().isNotFound());
		mockMvc.perform(get("/images/" + missing.getImageKey()))
				.andExpect(status().isNotFound())
				.andExpect(header().string("Cache-Control", "no-store"));
	}

	@Test
	void 우리가_만든_이름_모양이_아니면_찾아보지도_않고_404() throws Exception {
		// 확장자가 다르거나, 무작위 이름(UUID) 모양이 아닌 경우
		mockMvc.perform(get("/images/photo.png"))
				.andExpect(status().isNotFound())
				.andExpect(header().string("Cache-Control", "no-store"));
		mockMvc.perform(get("/images/application.properties")).andExpect(status().isNotFound());
	}

	// 사진 요청마다 사진 이름으로 글을 찾으므로 두 이름에 색인이 있어야 한다 (Post의 @Table, ddl-auto=update가 만든다)
	@Test
	void 사진_이름으로_글을_찾는_색인이_있다() {
		List<String> columns = jdbcTemplate.queryForList("SELECT column_name FROM information_schema.statistics"
				+ " WHERE table_schema = DATABASE() AND table_name = 'posts'"
				+ " AND index_name IN ('idx_posts_image_key', 'idx_posts_thumbnail_key')", String.class);
		assertThat(columns).containsExactlyInAnyOrder("image_key", "thumbnail_key");
	}

	private String savedPhoto() {
		return imageStorage.saveJpeg(TestImages.jpeg(10, 10));
	}

	private Post savePost(String imageKey, String thumbnailKey) {
		return postRepository.saveAndFlush(new Post("사진시험", "hash", null, imageKey, thumbnailKey, null));
	}

	// AI가 음식이 아니라고 본 글 (JM-80): 운영자가 올리기 전까지 사진을 가린다
	private Post notFoodPost() {
		Post post = savePost(savedPhoto(), savedPhoto());
		FoodAnalysis analysis = new FoodAnalysis(post);
		analysis.markNotFood(new FoodAnalyzerResponse(
				new FoodAnalysisResult(false, List.of(), "HIGH", "음식이 아니에요"), "test-model", 1300, 50), 1500);
		foodAnalysisRepository.saveAndFlush(analysis);
		return post;
	}
}
