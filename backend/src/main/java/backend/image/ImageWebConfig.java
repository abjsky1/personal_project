package backend.image;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// /images/파일이름 주소로 요청이 오면, uploads 폴더의 그 파일을 그대로 보내 준다.
// (Controller를 만들지 않고, Spring MVC의 "정적 파일 보내기" 기능을 쓴다)
//
// @Configuration: Spring 설정을 담은 클래스라는 표시
// WebMvcConfigurer: Spring MVC 설정 중 바꾸고 싶은 부분만 골라 바꿀 수 있게 해 주는 틀
@Configuration
public class ImageWebConfig implements WebMvcConfigurer {

	private final LocalImageStorage imageStorage;

	public ImageWebConfig(LocalImageStorage imageStorage) {
		this.imageStorage = imageStorage;
	}

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		// /images/** : /images/로 시작하는 모든 주소
		// toUri(): 폴더 경로를 file:///C:/personal_project/uploads/ 같은 주소 모양으로 바꾼다.
		registry.addResourceHandler("/images/**")
				.addResourceLocations(imageStorage.getRootDir().toUri().toString());
	}
}
