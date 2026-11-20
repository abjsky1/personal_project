package backend.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.io.IOException;

import org.apache.catalina.connector.ClientAbortException;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.apache.tomcat.util.http.fileupload.FileUploadException;
import org.apache.tomcat.util.http.fileupload.MultipartStream;
import org.apache.tomcat.util.http.fileupload.impl.IOFileUploadException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.multipart.MultipartException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

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

	@Test
	void Tomcat이_값을_읽지_못한_예외는_예외에_담긴_400번대_에러_화면이다() throws Exception {
		// 깨진 주소 값(?page=%zz)이면 Tomcat이 InvalidParameterException을 던진다 (JM-107). 전에는 500 에러 화면과 ERROR 로그였다.
		// MockMvc는 Tomcat을 거치지 않아서 테스트 Controller가 같은 예외를 직접 던진다. 실제 Tomcat으로 보내 보는 것은 BrokenRequestValueTest
		mockMvc.perform(get("/test/page/invalid-parameter"))
				.andExpect(status().isBadRequest())
				.andExpect(view().name("common/error"))
				.andExpect(model().attribute("status", 400))
				.andExpect(model().attribute("message", ErrorCode.INVALID_REQUEST.getMessage()));
		// form이 너무 크면 Tomcat이 413을 담아 둔다. 사진 한도 문구(FILE_TOO_LARGE)가 아니라 요청이 잘못됐다는 문구를 보여 준다
		mockMvc.perform(get("/test/page/form-too-large"))
				.andExpect(status().is(413))
				.andExpect(model().attribute("message", ErrorCode.INVALID_REQUEST.getMessage()));
	}

	@Test
	void 모양이_잘못된_업로드는_400이고_서버_쪽_업로드_실패는_그대로_500이다() throws Exception {
		// 요청이 잘못된 것 (JM-107): 경계 표시가 없음, 본문이 끊김 → 400
		mockMvc.perform(get("/test/page/upload-no-boundary")).andExpect(status().isBadRequest());
		mockMvc.perform(get("/test/page/upload-cut-short")).andExpect(status().isBadRequest());
		// 서버 쪽 문제: 디스크에 쓰지 못함, 임시 폴더가 없음 → 지금처럼 500 (ERROR 로그로 에러 알림이 가야 한다)
		// 이 테스트의 출력에 ERROR 로그와 스택 트레이스가 찍히는 것은 정상이다
		mockMvc.perform(get("/test/page/upload-disk-full")).andExpect(status().isInternalServerError());
		mockMvc.perform(get("/test/page/upload-location-invalid")).andExpect(status().isInternalServerError());
	}

	@Test
	void 응답을_쓰는_중에_사용자가_연결을_끊은_것은_ERROR를_남기지_않는다() throws Exception {
		// 사진이나 CSS를 받다가 다른 화면으로 가면 생긴다 (JM-107). 서버 문제가 아니므로 에러 알림이 가면 안 된다
		Logger logger = (Logger) LoggerFactory.getLogger(PageExceptionHandler.class);
		ListAppender<ILoggingEvent> logs = new ListAppender<>();
		logs.start();
		logger.addAppender(logs);
		try {
			mockMvc.perform(get("/test/page/client-gone"));
		} finally {
			logger.detachAppender(logs);
		}

		assertThat(logs.list).noneMatch(event -> event.getLevel() == Level.ERROR);
	}

	// 테스트 전용 화면 Controller: 일부러 예외를 던진다.
	@Controller
	static class PageTestController {

		// 응답을 쓰다가 연결이 끊겼을 때 Tomcat이 던지는 예외
		@GetMapping("/test/page/client-gone")
		String clientGone() throws IOException {
			throw new ClientAbortException(new IOException("Connection reset by peer"));
		}

		// 아래 넷은 Spring이 업로드 본문을 읽지 못했을 때 던지는 예외와 같은 모양 (MultipartException이 Tomcat의 예외를 감싼다)
		@GetMapping("/test/page/upload-no-boundary")
		String uploadNoBoundary() {
			throw new MultipartException("Failed to parse multipart servlet request",
					new FileUploadException("the request was rejected because no multipart boundary was found"));
		}

		@GetMapping("/test/page/upload-cut-short")
		String uploadCutShort() {
			throw new MultipartException("Failed to parse multipart servlet request",
					new IOFileUploadException("Processing of multipart/form-data request failed. Stream ended unexpectedly",
							new MultipartStream.MalformedStreamException("Stream ended unexpectedly")));
		}

		@GetMapping("/test/page/upload-disk-full")
		String uploadDiskFull() {
			throw new MultipartException("Failed to parse multipart servlet request",
					new IOFileUploadException("Processing of multipart/form-data request failed. No space left on device",
							new IOException("No space left on device")));
		}

		@GetMapping("/test/page/upload-location-invalid")
		String uploadLocationInvalid() {
			throw new MultipartException("Failed to parse multipart servlet request",
					new IOException("The temporary upload location is not valid"));
		}

		@GetMapping("/test/page/unexpected-error")
		String unexpectedError() {
			throw new IllegalStateException("사용자에게 보이면 안 되는 내부 내용");
		}

		// Tomcat이 깨진 주소 값을 읽지 못했을 때 던지는 예외와 같은 것 (상태를 주지 않으면 400)
		@GetMapping("/test/page/invalid-parameter")
		String invalidParameter() {
			throw new InvalidParameterException("Character decoding failed. Parameter [page] with value [%zz] has been ignored.");
		}

		// form이 너무 클 때 Tomcat이 던지는 예외와 같은 것 (413을 담는다)
		@GetMapping("/test/page/form-too-large")
		String formTooLarge() {
			throw new InvalidParameterException("Parameters were not parsed because the size of the posted data was too big.", 413);
		}
	}
}
