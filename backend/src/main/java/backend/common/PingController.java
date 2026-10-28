// 패키지 = 폴더 경로. common은 특정 기능(게시글, 댓글 등)에 속하지 않는 공통 코드를 두는 곳
package backend.common;

import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// React → Spring → MySQL이 실제로 이어져 있는지 확인하는 API
// 확인용이라 Service·Repository 없이 Controller에서 바로 DB를 조회한다.
// (게시글·댓글 같은 실제 기능은 Controller → Service → Repository로 나눠서 만든다)
//
// @RestController: 메서드가 반환한 값을 JSON으로 바꿔서 응답한다 (@Controller + @ResponseBody)
@RestController
public class PingController {

	// JdbcTemplate: SQL을 직접 실행하는 Spring 도구
	// Connection 열기 → PreparedStatement 실행 → ResultSet 읽기 → close 하는 반복 코드를 대신 처리해 준다.
	// DB 접속 정보는 application.properties의 spring.datasource 설정을 그대로 쓴다.
	private final JdbcTemplate jdbcTemplate;

	// 생성자 주입: Spring이 이 Controller 객체를 만들 때, 미리 만들어 둔 JdbcTemplate을 여기로 넣어 준다.
	// Lombok의 @RequiredArgsConstructor를 쓰면 이 생성자를 자동으로 만들어 주는데,
	// 이 프로젝트는 Lombok을 넣지 않았으므로 직접 작성한다.
	public PingController(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	// GET /api/ping 요청이 오면 이 메서드가 실행된다.
	// 브라우저 주소창에 http://localhost:8080/api/ping 을 입력해서 바로 확인할 수도 있다.
	@GetMapping("/api/ping")
	public Map<String, String> ping() {
		// SELECT VERSION(): 지금 연결된 MySQL의 버전을 알려 주는 SQL
		// 8.4.13이 나오면 Docker MySQL, 8.0.46이 나오면 PC에 설치된 MySQL80에 붙은 것이다.
		// queryForObject: 결과가 한 줄·한 칸일 때 쓰는 메서드. 두 번째 값은 결과를 받을 자바 타입
		String dbVersion = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);

		// Map.of(키, 값, 키, 값): 이 Map이 JSON으로 바뀌어 응답된다.
		// → {"message":"pong","dbVersion":"8.4.13"}  (Map이라 JSON 안의 순서는 바뀔 수 있다)
		// 확인용이라 DTO 클래스를 따로 만들지 않고 Map으로 간단히 응답한다.
		return Map.of("message", "pong", "dbVersion", dbVersion);
	}
}
