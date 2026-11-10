package backend.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

// 정책 페이지(이용약관, 개인정보처리방침)가 열리고, 문의 이메일이 화면에 넘어가는지 확인한다
@SpringBootTest
@AutoConfigureMockMvc
class PolicyControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void 이용약관_화면이_열린다() throws Exception {
		mockMvc.perform(get("/terms"))
				.andExpect(status().isOk())
				.andExpect(view().name("common/terms"))
				.andExpect(model().attributeExists("contactEmail"));
	}

	@Test
	void 개인정보처리방침_화면이_열린다() throws Exception {
		mockMvc.perform(get("/privacy"))
				.andExpect(status().isOk())
				.andExpect(view().name("common/privacy"))
				.andExpect(model().attributeExists("contactEmail"));
	}
}
