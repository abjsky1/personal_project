// 에러 응답과 관련된 공통 코드를 두는 패키지
package backend.common.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

// 에러 종류 목록
// 에러마다 "코드 이름 + HTTP 상태 + 사용자에게 보여 줄 기본 메시지"를 한곳에 모아 둔다.
// → 같은 에러는 어디서 나든 같은 상태·메시지로 응답하고, 프론트는 코드 이름(NOT_FOUND 등)으로 에러를 구분한다.
//
// enum: 정해진 값 몇 개만 가질 수 있는 타입. 아래 이름 하나하나가 ErrorCode 객체다.
// 기능을 만들면서 필요한 에러를 여기에 추가한다. (예: 댓글이 생기면 COMMENT_NOT_FOUND)
public enum ErrorCode {

	// ---- 공통 ----
	// 400: 요청 형식이 잘못됨 (값 누락, 주소의 숫자 자리에 글자가 오는 경우 등)
	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 내용이 올바르지 않습니다."),
	// 404: 없는 주소로 요청함
	NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 주소를 찾을 수 없습니다."),
	// 405: 주소는 있지만 그 방식(GET, POST 등)은 지원하지 않음
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
	// 413: 업로드한 파일이 너무 큼 (application.properties의 max-file-size)
	FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "사진은 10MB까지 올릴 수 있습니다."),
	// 500: 서버 코드에서 예상하지 못한 예외가 남 (원인은 사용자에게 보내지 않고 로그로만 남긴다)
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버에 문제가 생겼습니다. 잠시 후 다시 시도해 주세요."),

	// ---- 게시글 ----
	// 404: 없는 글이거나 삭제된 글
	POST_NOT_FOUND(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다."),
	// 403: 삭제할 때 입력한 비밀번호가 다름
	INVALID_PASSWORD(HttpStatus.FORBIDDEN, "비밀번호가 맞지 않습니다.");

	private final HttpStatus status;
	private final String message;

	// enum의 생성자: 위에서 INVALID_REQUEST(상태, 메시지)처럼 괄호 안에 쓴 값이 여기로 들어온다.
	ErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	// Spring 기본 예외의 HTTP 상태에 맞는 ErrorCode를 고른다. (ApiExceptionHandler와 PageExceptionHandler가 함께 쓴다)
	// switch 식(Java 14+): case 값 -> 결과. 어느 case에도 맞지 않으면 default의 결과를 쓴다.
	public static ErrorCode fromStatus(HttpStatusCode status) {
		return switch (status.value()) {
			case 404 -> NOT_FOUND;
			case 405 -> METHOD_NOT_ALLOWED;
			case 413 -> FILE_TOO_LARGE;
			// 나머지 400번대(400, 415 등)는 "요청이 잘못됨", 500번대는 "서버 오류"로 묶는다.
			default -> status.is4xxClientError() ? INVALID_REQUEST : INTERNAL_ERROR;
		};
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getMessage() {
		return message;
	}
}
