package backend.visit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import backend.visit.model.entity.Device;
import backend.visit.model.entity.PageView;
import backend.visit.model.entity.Screen;

// 사용자 흐름의 저장(JM-92): 화면을 기다리게 하지 않고 따로 저장하는지, 줄이 가득 차거나 DB가 실패해도 예외를 내지 않는지.
// DB 대신 JdbcTemplate의 update만 바꿔 끼워, 어느 스레드에서 몇 번 불렸는지 본다
class PageFlowRecorderTest {

	private final List<String> threads = new CopyOnWriteArrayList<>();

	@Test
	void 일꾼_스레드가_따로_저장한다() throws Exception {
		CountDownLatch saved = new CountDownLatch(1);
		PageFlowRecorder recorder = new PageFlowRecorder(jdbc(null, saved, false), 10);

		recorder.record(view(1));

		assertThat(saved.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(threads).containsExactly("page-flow-writer");
		recorder.stop();
	}

	// DB가 멈춘 사이 줄이 가득 차면 새 줄은 버린다. 부르는 쪽(화면)은 기다리지도 예외를 받지도 않는다
	@Test
	void 줄이_가득_차면_버리고_예외를_내지_않는다() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		PageFlowRecorder recorder = new PageFlowRecorder(jdbc(release, null, false), 2);

		long start = System.nanoTime();
		for (int seq = 1; seq <= 10; seq++) {
			int line = seq;
			assertThatCode(() -> recorder.record(view(line))).doesNotThrowAnyException();
		}
		assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1000);

		release.countDown();
		recorder.stop();
		// 일꾼이 붙잡고 있던 1줄 + 줄에서 기다린 2줄만 저장되고 나머지 7줄은 버려졌다
		assertThat(threads).hasSize(3);
	}

	// 서버를 끌 때(배포) 줄에 남은 것을 마저 저장한다
	@Test
	void 끌_때_줄에_남은_것을_마저_저장한다() throws Exception {
		PageFlowRecorder recorder = new PageFlowRecorder(jdbc(null, null, false), 10);
		for (int seq = 1; seq <= 5; seq++) {
			recorder.record(view(seq));
		}

		recorder.stop();

		assertThat(threads).hasSize(5);
	}

	@Test
	void 저장이_실패해도_예외를_내지_않는다() throws Exception {
		PageFlowRecorder direct = new PageFlowRecorder(jdbc(null, null, true), 0);
		assertThatCode(() -> direct.record(view(1))).doesNotThrowAnyException();

		PageFlowRecorder background = new PageFlowRecorder(jdbc(null, null, true), 10);
		assertThatCode(() -> background.record(view(1))).doesNotThrowAnyException();
		background.stop();
		assertThat(threads).hasSize(2);
	}

	// release: 이 값이 풀릴 때까지 저장을 붙잡는다 (DB가 멈춘 것처럼) / saved: 저장할 때마다 하나씩 내린다 / fail: 저장이 실패한다
	private JdbcTemplate jdbc(CountDownLatch release, CountDownLatch saved, boolean fail) {
		return new JdbcTemplate() {
			@Override
			public int update(String sql, Object[] args, int[] argTypes) {
				threads.add(Thread.currentThread().getName());
				if (release != null) {
					try {
						release.await(5, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
				if (saved != null) {
					saved.countDown();
				}
				if (fail) {
					throw new IllegalStateException("DB down");
				}
				return 1;
			}
		};
	}

	private static PageView view(int seq) {
		return new PageView(42L, seq, Screen.FEED, 1L, null, Device.MOBILE, null);
	}
}
