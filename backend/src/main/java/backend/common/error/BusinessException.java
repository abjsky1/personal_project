package backend.common.error;

// 우리 코드가 "예상한 실패"를 알릴 때 직접 던지는 예외
// 예: 게시글이 없으면 → throw new BusinessException(ErrorCode.POST_NOT_FOUND);
// 던지기만 하면 ErrorCode에 맞는 상태로 응답이 나간다.
// (화면 요청이면 PageExceptionHandler가 에러 화면으로, API 요청이면 ApiExceptionHandler가 JSON으로)
// → Service 코드에서는 응답 모양을 신경 쓰지 않고 "무슨 에러인지"만 정하면 된다.
//
// RuntimeException을 상속한 이유: 메서드마다 throws를 적지 않아도 던질 수 있다 (Spring에서 흔히 쓰는 방식)
public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;

	public BusinessException(ErrorCode errorCode) {
		// 부모(RuntimeException)에 메시지를 넘겨 둔다 → 로그나 디버깅 화면에서 예외 메시지로 보인다.
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}
}
