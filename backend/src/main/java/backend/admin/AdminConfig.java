package backend.admin;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import backend.admin.auth.AdminSessionInterceptor;
import lombok.RequiredArgsConstructor;

// 토큰 검사를 운영자 API 주소(/api/admin/ 아래 전부)에 적용한다 (RateLimitConfig와 같은 방식)
// 관리자 화면(/admin 아래, JM-82)에는 로그인 검사를 적용한다. 로그인 화면과 기기 등록 화면은 들어오기 전이라 뺀다
@Configuration
@RequiredArgsConstructor
public class AdminConfig implements WebMvcConfigurer {

	private final AdminTokenInterceptor adminTokenInterceptor;
	private final AdminSessionInterceptor adminSessionInterceptor;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(adminTokenInterceptor).addPathPatterns("/api/admin/**");
		registry.addInterceptor(adminSessionInterceptor).addPathPatterns("/admin", "/admin/**")
				.excludePathPatterns("/admin/login", "/admin/setup");
	}
}
