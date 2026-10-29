package backend.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;

// 화면 요청에서 에러가 나면 JSON이 아니라 에러 화면(common/error.jsp)을 알맞은 상태로 보여 주는지 검사하는 테스트
// (MockMvc는 JSP를 실제로 그리지 않고, 어떤 화면 이름과 값이 넘어갔는지만 확인한다)
@SpringBootTest
@AutoConfigureMockMvc
@Import(PageExceptionHandlerTest.PageTestController.class)
class PageExceptionHandlerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void 없는_주소는_404_에러_화면을_보여준다() throws Exception {
		mockMvc.perform(get("/no-such-page"))
				.andExpect(status().isNotFound())
				.andExpect(view().name("common/error"))
				.andExpect(model().attribute("message", ErrorCode.NOT_FOUND.getMessage()));
	}

	@Test
	void 지원하지_않는_방식은_405_에러_화면을_보여준다() throws Exception {
		// 첫 화면(/)은 GET만 있는데 POST로 보낸다.
		mockMvc.perform(post("/"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(view().name("common/error"));
	}

	@Test
	void 주소의_숫자_자리에_글자가_오면_400_에러_화면을_보여준다() throws Exception {
		// /posts/{id}의 id는 숫자여야 하는데 글자를 넣는다. 500(서버 오류)이 아니라 400(요청 잘못)이어야 한다.
		mockMvc.perform(get("/posts/abc"))
				.andExpect(status().isBadRequest())
				.andExpect(view().name("common/error"));
	}

	@Test
	void 예상하지_못한_예외는_500_에러_화면을_보여주고_traceId를_함께_넘긴다() throws Exception {
		// 테스트 출력에 ERROR 로그와 스택 트레이스가 찍히는 것은 정상이다. (500은 원인을 로그에 남기도록 만들었다)
		MvcResult result = mockMvc.perform(get("/test/page/unexpected-error"))
				.andExpect(status().isInternalServerError())
				.andExpect(view().name("common/error"))
				.andExpect(model().attribute("message", ErrorCode.INTERNAL_ERROR.getMessage()))
				.andReturn();

		// 화면에 보여 줄 번호가 응답 헤더(X-Trace-Id)의 번호와 같아야, 사용자가 알려 준 번호로 로그를 찾을 수 있다.
		String headerTraceId = result.getResponse().getHeader("X-Trace-Id");
		assertThat(result.getModelAndView().getModel().get("traceId")).isEqualTo(headerTraceId);
	}

	// 테스트 전용 화면 Controller: 일부러 예외를 던진다.
	@Controller
	static class PageTestController {

		@GetMapping("/test/page/unexpected-error")
		String unexpectedError() {
			throw new IllegalStateException("사용자에게 보이면 안 되는 내부 내용");
		}
	}
}
