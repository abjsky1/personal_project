package backend.admin.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

// 관리자 비밀번호를 설정하지 않으면 관리자 화면은 없는 주소로 다룬다 (JM-82, AdminApiDisabledTest와 같은 생각)
// 개발 PC의 .env에 로컬 비밀번호를 넣어 두어도 이 테스트는 비어 있는 상태로 돈다 (@TestPropertySource로 비운다)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.admin.password=")
class AdminPageDisabledTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void 비밀번호가_없으면_관리자_화면은_404다() throws Exception {
		mockMvc.perform(get("/admin/login")).andExpect(status().isNotFound());
		mockMvc.perform(post("/admin/login").param("password", "")).andExpect(status().isNotFound());
		mockMvc.perform(get("/admin/posts")).andExpect(status().isNotFound());
	}
}
