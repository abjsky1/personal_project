package backend.notice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

// 공지 페이지(JM-65)가 열리는지 확인한다. 공지 글은 JSP에 있어서 화면 내용은 로컬과 배포 뒤 사진으로 확인한다
@SpringBootTest
@AutoConfigureMockMvc
class NoticeControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void 공지_화면이_열린다() throws Exception {
		mockMvc.perform(get("/notice"))
				.andExpect(status().isOk())
				.andExpect(view().name("notice/list"));
	}
}
