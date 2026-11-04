package backend.admin;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.RequiredArgsConstructor;

// 토큰 검사를 운영자 API 주소(/api/admin/ 아래 전부)에 적용한다 (RateLimitConfig와 같은 방식)
@Configuration
@RequiredArgsConstructor
public class AdminConfig implements WebMvcConfigurer {

	private final AdminTokenInterceptor adminTokenInterceptor;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(adminTokenInterceptor).addPathPatterns("/api/admin/**");
	}
}
