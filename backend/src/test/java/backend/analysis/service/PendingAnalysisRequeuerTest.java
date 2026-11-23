package backend.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskRejectedException;

import backend.analysis.model.dto.PendingAnalysis;
import backend.analysis.model.repository.FoodAnalysisRepository;

// 줄에서 버려진 분석을 다시 줄에 넣는 일(JM-110)을 가짜 객체로 확인하는 테스트.
// 어떤 글을 고르는지는 FoodAnalysisRepositoryTest가 실제 DB로 확인한다
class PendingAnalysisRequeuerTest {

	private static final Duration MIN_AGE = Duration.ofMinutes(3);
	private static final int BATCH = 10;

	private final FoodAnalysisRepository repository = mock(FoodAnalysisRepository.class);
	private final FoodAnalysisService service = mock(FoodAnalysisService.class);
	private final PendingAnalysisRequeuer requeuer = new PendingAnalysisRequeuer(repository, service, MIN_AGE, BATCH);

	@Test
	void 찾은_PENDING을_모두_다시_줄에_넣는다() {
		when(repository.findStalePending(any(), any(), anyInt()))
				.thenReturn(List.of(new PendingAnalysis(3L, "a.jpg"), new PendingAnalysis(4L, "b.jpg")));

		requeuer.requeue();

		verify(service).analyzeInBackground(3L, "a.jpg");
		verify(service).analyzeInBackground(4L, "b.jpg");
	}

	@Test
	void 만든_지_3분과_맡은_지_3분을_기준으로_한_번에_10개까지_찾는다() {
		when(repository.findStalePending(any(), any(), anyInt())).thenReturn(List.of());
		Instant before = Instant.now();

		requeuer.requeue();

		ArgumentCaptor<Instant> createdBefore = ArgumentCaptor.forClass(Instant.class);
		ArgumentCaptor<Instant> staleBefore = ArgumentCaptor.forClass(Instant.class);
		verify(repository).findStalePending(createdBefore.capture(), staleBefore.capture(), eq(BATCH));
		Instant after = Instant.now();
		assertThat(createdBefore.getValue()).isBetween(before.minus(MIN_AGE), after.minus(MIN_AGE));
		assertThat(staleBefore.getValue()).isBetween(before.minus(FoodAnalysisService.CLAIM_STALE_AFTER),
				after.minus(FoodAnalysisService.CLAIM_STALE_AFTER));
	}

	@Test
	void 줄이_가득_차면_거기서_멈추고_남은_것은_다음_차례로_미룬다() {
		when(repository.findStalePending(any(), any(), anyInt())).thenReturn(List.of(
				new PendingAnalysis(3L, "a.jpg"), new PendingAnalysis(4L, "b.jpg"), new PendingAnalysis(5L, "c.jpg")));
		doThrow(new TaskRejectedException("full")).when(service).analyzeInBackground(4L, "b.jpg");

		requeuer.requeue();

		verify(service).analyzeInBackground(3L, "a.jpg");
		verify(service).analyzeInBackground(4L, "b.jpg");
		verify(service, never()).analyzeInBackground(5L, "c.jpg");
	}

	@Test
	void 찾은_것이_없으면_아무것도_넣지_않는다() {
		when(repository.findStalePending(any(), any(), anyInt())).thenReturn(List.of());

		requeuer.requeue();

		verify(service, never()).analyzeInBackground(anyLong(), anyString());
	}
}
