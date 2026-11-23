package backend.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

import backend.analysis.model.dto.PendingAnalysis;
import backend.analysis.model.repository.FoodAnalysisRepository;
import backend.common.logging.TraceIdFilter;

// 줄에서 버려진 분석을 다시 줄에 넣는 일(JM-110)을 가짜 객체로 확인하는 테스트.
// 어떤 글을 고르는지는 FoodAnalysisRepositoryTest가 실제 DB로, 설정과 일정의 연결은 PendingAnalysisRequeuerScheduleTest가 확인한다
class PendingAnalysisRequeuerTest {

	// 일부러 맡은 지 오래된 기준(3분)과 다른 값으로 둔다: 두 기준을 서로 바꿔 넘기는 실수가 걸리게
	private static final Duration MIN_AGE = Duration.ofMinutes(7);
	private static final Duration MAX_AGE = Duration.ofDays(1);
	private static final int BATCH = 10;

	private final FoodAnalysisRepository repository = mock(FoodAnalysisRepository.class);
	private final FoodAnalysisService service = mock(FoodAnalysisService.class);
	private final PendingAnalysisRequeuer requeuer = new PendingAnalysisRequeuer(repository, service, MIN_AGE, MAX_AGE, BATCH);

	@Test
	void 찾은_PENDING을_모두_다시_줄에_넣는다() {
		when(repository.findStalePending(any(), any(), any(), anyInt()))
				.thenReturn(List.of(new PendingAnalysis(3L, "a.jpg"), new PendingAnalysis(4L, "b.jpg")));

		requeuer.requeue();

		verify(service).analyzeInBackground(3L, "a.jpg");
		verify(service).analyzeInBackground(4L, "b.jpg");
	}

	@Test
	void 하루_안에_만들어져_7분이_지났고_맡은_지_3분이_넘은_것을_한_번에_10개까지_찾는다() {
		when(repository.findStalePending(any(), any(), any(), anyInt())).thenReturn(List.of());
		Instant before = Instant.now();

		requeuer.requeue();

		ArgumentCaptor<Instant> createdAfter = ArgumentCaptor.forClass(Instant.class);
		ArgumentCaptor<Instant> createdBefore = ArgumentCaptor.forClass(Instant.class);
		ArgumentCaptor<Instant> staleBefore = ArgumentCaptor.forClass(Instant.class);
		verify(repository).findStalePending(createdAfter.capture(), createdBefore.capture(), staleBefore.capture(), eq(BATCH));
		Instant after = Instant.now();
		assertThat(createdAfter.getValue()).isBetween(before.minus(MAX_AGE), after.minus(MAX_AGE));
		assertThat(createdBefore.getValue()).isBetween(before.minus(MIN_AGE), after.minus(MIN_AGE));
		assertThat(staleBefore.getValue()).isBetween(before.minus(FoodAnalysisService.CLAIM_STALE_AFTER),
				after.minus(FoodAnalysisService.CLAIM_STALE_AFTER));
	}

	@Test
	void 찾기_전에_맡을_횟수를_다_쓴_글을_같은_기준으로_STUCK으로_끝낸다() {
		when(repository.findStalePending(any(), any(), any(), anyInt())).thenReturn(List.of());

		requeuer.requeue();

		ArgumentCaptor<Instant> exhaustedBefore = ArgumentCaptor.forClass(Instant.class);
		ArgumentCaptor<Instant> staleBefore = ArgumentCaptor.forClass(Instant.class);
		verify(repository).failExhausted(exhaustedBefore.capture());
		verify(repository).findStalePending(any(), any(), staleBefore.capture(), anyInt());
		assertThat(exhaustedBefore.getValue()).isEqualTo(staleBefore.getValue());
	}

	@Test
	void 줄이_가득_차면_거기서_멈추고_남은_것은_다음_차례로_미룬다() {
		when(repository.findStalePending(any(), any(), any(), anyInt())).thenReturn(List.of(
				new PendingAnalysis(3L, "a.jpg"), new PendingAnalysis(4L, "b.jpg"), new PendingAnalysis(5L, "c.jpg")));
		doThrow(new TaskRejectedException("full")).when(service).analyzeInBackground(4L, "b.jpg");

		requeuer.requeue();

		verify(service).analyzeInBackground(3L, "a.jpg");
		verify(service).analyzeInBackground(4L, "b.jpg");
		verify(service, never()).analyzeInBackground(5L, "c.jpg");
	}

	@Test
	void 찾은_것이_없으면_아무것도_넣지_않는다() {
		when(repository.findStalePending(any(), any(), any(), anyInt())).thenReturn(List.of());

		requeuer.requeue();

		verify(service, never()).analyzeInBackground(anyLong(), anyString());
	}

	@Test
	void 쿼리가_실패하면_예외를_밖으로_던지지_않는다() {
		// 던지면 Spring이 일정 작업의 오류를 ERROR로 남겨, DB 점검 때마다 에러 경보가 울린다. 다음 차례에 다시 하면 된다
		when(repository.failExhausted(any())).thenThrow(new DataAccessResourceFailureException("db down"));

		assertThatCode(requeuer::requeue).doesNotThrowAnyException();
		verify(service, never()).analyzeInBackground(anyLong(), anyString());
	}

	@Test
	void DB_연결을_아예_못_얻어도_예외를_밖으로_던지지_않는다() {
		// DB가 꺼져 있으면 트랜잭션을 시작할 때 CannotCreateTransactionException이 난다 (DataAccessException이 아니다. 반박 확인에서 DB를 끄고 재현)
		when(repository.failExhausted(any())).thenThrow(new CannotCreateTransactionException("Could not open JPA EntityManager"));

		assertThatCode(requeuer::requeue).doesNotThrowAnyException();
		verify(service, never()).analyzeInBackground(anyLong(), anyString());
	}

	@Test
	void 다시_넣는_동안_로그_번호를_붙이고_끝나면_지운다() {
		when(repository.findStalePending(any(), any(), any(), anyInt())).thenReturn(List.of(new PendingAnalysis(3L, "a.jpg")));
		List<String> seen = new ArrayList<>();
		doAnswer(invocation -> {
			seen.add(MDC.get(TraceIdFilter.TRACE_ID_KEY));
			return null;
		}).when(service).analyzeInBackground(3L, "a.jpg");

		requeuer.requeue();

		// 일꾼은 줄에 넣을 때의 번호를 이어받는다 (AsyncConfig의 TaskDecorator) → 다시 넣은 분석의 로그를 이 실행과 이을 수 있다
		assertThat(seen).singleElement().asString().startsWith("requeue-");
		assertThat(MDC.get(TraceIdFilter.TRACE_ID_KEY)).isNull();
	}
}
