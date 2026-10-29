package backend.common.error;

import java.util.Map;

import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

import backend.common.logging.TraceIdFilter;
import lombok.extern.slf4j.Slf4j;

// 화면(JSP) 요청에서 난 예외를 받아 에러 화면(common/error.jsp)을 보여 준다.
// 없는 주소(404), 지원하지 않는 방식(405), 너무 큰 사진(413)처럼 Controller 밖에서 나는 예외도 여기로 온다.
// (@RestController의 예외는 먼저 확인되는 ApiExceptionHandler가 JSON으로 처리한다)
//
// @ControllerAdvice: 모든 Controller에 공통으로 적용되는 클래스라는 표시 (@RestControllerAdvice와 달리 화면을 돌려줄 수 있다)
// @Order(가장 나중): ApiExceptionHandler보다 뒤에 확인한다.
@Slf4j
@ControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class PageExceptionHandler {

	// 1. 우리 코드가 던진 BusinessException (게시글 없음 등) → ErrorCode에 적힌 상태와 메시지로 에러 화면
	// 예상한 실패라서 스택 트레이스 없이 한 줄만 남긴다.
	@ExceptionHandler(BusinessException.class)
	public ModelAndView handleBusinessException(BusinessException e) {
		log.warn("Business error: {}", e.getErrorCode());
		return errorPage(e.getErrorCode().getStatus(), e.getErrorCode());
	}

	// 2. 그 밖의 모든 예외
	// 주의: 여기서 모든 예외를 500으로 만들면, Spring 기본 예외(404, 405 등)까지 500으로 바뀌어 버린다. (S0에서 확인한 함정)
	// 그래서 예외가 원래 가진 상태를 먼저 확인한다.
	@ExceptionHandler(Exception.class)
	public ModelAndView handleException(Exception e) {
		HttpStatusCode status = statusOf(e);

		// 500번대 = 서버 쪽 문제 → 원인을 찾을 수 있게 스택 트레이스까지 남긴다. (400번대는 사용자 요청 문제라 남기지 않는다)
		if (status.is5xxServerError()) {
			log.error("Unhandled exception", e);
		}
		return errorPage(status, ErrorCode.fromStatus(status));
	}

	// 예외가 가진 HTTP 상태를 알아낸다.
	private HttpStatusCode statusOf(Exception e) {
		// Spring 기본 예외 대부분(없는 주소, 지원하지 않는 방식, 너무 큰 파일, 값 누락 등)은 ErrorResponse를 구현하고 있어서 자기 상태를 알려 준다.
		// instanceof 패턴(Java 16+): e가 ErrorResponse이면 errorResponse라는 이름으로 바로 꺼내 쓴다.
		if (e instanceof org.springframework.web.ErrorResponse errorResponse) {
			return errorResponse.getStatusCode();
		}
		// 주소의 숫자 자리에 글자가 온 경우 (/posts/abc) → 요청이 잘못된 것이므로 400
		if (e instanceof TypeMismatchException) {
			return HttpStatus.BAD_REQUEST;
		}
		// 그 밖에는 서버 코드에서 예상하지 못한 예외
		return HttpStatus.INTERNAL_SERVER_ERROR;
	}

	// 에러 화면을 만든다.
	// ModelAndView: 보여 줄 화면 이름 + 화면에 넘길 값 + 응답 상태를 한 번에 담는 객체
	// 화면 이름을 "error"로 하지 않는 이유: Spring Boot가 이미 "error"라는 이름의 기본 에러 화면을 갖고 있어서 그쪽이 먼저 선택된다.
	private ModelAndView errorPage(HttpStatusCode status, ErrorCode errorCode) {
		// traceId: 사용자가 이 번호를 알려 주면 서버 로그에서 같은 번호로 원인을 찾는다.
		String traceId = MDC.get(TraceIdFilter.TRACE_ID_KEY);
		Map<String, Object> model = Map.of(
				"status", status.value(),
				"message", errorCode.getMessage(),
				"traceId", traceId == null ? "" : traceId);
		return new ModelAndView("common/error", model, status);
	}
}
