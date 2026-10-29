// 테스트도 검사할 클래스와 같은 패키지에 둔다 (폴더는 src/test 아래)
package backend.common.logging;

// assertThat(실제 값).검사(기대 값): 값이 기대와 다르면 테스트를 실패시킨다 (AssertJ)
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

// TraceIdFilter 하나만 떼어서 검사하는 테스트 (단위 테스트)
// Spring 서버를 띄우지 않고 DB도 쓰지 않아서 빠르다.
// 진짜 요청 대신 Spring이 제공하는 가짜 요청·응답 객체(MockHttpServlet...)를 만들어 필터에 직접 넣어 본다.
class TraceIdFilterTest {

	private final TraceIdFilter filter = new TraceIdFilter();

	private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ping");
	private final MockHttpServletResponse response = new MockHttpServletResponse();

	// @Test: 이 메서드가 테스트라는 표시. ./gradlew build(또는 test)를 실행하면 하나씩 실행된다.
	// 테스트 이름은 "무엇을 확인하는지"가 바로 읽히도록 한글로 쓴다. (실패하면 이 이름이 그대로 보인다)
	@Test
	void 요청_처리_중에는_traceId가_있고_응답_헤더에도_같은_값이_들어간다() throws Exception {
		// 필터 다음 단계에서 본 traceId를 담아 둘 상자 (람다 안에서 값을 바깥으로 꺼내려고 사용)
		AtomicReference<String> traceIdDuringRequest = new AtomicReference<>();
		// 가짜 다음 단계: 원래는 Controller가 실행될 자리. 여기서는 "처리 중에 MDC에 무엇이 있었는지"만 기록한다.
		FilterChain chain = (req, res) -> traceIdDuringRequest.set(MDC.get(TraceIdFilter.TRACE_ID_KEY));

		filter.doFilter(request, response, chain);

		// 처리 중에 8자리 traceId가 있었고, 응답 헤더의 값도 그것과 같아야 한다.
		assertThat(traceIdDuringRequest.get()).hasSize(8);
		assertThat(response.getHeader("X-Trace-Id")).isEqualTo(traceIdDuringRequest.get());
	}

	@Test
	void 요청이_끝나면_MDC에서_traceId가_지워진다() throws Exception {
		// 아무것도 하지 않는 다음 단계
		FilterChain chain = (req, res) -> {
		};

		filter.doFilter(request, response, chain);

		// 남아 있으면, 같은 일꾼(스레드)이 처리하는 다음 요청의 로그에 이 번호가 잘못 찍힌다.
		assertThat(MDC.get(TraceIdFilter.TRACE_ID_KEY)).isNull();
	}

	@Test
	void 처리_중에_예외가_나도_MDC에서_traceId가_지워진다() {
		// Controller에서 예외가 터진 상황을 흉내 낸다.
		FilterChain chain = (req, res) -> {
			throw new IllegalStateException("처리 중 오류");
		};

		// 예외는 필터를 그대로 통과해서 밖으로 나가야 하고 (필터가 예외를 삼키면 안 된다)
		assertThatThrownBy(() -> filter.doFilter(request, response, chain))
				.isInstanceOf(IllegalStateException.class);
		// 그래도 finally 덕분에 MDC는 정리되어 있어야 한다.
		assertThat(MDC.get(TraceIdFilter.TRACE_ID_KEY)).isNull();
	}
}
