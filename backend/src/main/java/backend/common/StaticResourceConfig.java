package backend.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.resource.ResourceUrlEncodingFilter;

// CSS 주소에 파일 내용의 지문을 붙이는 필터를 등록한다. (배포 서버 설정: application-prod.properties)
// JSP의 <c:url value="/css/app.css" />가 주소를 만들 때 이 필터가 끼어들어 /css/app-3f2a….css로 바꿔 준다.
// Spring Boot는 이 필터를 Thymeleaf 같은 다른 화면 기술에만 자동으로 등록하고, JSP는 직접 등록해야 한다.
// (개발 PC에서는 지문 기능이 꺼져 있어서 주소가 그대로 나온다)
@Configuration
public class StaticResourceConfig {

	// Filter 객체를 @Bean으로 돌려주면 Spring Boot가 모든 요청에 이 필터를 건다.
	@Bean
	public ResourceUrlEncodingFilter resourceUrlEncodingFilter() {
		return new ResourceUrlEncodingFilter();
	}
}
