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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import backend.common.error.ErrorCode;

// 글 올리기 횟수 제한이 실제 요청에서 걸리는지 검사하는 테스트 (세는 규칙 자체는 UploadLimiterTest)
// 브라우저 1분 제한을 2번, IP 하루 상한을 4번으로 낮춰서 빠르게 확인한다. 사진은 넣지 않아서 글은 저장되지 않는다(글쓰기 화면으로 돌아감).
// → 제한은 "올리기 시도" 자체를 세므로, 저장이 되든 안 되든 횟수에 들어간다.
// 브라우저는 세션으로 구분한다: 같은 MockHttpSession을 넘기면 같은 브라우저, 넘기지 않으면 요청마다 새 브라우저다.
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
		"app.rate-limit.upload-per-browser-per-minute=2",
		"app.rate-limit.upload-per-browser-per-day=30",
		"app.rate-limit.upload-per-ip-per-day=4",
		"app.rate-limit.upload-total-per-day=1000000" })
class UploadRateLimitTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void 같은_브라우저가_1분_제한을_넘으면_429_에러_화면을_보여준다() throws Exception {
		MockHttpSession browser = new MockHttpSession();

		// 1, 2번째: 통과 (사진이 없어서 글쓰기 화면으로 돌아가지만, 막히지는 않음)
		mockMvc.perform(upload("10.0.0.1").session(browser)).andExpect(status().isOk());
		mockMvc.perform(upload("10.0.0.1").session(browser)).andExpect(status().isOk());

		// 3번째: 막힘
		mockMvc.perform(upload("10.0.0.1").session(browser))
				.andExpect(status().isTooManyRequests())
				.andExpect(view().name("common/error"))
				.andExpect(model().attribute("message", ErrorCode.TOO_MANY_UPLOADS.getMessage()));
	}

	// 2026-11-13 발표 때 막혔던 경우: 같은 와이파이(IP 하나)의 여러 사람이 한꺼번에 올린다
	@Test
	void 같은_와이파이의_여러_브라우저는_1분_제한을_나눠_쓰지_않는다() throws Exception {
		for (int person = 0; person < 4; person++) {
			mockMvc.perform(upload("10.0.0.2")).andExpect(status().isOk());
		}
	}

	@Test
	void 같은_IP의_하루_상한을_넘으면_브라우저가_달라도_막힌다() throws Exception {
		for (int person = 0; person < 4; person++) {
			mockMvc.perform(upload("10.0.0.3")).andExpect(status().isOk());
		}

		mockMvc.perform(upload("10.0.0.3"))
				.andExpect(status().isTooManyRequests())
				.andExpect(model().attribute("message", ErrorCode.TOO_MANY_UPLOADS_TODAY.getMessage()));

		// 10.0.0.3이 막혀도 다른 IP는 올릴 수 있다.
		mockMvc.perform(upload("10.0.0.4")).andExpect(status().isOk());
	}

	@Test
	void 글쓰기_화면을_여는_것은_세지_않는다() throws Exception {
		// GET은 몇 번을 열어도 막히지 않는다.
		MockHttpSession browser = new MockHttpSession();
		for (int i = 0; i < 5; i++) {
			mockMvc.perform(get("/posts/new").session(browser).with(request -> {
				request.setRemoteAddr("10.0.0.5");
				return request;
			})).andExpect(status().isOk());
		}
	}

	// 사진 없이 글 올리기를 흉내 낸다. with(...): 가짜 요청의 보낸 쪽 IP를 바꾼다.
	private MockMultipartHttpServletRequestBuilder upload(String ip) {
		MockMultipartHttpServletRequestBuilder builder = multipart("/posts");
		builder.param("nickname", "테스터")
				.param("password", "1234")
				.with(request -> {
					request.setRemoteAddr(ip);
					return request;
				});
		return builder;
	}
}
