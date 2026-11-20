package backend.visit.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.RequiredArgsConstructor;

// 방문 세기(JM-86)와 사용자 흐름(JM-92)을 어느 주소에 적용할지 Spring MVC에 등록한다
@Configuration
@RequiredArgsConstructor
public class VisitConfig implements WebMvcConfigurer {

	// 사용자 화면 전체에서 뺄 주소: 운영자 화면과 API, 화면이 뒤에서 부르는 API, 사진과 css 같은 파일, 상태 확인, 에러 화면.
	// 두 기록이 같은 범위를 써야 방문 수와 흐름의 방문이 맞는다
	static final String[] EXCLUDED = { "/admin", "/admin/**", "/api/**", "/images/**", "/css/**", "/js/**", "/favicon.ico",
			"/actuator/**", "/error" };

	private final VisitCountInterceptor visitCountInterceptor;
	private final PageFlowInterceptor pageFlowInterceptor;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(visitCountInterceptor).addPathPatterns("/**").excludePathPatterns(EXCLUDED);
		registry.addInterceptor(pageFlowInterceptor).addPathPatterns("/**").excludePathPatterns(EXCLUDED);
	}
}
