package backend.common.error;

import org.slf4j.MDC;

import backend.common.logging.TraceIdFilter;

// 에러가 나면 항상 이 모양의 JSON으로 응답한다.
// 예: {"code":"NOT_FOUND","message":"요청한 주소를 찾을 수 없습니다.","traceId":"64a1dee2"}
//
// record: 값을 담기만 하는 클래스를 짧게 만드는 문법 (Java 16+)
// 괄호 안에 쓴 값마다 필드, 생성자, 값을 꺼내는 메서드(code() 등)를 Java가 자동으로 만들어 준다.
// 응답으로 보내면 Spring이 이 필드 이름 그대로 JSON으로 바꾼다.
//
// code: 프론트가 에러를 구분할 때 쓰는 이름 (ErrorCode의 이름)
// message: 사용자에게 그대로 보여 줘도 되는 문장
// traceId: 이 요청의 번호. 사용자가 이 번호를 알려 주면 서버 로그에서 같은 번호로 원인을 찾을 수 있다.
public record ErrorResponse(String code, String message, String traceId) {

	// ErrorCode 하나로 응답 객체를 만든다.
	// static 메서드라 new 없이 ErrorResponse.of(ErrorCode.NOT_FOUND)처럼 부른다.
	public static ErrorResponse of(ErrorCode errorCode) {
		// name(): enum 값의 이름을 문자열로 준다 (ErrorCode.NOT_FOUND → "NOT_FOUND")
		// traceId는 TraceIdFilter가 요청 시작 때 MDC에 적어 둔 값을 꺼낸다.
		return new ErrorResponse(errorCode.name(), errorCode.getMessage(), MDC.get(TraceIdFilter.TRACE_ID_KEY));
	}
}
