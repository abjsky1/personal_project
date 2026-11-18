package backend.visit.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.RequiredArgsConstructor;

// 방문 세기(JM-86)를 어느 주소에 적용할지 Spring MVC에 등록한다
@Configuration
@RequiredArgsConstructor
public class VisitConfig implements WebMvcConfigurer {

	private final VisitCountInterceptor visitCountInterceptor;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		// 사용자 화면 전체. 운영자 화면과 API, 화면이 뒤에서 부르는 API, 사진과 css 같은 파일, 상태 확인, 에러 화면은 뺀다
		registry.addInterceptor(visitCountInterceptor).addPathPatterns("/**")
				.excludePathPatterns("/admin", "/admin/**", "/api/**", "/images/**", "/css/**", "/js/**", "/favicon.ico",
						"/actuator/**", "/error");
	}
}
