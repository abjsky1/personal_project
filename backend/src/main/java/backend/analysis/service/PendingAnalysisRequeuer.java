package backend.analysis.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import backend.analysis.model.dto.PendingAnalysis;
import backend.analysis.model.entity.FoodAnalysis;
import backend.analysis.model.repository.FoodAnalysisRepository;
import backend.common.logging.TraceIdFilter;
import lombok.extern.slf4j.Slf4j;

// 줄에서 버려진 AI 분석을 다시 줄에 넣는다 (JM-110).
// 분석 줄은 app의 메모리에만 있어서, app을 끌 때(배포, 재시작) 줄에서 기다리던 분석은 버려지고 그 글은 PENDING으로 남는다.
// 줄이 가득 차서 넣지 못한 글(PostService.startAnalysis)도 같다. 몇 분마다 그런 글을 찾아 다시 넣는다.
//
// 켤 때 한 번만 하지 않는 이유: 무중단 배포(JM-62)에서는 새 app이 먼저 켜지고, 옛 app이 1분쯤 뒤에 꺼지면서 줄의 일을 버린다.
// 켤 때 찾으면 그 일은 아직 버려지기 전이라 놓친다.
// 같은 글을 두 app이 함께 넣어도 FoodAnalysisService.analyze가 처음에 그 글을 맡아서(claimAnalysis) AI는 한 번만 불린다.
// 하루 분석 한도도 analyze가 검사한다 (넘으면 LIMIT_EXCEEDED).
// 한 글은 FoodAnalysis.MAX_CLAIMS번까지만 맡는다. 다 쓰고도 PENDING이면 FAILED(STUCK)로 끝내서, 같은 글에 AI를 끝없이 부르지 않는다.
//
// @ConditionalOnProperty: app.analysis.requeue.enabled가 false면 이 빈을 만들지 않는다 (테스트에서는 꺼서, 시험 데이터를 건드리지 않게)
@Slf4j
@Component
@ConditionalOnProperty(name = "app.analysis.requeue.enabled", havingValue = "true", matchIfMissing = true)
public class PendingAnalysisRequeuer {

	private final FoodAnalysisRepository foodAnalysisRepository;
	private final FoodAnalysisService foodAnalysisService;
	// 만든 지 이만큼 지난 PENDING만 다시 넣는다. 그보다 새 글은 아직 줄에서 차례를 기다리는 중일 수 있어서 건드리지 않는다
	private final Duration minAge;
	// 만든 지 이보다 오래된 PENDING은 다시 넣지 않는다. 이 기능이 생기기 전부터 남은 몇 주 전 글을 갑자기 분석하지 않게 (운영자가 따로 본다)
	private final Duration maxAge;
	// 한 번에 넣는 최대 수. 줄(50)을 다시 넣기가 다 차지하지 않게 하고, 한꺼번에 AI를 많이 부르지 않게 한다
	private final int batchSize;

	public PendingAnalysisRequeuer(
			FoodAnalysisRepository foodAnalysisRepository,
			FoodAnalysisService foodAnalysisService,
			@Value("${app.analysis.requeue.min-age}") Duration minAge,
			@Value("${app.analysis.requeue.max-age}") Duration maxAge,
			@Value("${app.analysis.requeue.batch-size}") int batchSize) {
		this.foodAnalysisRepository = foodAnalysisRepository;
		this.foodAnalysisService = foodAnalysisService;
		this.minAge = minAge;
		this.maxAge = maxAge;
		this.batchSize = batchSize;
	}

	// @Scheduled: 정해진 간격마다 Spring이 이 메서드를 부른다 (SchedulingConfig의 @EnableScheduling이 켠다).
	// fixedDelay: 앞의 실행이 끝난 뒤 간격만큼 쉬고 다시 부른다. initialDelay: 켜진 뒤 처음 부르기까지 기다리는 시간
	@Scheduled(initialDelayString = "${app.analysis.requeue.initial-delay}", fixedDelayString = "${app.analysis.requeue.interval}")
	public void requeue() {
		// 이 실행의 로그와, 여기서 다시 넣은 분석의 로그에 같은 번호(traceId)를 붙인다 (일꾼은 AsyncConfig의 TaskDecorator로 이 번호를 이어받는다)
		MDC.put(TraceIdFilter.TRACE_ID_KEY, "requeue-" + UUID.randomUUID().toString().substring(0, 8));
		try {
			Instant now = Instant.now();
			Instant staleBefore = now.minus(FoodAnalysisService.CLAIM_STALE_AFTER);
			int givenUp = foodAnalysisRepository.failExhausted(staleBefore);
			if (givenUp > 0) {
				log.warn("Pending analyses given up after {} claims, marked FAILED(STUCK): count={}", FoodAnalysis.MAX_CLAIMS, givenUp);
			}
			List<PendingAnalysis> targets = foodAnalysisRepository.findStalePending(
					now.minus(maxAge), now.minus(minAge), staleBefore, batchSize);
			if (targets.isEmpty()) {
				return;
			}
			int queued = 0;
			for (PendingAnalysis target : targets) {
				try {
					// @Async 메서드라 줄에 넣기만 하고 바로 돌아온다
					foodAnalysisService.analyzeInBackground(target.postId(), target.imageKey());
					queued++;
				} catch (TaskRejectedException e) {
					// 줄이 가득 찼다. 남은 것은 다음 차례에 다시 찾는다
					log.warn("Analysis queue is full while requeueing: queued={}, left={}", queued, targets.size() - queued);
					break;
				}
			}
			log.info("Pending analyses requeued: found={}, queued={}, postIds={}", targets.size(), queued,
					targets.stream().map(PendingAnalysis::postId).toList());
		} catch (DataAccessException e) {
			// DB가 잠깐 안 될 때(RDS 점검 등). 다음 차례에 다시 하므로 경고만 남긴다 (에러 경보를 울리지 않게)
			log.warn("Pending analysis requeue skipped, DB error: {}", e.getClass().getSimpleName());
		} finally {
			MDC.remove(TraceIdFilter.TRACE_ID_KEY);
		}
	}
}
