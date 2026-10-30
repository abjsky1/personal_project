package backend.common.ratelimit;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.RequiredArgsConstructor;

// 업로드 횟수 제한을 어느 주소에 적용할지 Spring MVC에 등록한다.
@Configuration
@RequiredArgsConstructor
public class RateLimitConfig implements WebMvcConfigurer {

	private final UploadRateLimitInterceptor uploadRateLimitInterceptor;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		// /posts 주소에만 적용한다. (GET/POST 구분은 인터셉터 안에서 한다)
		registry.addInterceptor(uploadRateLimitInterceptor).addPathPatterns("/posts");
	}
}
