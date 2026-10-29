package backend.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

// 비밀번호 해시 도구를 Spring에 등록한다.
// 게시글·댓글·피드백이 모두 비밀번호를 쓰므로, 한 번 등록해 두고 필요한 곳에서 주입받아 쓴다.
@Configuration
public class PasswordConfig {

	// @Bean: 이 메서드가 돌려준 객체를 Spring이 관리한다. 다른 클래스의 생성자에 PasswordEncoder를 적으면 이 객체가 들어간다.
	// BCrypt: 일부러 느리게 계산하는 해시 방식 → 해시가 유출돼도 비밀번호를 하나씩 대입해 알아내기 어렵다.
	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}
