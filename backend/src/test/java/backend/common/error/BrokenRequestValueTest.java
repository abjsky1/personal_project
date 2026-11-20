package backend.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

// 실제 Tomcat을 띄워 잘못된 요청 값을 보내고, 500이 아니라 400번대로 답하는지와 ERROR 로그가 없는지 검사한다 (JM-107)
// 운영에서는 ERROR 로그 한 줄이 운영자에게 에러 알림 메일로 간다. 누가 주소 하나를 잘못 보냈다고 메일이 가면 안 된다.
//
// MockMvc가 아니라 실제 서버를 쓰는 이유: MockMvc는 Tomcat의 값 읽기(%zz 같은 퍼센트 인코딩 풀기)와 JSP 엔진을 거치지 않아서 이 경우들이 재현되지 않는다.
// webEnvironment = RANDOM_PORT: 실제 Tomcat을 빈 포트로 띄운다. 포트 번호는 local.server.port로 받는다.
// 요청은 소켓에 요청 줄을 직접 써서 보낸다. Java의 URI와 HttpClient는 %zz처럼 깨진 주소를 보내기 전에 거절하기 때문이다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "app.admin.token=" + BrokenRequestValueTest.TOKEN)
class BrokenRequestValueTest {

	static final String TOKEN = "test-admin-token";

	@Value("${local.server.port}")
	private int port;

	// 테스트하는 동안 앱의 로그를 모아 두는 곳 (logback의 ListAppender: 로그 한 줄을 목록에 하나씩 쌓는다)
	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
	private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

	@BeforeEach
	void collectLogs() {
		logs.start();
		root.addAppender(logs);
	}

	@AfterEach
	void stopCollecting() {
		root.detachAppender(logs);
		logs.stop();
	}

	@Test
	void 글자가_깨진_쪽_번호는_400_에러_화면이고_ERROR가_없다() throws IOException {
		Response response = get("/?page=%zz", "text/html");

		assertThat(response.status()).isEqualTo(400);
		assertThat(response.body()).contains(ErrorCode.INVALID_REQUEST.getMessage());
		assertThat(errors()).isEmpty();
		// WARN 한 줄은 남기되, 보낸 값(%zz)은 적지 않는다
		assertThat(warnings()).contains("Invalid request parameter: status=400")
				.noneMatch(message -> message.contains("%zz"));
	}

	@Test
	void UTF_8로_풀리지_않는_값과_이름이_깨진_값도_400이다() throws IOException {
		// %ED%95: 한글 한 글자(3바이트)의 앞 2바이트만 있다
		assertThat(get("/feedback?page=%ED%95", "text/html").status()).isEqualTo(400);
		// 읽는 값(page)이 아닌 다른 값이 깨져도 Tomcat은 값을 읽을 때 한꺼번에 실패한다
		assertThat(get("/?utm_source=%zz", "text/html").status()).isEqualTo(400);
		assertThat(get("/?%zz=1", "text/html").status()).isEqualTo(400);
		assertThat(errors()).isEmpty();
	}

	@Test
	void form으로_보낸_깨진_값도_400이다() throws IOException {
		Response response = postForm("/feedback", "nickname=a&password=1234&title=t&content=%zz".getBytes(StandardCharsets.US_ASCII));

		assertThat(response.status()).isEqualTo(400);
		assertThat(errors()).isEmpty();
	}

	@Test
	void 값이_1000개를_넘으면_400이다() throws IOException {
		// Tomcat은 요청 하나의 값을 1,000개까지만 받는다 (maxParameterCount)
		String query = String.join("&", Collections.nCopies(1001, "a=1"));

		assertThat(get("/?" + query, "text/html").status()).isEqualTo(400);
		assertThat(errors()).isEmpty();
	}

	@Test
	void form이_2MB를_넘으면_413_에러_화면이고_ERROR가_없다() throws IOException {
		// Tomcat이 form을 읽는 한도는 2MB (Spring Boot 기본값 server.tomcat.max-http-form-post-size)
		byte[] body = ("content=" + "a".repeat(2 * 1024 * 1024 + 100)).getBytes(StandardCharsets.US_ASCII);

		Response response = postForm("/feedback", body);

		assertThat(response.status()).isEqualTo(413);
		assertThat(response.body()).contains(ErrorCode.INVALID_REQUEST.getMessage());
		assertThat(errors()).isEmpty();
	}

	@Test
	void 아주_큰_쪽_번호는_마지막_쪽을_보여준다() throws IOException {
		// 전에는 건너뛸 글 수가 int를 넘어 500이었다
		assertThat(get("/?page=2147483647", "text/html").status()).isEqualTo(200);
		assertThat(get("/feedback?page=2147483647", "text/html").status()).isEqualTo(200);
		assertThat(errors()).isEmpty();
	}

	@Test
	void 주소에_jsp_precompile이_있어도_화면을_그대로_그린다() throws IOException {
		// 전에는 값이 있으면 JSP 엔진의 예외(500, ERROR 2줄), 값이 없으면 빈 화면이었다 (application.properties의 jspPrecompilationQueryParameter)
		Response withValue = get("/terms?jsp_precompile=abc", "text/html");
		Response flagOnly = get("/terms?jsp_precompile", "text/html");

		assertThat(withValue.status()).isEqualTo(200);
		assertThat(withValue.body()).contains("이용약관");
		assertThat(flagOnly.status()).isEqualTo(200);
		assertThat(flagOnly.body()).contains("이용약관");
		assertThat(errors()).isEmpty();
	}

	@Test
	void JSON을_받지_않는다고_한_요청에도_API_오류는_JSON이다() throws IOException {
		// Accept: text/html만 보낸다 (*/* 없음). 전에는 오류 JSON을 쓰지 못해 500과 ERROR였다
		Response noToken = get("/api/admin/stats", "text/html");
		Response noPost = get("/api/posts/999999999/analysis", "text/html");

		assertThat(noToken.status()).isEqualTo(401);
		assertThat(noToken.headers()).containsIgnoringCase("Content-Type: application/json");
		assertThat(noToken.body()).contains("ADMIN_UNAUTHORIZED");
		// 분석 기록이 없는 글 번호: 404 (코드 NOT_FOUND)
		assertThat(noPost.status()).isEqualTo(404);
		assertThat(noPost.headers()).containsIgnoringCase("Content-Type: application/json");
		assertThat(noPost.body()).contains("\"code\":\"NOT_FOUND\"");
		assertThat(errors()).isEmpty();
	}

	@Test
	void API에_보낸_깨진_값도_400_JSON이다() throws IOException {
		Response response = send("GET /api/admin/stats?days=%zz HTTP/1.1", "application/json", null, null,
				"X-Admin-Token: " + TOKEN);

		assertThat(response.status()).isEqualTo(400);
		assertThat(response.body()).contains("INVALID_REQUEST");
		assertThat(errors()).isEmpty();
	}

	// 요청을 처리한 Tomcat 스레드(이름에 -exec-가 들어간다)가 남긴 로그 중 ERROR, WARN
	private List<String> errors() {
		return messagesAt(Level.ERROR);
	}

	private List<String> warnings() {
		return messagesAt(Level.WARN);
	}

	private List<String> messagesAt(Level level) {
		List<ILoggingEvent> events;
		// ListAppender는 로그를 쌓을 때 자기 자신을 잠근다. 같은 잠금 안에서 복사해야 다른 스레드가 쌓은 줄이 빠짐없이 보인다
		synchronized (logs) {
			events = new ArrayList<>(logs.list);
		}
		return events.stream()
				.filter(event -> event.getThreadName().contains("-exec-"))
				.filter(event -> event.getLevel() == level)
				.map(ILoggingEvent::getFormattedMessage)
				.toList();
	}

	private Response get(String pathAndQuery, String accept) throws IOException {
		return send("GET " + pathAndQuery + " HTTP/1.1", accept, null, null);
	}

	private Response postForm(String path, byte[] body) throws IOException {
		return send("POST " + path + " HTTP/1.1", "text/html", "application/x-www-form-urlencoded", body);
	}

	// 요청 줄과 머리를 소켓에 그대로 쓰고, 서버가 연결을 닫을 때까지(Connection: close) 응답을 읽는다.
	// 서버는 요청 처리를 다 마친 뒤에 연결을 닫으므로, 응답을 다 읽은 때에는 그 요청의 로그도 다 남아 있다
	private Response send(String requestLine, String accept, String contentType, byte[] body, String... headers)
			throws IOException {
		try (Socket socket = new Socket("localhost", port)) {
			socket.setSoTimeout(30_000);
			StringBuilder head = new StringBuilder()
					.append(requestLine).append("\r\n")
					.append("Host: localhost\r\n")
					.append("User-Agent: jjikmuk-check-bot/1.0\r\n")
					.append("Accept: ").append(accept).append("\r\n");
			for (String header : headers) {
				head.append(header).append("\r\n");
			}
			if (body != null) {
				head.append("Content-Type: ").append(contentType).append("\r\n")
						.append("Content-Length: ").append(body.length).append("\r\n");
			}
			head.append("Connection: close\r\n\r\n");

			OutputStream out = socket.getOutputStream();
			out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
			if (body != null) {
				out.write(body);
			}
			out.flush();

			String text = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			// 첫 줄: "HTTP/1.1 400 " → 10번째 글자부터 3자리가 상태
			int status = Integer.parseInt(text.substring(9, 12));
			int bodyStart = text.indexOf("\r\n\r\n");
			return new Response(status, text.substring(0, bodyStart), text.substring(bodyStart + 4));
		}
	}

	private record Response(int status, String headers, String body) {
	}
}
