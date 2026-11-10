package backend.admin.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import backend.admin.AdminTokenInterceptor;

// 운영자 토큰이 설정되어 있지 않을 때(기본값 = 개발 PC, CI)는 어떤 토큰을 보내도 운영자 기능이 닫혀 있어야 한다.
@SpringBootTest
@AutoConfigureMockMvc
class AdminApiDisabledTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void 토큰이_설정되어_있지_않으면_빈_토큰을_보내도_403이다() throws Exception {
		mockMvc.perform(post("/api/admin/posts/1/delete").header(AdminTokenInterceptor.HEADER, ""))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ADMIN_DISABLED"));

		mockMvc.perform(post("/api/admin/posts/1/delete"))
				.andExpect(status().isForbidden());
	}
}
