package backend.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

// 사진 주소(/images/파일이름)가 저장소에서 사진을 꺼내 제대로 보내 주는지 검사하는 테스트
// 테스트에서는 app.image-storage 설정이 없으므로 내 PC 폴더 저장소(LocalImageStorage)가 쓰인다.
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.upload-dir=build/test-uploads")
class ImageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ImageStorage imageStorage;

	@Test
	void 설정이_없으면_내_PC_폴더_저장소를_쓴다() {
		assertThat(imageStorage).isInstanceOf(LocalImageStorage.class);
	}

	@Test
	void 저장한_사진을_JPEG로_보내고_브라우저가_오래_저장해_두게_한다() throws Exception {
		byte[] jpeg = TestImages.jpeg(50, 50);
		String key = imageStorage.saveJpeg(jpeg);

		mockMvc.perform(get("/images/" + key))
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.IMAGE_JPEG))
				.andExpect(content().bytes(jpeg))
				// max-age=31536000 = 365일(초 단위)
				.andExpect(header().string("Cache-Control", Matchers.containsString("max-age=31536000")));
	}

	@Test
	void 없는_사진은_404() throws Exception {
		mockMvc.perform(get("/images/00000000-0000-0000-0000-000000000000.jpg"))
				.andExpect(status().isNotFound());
	}

	@Test
	void 우리가_만든_이름_모양이_아니면_찾아보지도_않고_404() throws Exception {
		// 확장자가 다르거나, 무작위 이름(UUID) 모양이 아닌 경우
		mockMvc.perform(get("/images/photo.png")).andExpect(status().isNotFound());
		mockMvc.perform(get("/images/application.properties")).andExpect(status().isNotFound());
	}
}
