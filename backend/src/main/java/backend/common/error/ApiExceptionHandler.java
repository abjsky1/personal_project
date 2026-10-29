package backend.common.error;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

// JSON API(@RestController)에서 난 예외를 한곳에서 받아, ErrorResponse 모양의 JSON으로 바꿔 응답한다.
// → Controller·Service마다 try-catch로 에러 응답을 만들 필요가 없다.
// (JSP 화면을 만드는 @Controller의 예외는 PageExceptionHandler가 에러 화면으로 보여 준다)
//
// @RestControllerAdvice(annotations = RestController.class): @RestController가 붙은 Controller에만 적용한다.
// 안에 있는 @ExceptionHandler 메서드가 "이 종류의 예외가 나면 이렇게 응답한다"를 정한다.
// 예외 하나에 맞는 메서드가 여러 개면, 예외 종류가 가장 가깝게 맞는 메서드가 선택된다.
// @Order(가장 먼저): @RestController도 넓게 보면 @Controller라서 PageExceptionHandler에도 해당된다.
// 그래서 이 클래스를 먼저 확인하게 해서, API 예외는 화면이 아니라 JSON으로 나가게 한다.
//
// 이 클래스가 받는 예외는 세 갈래다.
// 1. BusinessException: 우리 코드가 직접 던진 "예상한 실패" → ErrorCode에 적힌 상태로 응답
// 2. Spring이 던지는 기본 예외(없는 주소, 지원하지 않는 방식, 잘못된 요청 형식 등) → Spring이 정한 상태(404, 405, 400 등) 그대로 응답
// 3. 그 밖의 모든 예외: 예상하지 못한 오류 → 500으로 응답하고, 원인을 로그에 남긴다
//
// ResponseEntityExceptionHandler를 상속하는 이유 (2번):
// Exception 전체를 받는 3번만 있으면, Spring의 기본 예외까지 3번이 먼저 가로채서 404·405가 전부 500으로 바뀐다.
// 이 부모 클래스에는 Spring 기본 예외마다 알맞은 상태를 정해 둔 처리 메서드가 이미 들어 있다.
// 그 메서드들이 마지막에 공통으로 부르는 handleExceptionInternal()만 바꿔서, 응답 모양만 우리 형식으로 맞춘다.
@RestControllerAdvice(annotations = RestController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	// 1. 우리 코드가 던진 BusinessException
	// 예상한 실패(게시글 없음, 비밀번호 틀림 등)라서 어디서 났는지(스택 트레이스)는 남기지 않고 한 줄만 남긴다.
	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
		ErrorCode errorCode = e.getErrorCode();
		log.warn("Business error: {}", errorCode);
		// ResponseEntity: 응답 본문(body)과 상태 코드, 헤더를 함께 정해서 돌려줄 때 쓰는 객체
		return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
	}

	// 2. Spring 기본 예외의 응답 모양을 우리 형식으로 바꾼다.
	// @Override: 부모 클래스(ResponseEntityExceptionHandler)에 있는 메서드를 이 클래스에서 다시 정의한다는 표시
	// statusCode: Spring이 이 예외에 맞게 정해 둔 상태 (없는 주소 404, 지원하지 않는 방식 405 등)
	// headers: Spring이 함께 보내려던 헤더 (예: 405일 때 지원하는 방식을 알려 주는 Allow 헤더) → 그대로 보낸다
	// @Nullable: 이 값은 null일 수 있다는 표시 (부모 메서드와 똑같이 맞춰 적는다)
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		// 상태 숫자(404, 405 등)에 맞는 ErrorCode를 고른다.
		ErrorCode errorCode = ErrorCode.fromStatus(statusCode);

		// 500번대는 서버 쪽 문제이므로 3번과 같이 원인을 로그에 남긴다.
		if (statusCode.is5xxServerError()) {
			log.error("Unhandled exception", ex);
		}

		// 상태 코드는 Spring이 정한 값을 그대로 쓴다 (예: 415면 415). 본문만 우리 형식으로 바꾼다.
		return ResponseEntity.status(statusCode).headers(headers).body(ErrorResponse.of(errorCode));
	}

	// 3. 위에서 처리하지 못한 모든 예외 = 예상하지 못한 오류
	// Exception은 모든 예외의 부모라서, 더 가깝게 맞는 처리 메서드가 없는 예외는 전부 여기로 온다.
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception e) {
		// 예외 객체(e)를 마지막 인자로 넘기면, 어디서 터졌는지(스택 트레이스)까지 로그에 남는다.
		// 이 로그가 없으면 사용자는 500만 보고, 개발자는 원인을 찾을 방법이 없다.
		log.error("Unhandled exception", e);
		// 사용자에게는 예외 내용을 보내지 않는다. (서버 내부 정보가 드러날 수 있으므로)
		// 대신 응답의 traceId로 위 로그를 찾아 원인을 본다.
		ErrorCode errorCode = ErrorCode.INTERNAL_ERROR;
		return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
	}
}
