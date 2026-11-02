package backend.common;

import java.util.Map;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;

// @EnableAsync: @Async가 붙은 메서드를 뒤에서(다른 스레드에서) 실행하게 켠다.
@Configuration
@EnableAsync
public class AsyncConfig {

	// 뒤에서 도는 작업의 로그에도 요청과 같은 [traceId]가 찍히게 한다.
	// MDC는 스레드마다 따로 있어서, 그냥 두면 다른 스레드로 넘어가는 순간 traceId가 사라진다.
	// → 작업을 맡기는 쪽의 MDC를 복사해 뒀다가, 일꾼 스레드가 실행하기 직전에 넣어 준다.
	// TaskDecorator를 Bean으로 등록하면 Spring Boot가 일꾼 스레드 묶음에 알아서 적용한다.
	@Bean
	public TaskDecorator traceIdTaskDecorator() {
		return task -> {
			Map<String, String> context = MDC.getCopyOfContextMap();
			return () -> {
				if (context != null) {
					MDC.setContextMap(context);
				}
				try {
					task.run();
				} finally {
					// 일꾼 스레드는 재사용되므로, 다음 작업에 이번 traceId가 남지 않게 지운다.
					MDC.clear();
				}
			};
		};
	}
}
