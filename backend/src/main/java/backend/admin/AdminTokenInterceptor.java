// 운영자 기능(관리자 API)을 두는 패키지
package backend.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

// 운영자 API(/api/admin/**)에 들어오는 요청의 열쇠(토큰)를 검사한다.
// 운영자는 계정이 없다. 서버의 .env에 넣어 둔 긴 무작위 문자열(APP_ADMIN_TOKEN)을 요청 헤더 X-Admin-Token에 그대로 보내면 운영자로 본다.
// → 열쇠를 아는 사람만 통과하고, 열쇠가 새면 .env의 값을 바꿔 app을 재시작하면 된다 (기획서 16절)
@Slf4j
@Component
public class AdminTokenInterceptor implements HandlerInterceptor {

	public static final String HEADER = "X-Admin-Token";

	private final String configuredToken;

	// @Value: 설정값을 받는다. app.admin.token은 환경 변수 APP_ADMIN_TOKEN에서 오고, 없으면 빈 문자열(:) = 운영자 기능 꺼짐
	public AdminTokenInterceptor(@Value("${app.admin.token:}") String configuredToken) {
		this.configuredToken = configuredToken;
	}

	// preHandle: Controller가 실행되기 전에 먼저 불린다. 예외를 던지면 Controller는 실행되지 않고 ApiExceptionHandler가 JSON 에러로 응답한다.
	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		// 토큰이 설정되어 있지 않으면(개발 PC 등) 운영자 기능 자체를 닫아 둔다. 빈 헤더로 통과되는 일이 없게.
		if (configuredToken == null || configuredToken.isBlank()) {
			throw new BusinessException(ErrorCode.ADMIN_DISABLED);
		}

		String given = request.getHeader(HEADER);
		// MessageDigest.isEqual: 두 값을 끝까지 비교한다. equals()는 앞에서 다른 글자가 나오면 바로 끝나서,
		// 걸린 시간을 재면 몇 글자까지 맞았는지 짐작할 여지가 생긴다(타이밍 공격). 비밀값 비교는 이 메서드로 한다.
		if (given == null || !MessageDigest.isEqual(
				given.getBytes(StandardCharsets.UTF_8), configuredToken.getBytes(StandardCharsets.UTF_8))) {
			// 어떤 값을 보냈는지는 로그에 남기지 않는다. 어느 주소로 누가 시도했는지만 (IP는 TraceIdFilter 규칙대로 남기지 않는다)
			log.warn("Admin token rejected: {} {}", request.getMethod(), request.getRequestURI());
			throw new BusinessException(ErrorCode.ADMIN_UNAUTHORIZED);
		}
		return true;
	}
}
