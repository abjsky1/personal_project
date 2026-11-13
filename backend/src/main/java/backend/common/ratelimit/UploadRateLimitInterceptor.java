// 요청 횟수 제한(rate limit)과 관련된 공통 코드를 두는 패키지
package backend.common.ratelimit;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

// 글 올리기(POST /posts)를 너무 자주 하지 못하게 막는다. (기획서 7절)
// 주소가 공개되면 누구나 올릴 수 있어서, 도배로 저장 공간과 AI 분석 한도를 다 써 버리는 것을 막는다.
// 몇 번까지 올릴 수 있는지 세는 일은 UploadLimiter가 한다 (JM-79부터 브라우저마다 세고, IP와 서비스 전체에는 하루 상한만 둔다).
//
// HandlerInterceptor: Controller 메서드가 실행되기 "직전"에 끼어드는 Spring MVC 기능
// preHandle()에서 예외를 던지면 Controller는 실행되지 않고, PageExceptionHandler가 에러 화면(429)을 보여 준다.
// (필터와 달리 Spring MVC 안에서 실행되므로 우리가 만든 에러 처리를 그대로 쓸 수 있다)
@Component
@RequiredArgsConstructor
public class UploadRateLimitInterceptor implements HandlerInterceptor {

	private final UploadLimiter uploadLimiter;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		// 글 올리기(POST)만 센다. 글쓰기 화면을 여는 것(GET)은 세지 않는다.
		if (!"POST".equals(request.getMethod())) {
			return true;
		}
		// getSession(): 이 브라우저의 세션. 없으면 새로 만들고, 세션 번호가 쿠키(JSESSIONID)로 브라우저에 남는다
		// → 다음에 올릴 때 같은 브라우저인지 안다. 같은 와이파이를 쓰는 사람들도 브라우저가 다르면 따로 센다.
		// getRemoteAddr(): 요청을 보낸 쪽의 IP. 배포 서버에서는 Nginx를 거쳐 오지만,
		// application-prod.properties의 forward-headers-strategy 설정 덕분에 Nginx의 IP가 아니라 진짜 사용자 IP가 나온다.
		uploadLimiter.check(request.getSession().getId(), request.getRemoteAddr());
		return true;
	}
}
