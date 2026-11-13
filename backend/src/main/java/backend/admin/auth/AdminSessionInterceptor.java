package backend.admin.auth;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;

// 관리자 화면(/admin/**)의 문지기 (JM-82). 로그인과 인증 앱 등록 화면은 빼고 등록한다 (AdminConfig)
// - 관리자 화면이 꺼져 있으면(비밀번호 미설정) 없는 주소(404)
// - 로그인하지 않았거나 로그인 시간이 지났으면 로그인 화면으로 보낸다
// - 무언가를 바꾸는 요청(POST)은 폼에 숨겨 둔 CSRF 값이 세션의 값과 같아야 통과한다
@Component
@RequiredArgsConstructor
public class AdminSessionInterceptor implements HandlerInterceptor {

	private final AdminAuthService adminAuthService;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
		adminAuthService.requireEnabled();
		HttpSession session = request.getSession(false);
		if (!adminAuthService.isLoggedIn(session)) {
			response.sendRedirect("/admin/login");
			return false;
		}
		if ("POST".equals(request.getMethod()) && !adminAuthService.csrfMatches(session, request.getParameter("_csrf"))) {
			throw new BusinessException(ErrorCode.ADMIN_UNAUTHORIZED);
		}
		return true;
	}
}
