package backend.common.error;

import java.util.Map;

import org.apache.tomcat.util.http.InvalidParameterException;
import org.apache.tomcat.util.http.fileupload.FileUploadException;
import org.apache.tomcat.util.http.fileupload.InvalidFileNameException;
import org.apache.tomcat.util.http.fileupload.MultipartStream;
import org.apache.tomcat.util.http.fileupload.impl.InvalidContentTypeException;
import org.slf4j.MDC;
import org.springframework.beans.InvalidPropertyException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.DisconnectedClientHelper;

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

	// 2. 주소나 form의 값을 서버(Tomcat)가 읽지 못함 (JM-107)
	// 예: 글자가 깨진 값(?page=%zz, %ED%95), 값이 1,000개를 넘음, 2MB가 넘는 form
	// Tomcat 11은 값을 처음 읽는 곳(@RequestParam, form 묶기)에서 InvalidParameterException을 던지고, 알맞은 상태(보통 400, 너무 크면 413)를 예외에 담아 둔다.
	// 요청이 잘못된 것이므로 400번대로 답한다. 3번으로 보내면 500과 ERROR 로그가 되고, 운영에서는 ERROR 한 줄이 운영자에게 에러 알림 메일로 간다.
	// 로그는 WARN 한 줄만 남긴다. 예외 문구에는 보낸 값이 그대로 들어 있어서 남기지 않는다.
	@ExceptionHandler(InvalidParameterException.class)
	public ModelAndView handleInvalidParameter(InvalidParameterException e) {
		// getErrorCode(): Tomcat이 예외에 담아 둔 HTTP 상태 숫자 (400, 413)
		HttpStatusCode status = HttpStatusCode.valueOf(e.getErrorCode());
		// Tomcat은 400번대만 담는다. 그 밖의 상태가 오면 서버 쪽 문제로 보고 3번처럼 처리한다
		if (!status.is4xxClientError()) {
			return handleException(e);
		}
		log.warn("Invalid request parameter: status={}", status.value());
		return errorPage(status, ErrorCode.INVALID_REQUEST);
	}

	// 3. 그 밖의 모든 예외
	// 주의: 여기서 모든 예외를 500으로 만들면, Spring 기본 예외(404, 405 등)까지 500으로 바뀌어 버린다. (S0에서 확인한 함정)
	// 그래서 예외가 원래 가진 상태를 먼저 확인한다.
	@ExceptionHandler(Exception.class)
	public ModelAndView handleException(Exception e) {
		// 응답을 보내는 중에 사용자가 연결을 끊음 (JM-107): 사진이나 CSS를 받다가 다른 화면으로 가는 등. 서버 문제가 아니고 보낼 곳도 없다.
		// Spring은 이 경우를 걸러 주지 않고 이 메서드로 넘겨서, 전에는 500과 ERROR(에러 알림 메일)가 됐다.
		// DisconnectedClientHelper: 예외 종류(ClientAbortException 등)와 문구("broken pipe" 등)로 끊긴 연결인지 보는 Spring의 도구
		// null을 돌려주면 "처리는 끝났고 더 쓸 응답이 없다"는 뜻이 된다
		if (DisconnectedClientHelper.isClientDisconnectedException(e)) {
			return null;
		}
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
		// form 칸 이름에 [0]이나 .이 붙어(title[0]=a) Spring이 값을 넣을 곳을 찾지 못한 경우도 400 (InvalidPropertyException, JM-107)
		if (e instanceof TypeMismatchException || e instanceof InvalidPropertyException) {
			return HttpStatus.BAD_REQUEST;
		}
		// 업로드(multipart) 본문의 모양이 잘못된 경우 (JM-107): 경계 표시가 없거나, 본문이 중간에 끊겼거나, 파일 이름이 잘못됨 → 400
		// 업로드 본문은 Controller를 찾기 전에 읽어서, 어느 주소(API 주소도)로 왔든 이 화면 처리로 온다.
		// 임시 폴더가 없거나 디스크에 쓰지 못한 것처럼 서버 쪽 문제는 그대로 아래의 500이다
		if (e instanceof MultipartException && isMalformedUpload(e)) {
			return HttpStatus.BAD_REQUEST;
		}
		// 그 밖에는 서버 코드에서 예상하지 못한 예외
		return HttpStatus.INTERNAL_SERVER_ERROR;
	}

	// 업로드 본문을 읽는 Tomcat이 "요청의 모양이 잘못됐다"고 본 예외가 원인 사슬(getCause)에 있는지 (JM-107)
	// - FileUploadException 그 자체: 경계 표시(boundary)가 없음 등, 요청을 읽는 단계의 실패
	// - MalformedStreamException: 본문이 마지막 경계 없이 끝남
	// - InvalidContentTypeException: 업로드 형식이 아님 / InvalidFileNameException: 파일 이름에 쓸 수 없는 글자
	// - IllegalArgumentException: Tomcat은 받았지만 Spring이 칸의 머리말(Content-Disposition)을 읽지 못함 (filename*=x''a 등)
	// 디스크에 쓰다 난 오류는 FileUploadException을 이은 다른 클래스(IOFileUploadException)가 일반 IOException을 감싸서 오므로 여기에 들지 않는다
	private static boolean isMalformedUpload(Throwable e) {
		for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
			if (cause.getClass() == FileUploadException.class
					|| cause instanceof MultipartStream.MalformedStreamException
					|| cause instanceof InvalidContentTypeException
					|| cause instanceof InvalidFileNameException
					|| cause instanceof IllegalArgumentException) {
				return true;
			}
		}
		return false;
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
