package backend.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class AsyncConfigTest {

	@AfterEach
	void clearMdc() {
		MDC.clear();
	}

	@Test
	void 뒤에서_도는_작업에도_요청의_traceId가_이어지고_끝나면_지워진다() throws Exception {
		MDC.put("traceId", "abc12345");
		AtomicReference<String> seenDuringTask = new AtomicReference<>();
		AtomicReference<String> seenAfterTask = new AtomicReference<>();

		Runnable decorated = new AsyncConfig().traceIdTaskDecorator()
				.decorate(() -> seenDuringTask.set(MDC.get("traceId")));

		// 일꾼 스레드처럼 다른 스레드에서 실행한다.
		Thread worker = new Thread(() -> {
			decorated.run();
			seenAfterTask.set(MDC.get("traceId"));
		});
		worker.start();
		worker.join();

		assertThat(seenDuringTask.get()).isEqualTo("abc12345");
		assertThat(seenAfterTask.get()).isNull();
	}
}
