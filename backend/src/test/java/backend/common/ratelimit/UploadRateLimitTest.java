package backend.common.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import backend.common.error.ErrorCode;

// 같은 IP에서 글을 너무 자주 올리면 막히는지 검사하는 테스트
// 1분 제한을 2번으로 낮춰서 빠르게 확인한다. 사진은 넣지 않아서 글은 저장되지 않는다(글쓰기 화면으로 돌아감).
// → 제한은 "올리기 시도" 자체를 세므로, 저장이 되든 안 되든 횟수에 들어간다.
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
		"app.rate-limit.upload-per-minute=2",
		"app.rate-limit.upload-per-day=30" })
class UploadRateLimitTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void 같은_IP에서_1분_제한을_넘으면_429_에러_화면을_보여준다() throws Exception {
		// 1, 2번째: 통과 (사진이 없어서 글쓰기 화면으로 돌아가지만, 막히지는 않음)
		mockMvc.perform(uploadFrom("10.0.0.1")).andExpect(status().isOk());
		mockMvc.perform(uploadFrom("10.0.0.1")).andExpect(status().isOk());

		// 3번째: 막힘
		mockMvc.perform(uploadFrom("10.0.0.1"))
				.andExpect(status().isTooManyRequests())
				.andExpect(view().name("common/error"))
				.andExpect(model().attribute("message", ErrorCode.TOO_MANY_UPLOADS.getMessage()));
	}

	@Test
	void 다른_IP는_따로_센다() throws Exception {
		mockMvc.perform(uploadFrom("10.0.0.2")).andExpect(status().isOk());
		mockMvc.perform(uploadFrom("10.0.0.2")).andExpect(status().isOk());
		mockMvc.perform(uploadFrom("10.0.0.2")).andExpect(status().isTooManyRequests());

		// 10.0.0.2가 막혀도 10.0.0.3은 올릴 수 있다.
		mockMvc.perform(uploadFrom("10.0.0.3")).andExpect(status().isOk());
	}

	@Test
	void 글쓰기_화면을_여는_것은_세지_않는다() throws Exception {
		// GET은 몇 번을 열어도 막히지 않는다.
		for (int i = 0; i < 5; i++) {
			mockMvc.perform(get("/posts/new").with(request -> {
				request.setRemoteAddr("10.0.0.4");
				return request;
			})).andExpect(status().isOk());
		}
	}

	// 사진 없이 글 올리기를 흉내 낸다. with(...): 가짜 요청의 보낸 쪽 IP를 바꾼다.
	// RequestBuilder: mockMvc.perform()이 받는 "요청을 만드는 도구"의 공통 타입
	private RequestBuilder uploadFrom(String ip) {
		return multipart("/posts")
				.param("nickname", "테스터")
				.param("password", "1234")
				.with(request -> {
					request.setRemoteAddr(ip);
					return request;
				});
	}
}
