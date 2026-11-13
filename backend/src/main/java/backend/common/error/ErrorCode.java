// 에러 응답과 관련된 공통 코드를 두는 패키지
package backend.common.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

// 에러 종류 목록
// 에러마다 "코드 이름 + HTTP 상태 + 사용자에게 보여 줄 기본 메시지"를 한곳에 모아 둔다.
// → 같은 에러는 어디서 나든 같은 상태, 메시지로 응답하고, 프론트는 코드 이름(NOT_FOUND 등)으로 에러를 구분한다.
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
	// 403: 삭제하거나 메모를 고치거나 분석을 확정할 때 입력한 비밀번호가 다름
	INVALID_PASSWORD(HttpStatus.FORBIDDEN, "비밀번호가 맞지 않습니다."),
	// 429: 같은 곳(IP)에서 너무 자주 올림 (application.properties의 app.rate-limit.*)
	// (제한 숫자는 설정으로 바뀔 수 있어서 메시지에 숫자를 넣지 않는다)
	TOO_MANY_UPLOADS(HttpStatus.TOO_MANY_REQUESTS, "업로드가 너무 잦습니다. 잠시 후 다시 올려 주세요."),
	// 429: 한 글에 좋아요가 짧은 시간 동안 너무 많이 눌림 (JM-59, app.rate-limit.like-per-post-*). IP가 아니라 글마다 센다
	TOO_MANY_LIKES(HttpStatus.TOO_MANY_REQUESTS, "이 글에 좋아요가 한꺼번에 너무 많이 눌렸습니다. 잠시 후 다시 눌러 주세요."),

	// ---- 분석 확정 (JM-74, 고쳐 쓰는 AI) ----
	// 400: 확정하려고 보낸 값이 맞지 않음 (없는 후보 번호, 범위를 벗어난 양, 비었거나 너무 긴 이름, 음식을 전부 뺌)
	INVALID_CONFIRMATION(HttpStatus.BAD_REQUEST, "확정할 수 없는 값이에요. 양은 1g부터 5,000g까지, 직접 적는 이름은 30자까지 적고, 음식은 하나 이상 남겨 주세요."),
	// 400: 확정하려는 합계가 터무니없이 큼 (칼로리나 영양소의 합계가 10,000을 넘음)
	CONFIRMATION_TOO_LARGE(HttpStatus.BAD_REQUEST, "합계가 너무 커서 확정할 수 없어요. 양을 다시 확인해 주세요."),
	// 400: 고를 후보가 없는 글 ("AI가 바로 확정"으로 올린 글, 분석이 끝나지 않았거나 실패한 글)
	NOT_CONFIRMABLE(HttpStatus.BAD_REQUEST, "이 글은 고쳐서 확정할 수 없습니다."),
	// 409: 이미 확정한 글. 확정은 글마다 한 번뿐이다
	ALREADY_CONFIRMED(HttpStatus.CONFLICT, "이미 확정한 글입니다. 확정은 한 번만 할 수 있습니다."),

	// ---- 직접 적기 (JM-74): 후보에 없는 음식의 이름을 적으면 AI가 한 번 더 계산한다. 글마다 한 번만 쓸 수 있다 ----
	// 409: 이 글은 직접 적기를 이미 씀
	TYPED_ALREADY_USED(HttpStatus.CONFLICT, "직접 적기는 글마다 한 번만 쓸 수 있어요. 후보에서 골라 확정해 주세요."),
	// 400: 직접 적은 글자를 AI가 음식 이름이 아니라고 봄 (AI가 답을 줬으므로 한 번을 쓴 것으로 센다)
	TYPED_NOT_FOOD(HttpStatus.BAD_REQUEST, "직접 적은 이름을 AI가 음식으로 보지 않아서 확정하지 못했어요. 직접 적기는 글마다 한 번이라, 이제 후보에서 골라 확정해 주세요."),
	// 502: AI가 쓸 수 없는 답을 줌 (개수가 다름, 범위를 벗어난 값. 이 경우도 한 번을 쓴 것으로 센다)
	TYPED_AI_FAILED(HttpStatus.BAD_GATEWAY, "AI가 직접 적은 음식을 계산하지 못했어요. 직접 적기는 글마다 한 번이라, 이제 후보에서 골라 확정해 주세요."),
	// 503: AI에게서 답을 받지 못함 (연결 실패, AI 서버 오류, 오늘 분석 횟수가 다 참). 한 번을 쓴 것으로 세지 않는다
	TYPED_AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI가 지금 직접 적은 음식을 계산할 수 없어요. 후보에서 골라 확정하거나, 나중에 다시 적어 주세요."),

	// ---- 댓글 ----
	// 404: 없는 댓글이거나 삭제된 댓글 (또는 주소의 글 번호와 맞지 않는 댓글)
	COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다."),

	// ---- 피드백 ----
	// 404: 없는 피드백이거나 삭제된 피드백
	FEEDBACK_NOT_FOUND(HttpStatus.NOT_FOUND, "피드백을 찾을 수 없습니다."),

	// ---- 공지 (JM-65) ----
	// 404: notices.json에 없는 공지 번호
	NOTICE_NOT_FOUND(HttpStatus.NOT_FOUND, "공지를 찾을 수 없습니다."),

	// ---- 운영자 API ----
	// 401: X-Admin-Token 헤더가 없거나 서버의 토큰과 다름
	ADMIN_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "운영자 인증에 실패했습니다."),
	// 403: 서버에 운영자 토큰이 설정되어 있지 않아 운영자 기능이 꺼져 있음 (개발 PC 등)
	ADMIN_DISABLED(HttpStatus.FORBIDDEN, "운영자 기능이 꺼져 있습니다."),

	// ---- 사진 ----
	// 400: 파일 내용이 JPEG, PNG가 아니거나, 읽을 수 없는 사진 (확장자만 바꾼 파일, 아이폰 HEIC 등)
	INVALID_IMAGE(HttpStatus.BAD_REQUEST, "JPEG 또는 PNG 사진만 올릴 수 있습니다."),
	// 400: 화소 수가 너무 많은 사진 (서버 메모리를 지키기 위한 한도)
	IMAGE_TOO_LARGE(HttpStatus.BAD_REQUEST, "사진 해상도가 너무 큽니다. 5,000만 화소 이하의 사진을 올려 주세요.");

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
