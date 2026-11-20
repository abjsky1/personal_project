package backend.common.error;

import static org.assertj.core.api.Assertions.assertThat;
// get(): 가짜 요청을 만드는 메서드 / status(), jsonPath(): 응답을 검사하는 메서드
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.apache.tomcat.util.http.InvalidParameterException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jayway.jsonpath.JsonPath;

// JSON API(@RestController)에서 에러가 나면 화면이 아니라 ErrorResponse 모양의 JSON으로 응답하는지 검사하는 테스트
// (화면 요청의 에러는 PageExceptionHandlerTest에서 검사한다)
//
// @SpringBootTest: 실제 앱처럼 Spring 전체를 띄워서 테스트한다. (DB도 연결되므로 로컬은 Docker MySQL, CI는 compose MySQL을 쓴다)
// @AutoConfigureMockMvc: 서버 포트를 열지 않고도 요청을 보내 볼 수 있는 MockMvc를 준비한다. 필터(TraceIdFilter)도 함께 거친다.
// @Import: 아래에 만든 테스트 전용 Controller를 이 테스트에서만 등록한다. (실제 앱에는 들어가지 않는다)
@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiExceptionHandlerTest.ApiTestController.class)
class ApiExceptionHandlerTest {

	// @Autowired: Spring이 준비해 둔 MockMvc 객체를 이 필드에 넣어 준다.
	@Autowired
	private MockMvc mockMvc;

	@Test
	void BusinessException은_ErrorCode에_적힌_상태와_코드로_응답한다() throws Exception {
		// perform(요청): 요청을 보낸다 → andExpect(검사): 응답이 기대와 같은지 하나씩 확인한다.
		// jsonPath("$.code"): 응답 JSON에서 code 값을 꺼낸다. ($는 JSON 전체를 뜻한다)
		mockMvc.perform(get("/test/api/business-error"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("POST_NOT_FOUND"))
				.andExpect(jsonPath("$.message").value(ErrorCode.POST_NOT_FOUND.getMessage()));
	}

	@Test
	void Spring_기본_예외는_500이_아니라_원래_상태로_응답한다() throws Exception {
		// 필수 값(q)을 빼고 보낸다 → Spring이 "값 누락" 예외(400)를 던진다.
		// 모든 예외를 500으로 바꾸는 처리만 있으면 여기서 500이 나온다. (S0에서 확인한 함정)
		mockMvc.perform(get("/test/api/needs-param"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	void 예상하지_못한_예외는_500으로_응답하고_예외_내용은_보내지_않는다() throws Exception {
		// 이 테스트를 실행하면 테스트 출력에 ERROR 로그와 스택 트레이스가 찍힌다. 4번 처리 메서드가 로그를 남긴 것이므로 정상이다.
		// message가 정해 둔 문장과 같다 = 예외에 담긴 내부 내용(아래 테스트 Controller의 문장)이 응답에 나가지 않았다.
		mockMvc.perform(get("/test/api/unexpected-error"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.message").value(ErrorCode.INTERNAL_ERROR.getMessage()));
	}

	@Test
	void 에러_응답의_traceId는_응답_헤더의_traceId와_같다() throws Exception {
		// andReturn(): 검사 대신 응답 전체를 꺼낸다 → 헤더와 본문의 값을 직접 비교하려고
		MvcResult result = mockMvc.perform(get("/test/api/business-error")).andReturn();

		String headerTraceId = result.getResponse().getHeader("X-Trace-Id");
		String bodyTraceId = JsonPath.read(result.getResponse().getContentAsString(), "$.traceId");

		// 사용자가 받은 번호(본문)로 서버 로그(헤더와 같은 번호)를 찾을 수 있어야 한다.
		assertThat(headerTraceId).hasSize(8);
		assertThat(bodyTraceId).isEqualTo(headerTraceId);
	}

	@Test
	void Tomcat이_값을_읽지_못한_예외는_예외에_담긴_400번대로_응답한다() throws Exception {
		// 깨진 주소 값(?page=%zz)이면 Tomcat이 InvalidParameterException을 던진다 (JM-107).
		// MockMvc는 Tomcat을 거치지 않아서 테스트 Controller가 같은 예외를 직접 던진다. 실제 Tomcat으로 보내 보는 것은 BrokenRequestValueTest
		mockMvc.perform(get("/test/api/invalid-parameter"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		// form이 너무 크면 Tomcat이 413을 담아 둔다
		mockMvc.perform(get("/test/api/form-too-large"))
				.andExpect(status().is(413))
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	void JSON을_받지_않는다고_한_요청에도_오류는_JSON으로_응답한다() throws Exception {
		// accept(TEXT_HTML): 요청 머리의 Accept를 text/html로만 보낸다 (*/* 없음)
		// 전에는 오류 JSON을 쓰지 못해 처리가 실패하고 500과 ERROR가 났다 (JM-107)
		mockMvc.perform(get("/test/api/business-error").accept(MediaType.TEXT_HTML))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
		// Spring 기본 예외(값 누락)도 같다
		mockMvc.perform(get("/test/api/needs-param").accept(MediaType.TEXT_HTML))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	// 테스트 전용 JSON API: 일부러 예외를 던져서 ApiExceptionHandler가 어떻게 응답하는지 확인한다.
	// static: 바깥 테스트 클래스 객체 없이 Spring이 따로 만들 수 있게 한다.
	@RestController
	static class ApiTestController {

		@GetMapping("/test/api/business-error")
		void businessError() {
			throw new BusinessException(ErrorCode.POST_NOT_FOUND);
		}

		// @RequestParam: 주소의 ?q=값 이 꼭 있어야 한다. 없으면 Spring이 예외를 던진다.
		@GetMapping("/test/api/needs-param")
		String needsParam(@RequestParam String q) {
			return q;
		}

		@GetMapping("/test/api/unexpected-error")
		void unexpectedError() {
			throw new IllegalStateException("DB 접속 정보 같은, 사용자에게 보이면 안 되는 내부 내용");
		}

		// Tomcat이 깨진 주소 값을 읽지 못했을 때 던지는 예외와 같은 것 (상태를 주지 않으면 400)
		@GetMapping("/test/api/invalid-parameter")
		void invalidParameter() {
			throw new InvalidParameterException("Character decoding failed. Parameter [page] with value [%zz] has been ignored.");
		}

		// form이 너무 클 때 Tomcat이 던지는 예외와 같은 것 (413을 담는다)
		@GetMapping("/test/api/form-too-large")
		void formTooLarge() {
			throw new InvalidParameterException("Parameters were not parsed because the size of the posted data was too big.", 413);
		}
	}
}
