// 로그와 관련된 공통 코드를 두는 패키지
package backend.common.logging;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// 모든 요청에 번호(traceId)를 붙이는 필터
// 필터: 요청이 Controller에 도착하기 전에 먼저 지나가는 관문. 응답도 다시 이곳을 거쳐서 나간다.
//
// 이 필터가 하는 일
// 1. 요청이 들어오면 traceId를 만들어 MDC에 적는다 → 이 요청을 처리하는 동안 찍히는 모든 로그에 traceId가 붙는다.
// 2. 응답 헤더(X-Trace-Id)에도 traceId를 넣는다 → 브라우저 개발자 도구(Network 탭)에서도 번호를 볼 수 있다.
// 3. 요청이 끝나면 "GET /api/ping -> 200 (35ms)" 같은 요청 기록을 한 줄 남긴다.
//
// @Component: Spring이 이 객체를 만들어 관리한다. 필터를 @Component로 등록하면 Spring Boot가 모든 요청에 자동으로 적용한다.
// @Order(가장 먼저): 필터가 여러 개일 때 이 필터를 맨 앞에 둔다 → 뒤에 있는 필터가 찍는 로그에도 traceId가 붙는다.
// OncePerRequestFilter: 요청 하나당 정확히 한 번만 실행되도록 Spring이 만들어 둔 필터의 기본 틀
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

	// MDC에 traceId를 적을 때 쓰는 이름. 다른 클래스(에러 응답 등)에서도 이 이름으로 꺼내 쓴다.
	public static final String TRACE_ID_KEY = "traceId";

	// 응답 헤더 이름. X-로 시작하는 이름은 "표준이 아니라 직접 정한 헤더"라는 관례
	private static final String TRACE_ID_HEADER = "X-Trace-Id";

	// 이 클래스에서 로그를 찍을 때 쓰는 도구. 로그에 클래스 이름(TraceIdFilter)이 함께 찍힌다.
	// (Lombok을 쓰면 @Slf4j가 이 줄을 자동으로 만들어 준다)
	private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);

	// 요청이 올 때마다 Spring이 이 메서드를 호출한다.
	// filterChain: 이 필터 다음 순서(다른 필터들 → Controller)로 요청을 넘기는 통로
	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		// UUID: 겹치지 않는 무작위 값 (예: 3f2a9c1e-7b4d-4e...). 앞 8자리만 쓴다.
		// 이 서비스 규모에서는 8자리도 겹칠 일이 거의 없고, 로그에는 시간도 함께 찍혀서 찾는 데 충분하다.
		String traceId = UUID.randomUUID().toString().substring(0, 8);
		// 요청 처리에 걸린 시간을 재기 위해 시작 시각을 적어 둔다 (밀리초 단위)
		long startTime = System.currentTimeMillis();

		// MDC: 지금 이 요청을 처리하는 동안 붙어 다니는 메모지
		// 여기에 적은 값은 이 요청 중에 찍히는 모든 로그에 자동으로 함께 나온다. (어디에 찍힐지는 application.properties에서 정함)
		MDC.put(TRACE_ID_KEY, traceId);
		response.setHeader(TRACE_ID_HEADER, traceId);

		try {
			// 요청을 다음 순서로 넘긴다. Controller가 응답을 다 만들고 나면 이 줄 다음으로 돌아온다.
			filterChain.doFilter(request, response);
		} finally {
			// finally: 위에서 예외가 나도 반드시 실행된다. (요청 기록과 MDC 정리는 어떤 경우에도 해야 하므로)
			long elapsed = System.currentTimeMillis() - startTime;
			// 요청 기록 한 줄. {} 자리에 뒤의 값이 순서대로 들어간다.
			// getRequestURI()는 ? 뒤의 값(쿼리 문자열)을 빼고 경로만 준다 → 주소에 담긴 값이 로그에 남지 않게
			log.info("{} {} -> {} ({}ms)", request.getMethod(), request.getRequestURI(), response.getStatus(), elapsed);
			// 서버는 요청을 처리하는 일꾼(스레드)을 재사용한다.
			// 여기서 지우지 않으면, 이 일꾼이 다음에 처리하는 요청의 로그에 지금 번호가 잘못 찍힌다.
			MDC.remove(TRACE_ID_KEY);
		}
	}
}
