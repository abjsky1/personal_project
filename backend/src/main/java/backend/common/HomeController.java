// 패키지 = 폴더 경로. common은 특정 기능(게시글, 댓글 등)에 속하지 않는 공통 코드를 두는 곳
package backend.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

// 첫 화면: 브라우저 → Spring → MySQL → JSP 화면이 이어져 있는지 확인한다.
// 확인용이라 Service·Repository 없이 Controller에서 바로 DB를 조회한다.
// (게시글·댓글 같은 실제 기능은 Controller → Service → Repository로 나눠서 만든다)
//
// @Controller: 메서드가 돌려준 문자열을 "화면(JSP) 이름"으로 보고, 그 JSP를 HTML로 만들어 응답한다.
// (@RestController는 돌려준 값을 JSON으로 응답한다는 점이 다르다)
@Controller
public class HomeController {

	// JdbcTemplate: SQL을 직접 실행하는 Spring 도구
	// DB 접속 정보는 application.properties의 spring.datasource 설정을 그대로 쓴다.
	private final JdbcTemplate jdbcTemplate;

	// 생성자 주입: Spring이 이 Controller 객체를 만들 때, 미리 만들어 둔 JdbcTemplate을 여기로 넣어 준다.
	public HomeController(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	// GET / (사이트 첫 주소) 요청이 오면 실행된다. 브라우저에서 http://localhost:8080 으로 확인한다.
	// Model: JSP로 넘겨줄 값을 담는 상자. 여기에 넣은 값은 JSP에서 ${이름}으로 꺼내 쓴다.
	@GetMapping("/")
	public String home(Model model) {
		// SELECT VERSION(): 지금 연결된 MySQL의 버전을 알려 주는 SQL
		// queryForObject: 결과가 한 줄·한 칸일 때 쓰는 메서드. 두 번째 값은 결과를 받을 자바 타입
		String dbVersion = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
		model.addAttribute("dbVersion", dbVersion);

		// "home" → prefix·suffix가 붙어서 /WEB-INF/views/home.jsp를 화면으로 보여 준다.
		return "home";
	}
}
