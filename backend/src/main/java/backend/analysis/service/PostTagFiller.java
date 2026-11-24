package backend.analysis.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

import backend.analysis.model.dto.FoodTagsResponse;
import backend.analysis.model.dto.UntaggedPost;
import backend.analysis.model.entity.FoodTag;
import backend.analysis.model.repository.FoodAnalysisRepository;
import backend.common.logging.TraceIdFilter;
import backend.image.ImageStorage;
import lombok.extern.slf4j.Slf4j;

// 태그가 없는 글의 태그를 채운다 (JM-83, 음식 카테고리).
// 새 글은 분석할 때 태그가 함께 오지만, 태그가 생기기 전에 분석한 지난 글과 태그 저장에 실패한 글은 태그가 없다.
// 몇 분마다 그런 글을 최신 것부터 조금씩 찾아, 사진을 AI에 다시 보내 태그만 고르게 한다 (운영자 결정 11-24: 사진을 다시 보내 정확하게).
// 지난 글 161개(11-24)는 5분마다 20개씩이면 배포 뒤 40분쯤에 다 채워지고, 그 뒤에는 실패한 새 글을 다시 채우는 안전망으로 남는다.
//
// AI 비용을 지키는 장치
// - 한 글은 FoodAnalysis.MAX_TAG_ATTEMPTS번까지만 맡는다 (사진이 없거나 AI가 계속 실패하는 글에 끝없이 부르지 않게)
// - 부르기 전에 그 글을 맡는다(claimTagging): 무중단 배포로 app 둘이 잠깐 같이 떠 있어도 한 글에 AI는 한 번만 불린다
// - 오늘 분석 횟수가 상한(app.analysis.daily-limit)에 닿았으면 부르지 않는다. 태그 호출도 그 횟수에 센다 (countToday)
// - 한 번에 batchSize개까지만, 일정 스레드에서 차례로 부른다 (글을 올린 사람의 분석 일꾼과 줄을 쓰지 않는다)
//
// @ConditionalOnProperty: app.tags.fill.enabled가 false면 이 빈을 만들지 않는다 (테스트에서는 꺼서, 시험 데이터를 건드리지 않게)
@Slf4j
@Component
@ConditionalOnProperty(name = "app.tags.fill.enabled", havingValue = "true", matchIfMissing = true)
public class PostTagFiller {

	// 맡은 지 이보다 오래됐는데 아직 태그가 없으면, 맡은 app이 부르는 중에 꺼진 것으로 보고 다시 맡을 수 있게 한다.
	// AI 호출은 길어야 35초쯤이다 (GeminiConfig). 분석의 CLAIM_STALE_AFTER와 같은 값
	static final Duration CLAIM_STALE_AFTER = Duration.ofMinutes(3);

	private final FoodAnalysisRepository foodAnalysisRepository;
	private final FoodAnalysisService foodAnalysisService;
	private final PostTagService postTagService;
	private final FoodAnalyzer foodAnalyzer;
	private final ImageStorage imageStorage;
	// 한 번에 채우는 최대 수
	private final int batchSize;

	public PostTagFiller(
			FoodAnalysisRepository foodAnalysisRepository,
			FoodAnalysisService foodAnalysisService,
			PostTagService postTagService,
			FoodAnalyzer foodAnalyzer,
			ImageStorage imageStorage,
			@Value("${app.tags.fill.batch-size}") int batchSize) {
		this.foodAnalysisRepository = foodAnalysisRepository;
		this.foodAnalysisService = foodAnalysisService;
		this.postTagService = postTagService;
		this.foodAnalyzer = foodAnalyzer;
		this.imageStorage = imageStorage;
		this.batchSize = batchSize;
	}

	// 다시 넣기(PendingAnalysisRequeuer)와 같은 일정 스레드에서 차례로 돈다. 한 번에 20개면 AI 응답(태그만 묻는 호출은 가운데 1.6초)으로 40초쯤 걸린다.
	// 그동안 다시 넣기는 차례를 기다린다 (5분 간격이라 늦어도 1분 안쪽)
	@Scheduled(initialDelayString = "${app.tags.fill.initial-delay}", fixedDelayString = "${app.tags.fill.interval}")
	public void fill() {
		// 이 실행의 로그에 같은 번호(traceId)를 붙인다
		MDC.put(TraceIdFilter.TRACE_ID_KEY, "tags-" + UUID.randomUUID().toString().substring(0, 8));
		try {
			List<UntaggedPost> targets = foodAnalysisRepository.findUntagged(Instant.now().minus(CLAIM_STALE_AFTER), batchSize);
			if (targets.isEmpty()) {
				return;
			}
			int filled = 0;
			int failed = 0;
			for (UntaggedPost target : targets) {
				if (foodAnalysisService.isDailyLimitReached()) {
					log.warn("Post tag fill stopped, daily analysis limit reached: limit={}", foodAnalysisService.getDailyLimit());
					break;
				}
				Outcome outcome = fillOne(target);
				if (outcome == Outcome.FILLED) {
					filled++;
				} else if (outcome == Outcome.FAILED) {
					failed++;
				}
			}
			log.info("Post tag fill run: found={}, filled={}, failed={}", targets.size(), filled, failed);
		} catch (DataAccessException | TransactionException e) {
			// DB가 잠깐 안 될 때. 다음 차례에 다시 하므로 경고만 남긴다 (에러 경보를 울리지 않게, 다시 넣기와 같다)
			log.warn("Post tag fill skipped, DB error: {}", e.getClass().getSimpleName());
		} finally {
			MDC.remove(TraceIdFilter.TRACE_ID_KEY);
		}
	}

	// 한 글을 채운 결과. FAILED는 맡은 횟수만 하나 늘었고, SKIPPED는 다른 app이 맡았거나 이미 채운 글이다
	enum Outcome {
		FILLED, FAILED, SKIPPED
	}

	// 한 글의 태그를 채운다
	Outcome fillOne(UntaggedPost target) {
		Instant now = Instant.now();
		if (foodAnalysisRepository.claimTagging(target.postId(), now, now.minus(CLAIM_STALE_AFTER)) == 0) {
			return Outcome.SKIPPED;
		}
		long startedAt = System.currentTimeMillis();
		try {
			byte[] jpeg = imageStorage.load(target.imageKey()).orElseThrow(() -> new FoodAnalysisException("IMAGE_NOT_FOUND"));
			FoodTagsResponse response = foodAnalyzer.classify(jpeg);
			Set<FoodTag> tags = response.tags() == null ? Set.of() : response.tags().toTags();
			if (tags.isEmpty()) {
				throw new FoodAnalysisException("EMPTY_TAGS");
			}
			postTagService.replace(target.postId(), tags);
			log.info("Post tags filled: postId={}, tags={}, model={}, latencyMs={}, inputTokens={}, outputTokens={}",
					target.postId(), tags, response.model(), System.currentTimeMillis() - startedAt,
					response.inputTokens(), response.outputTokens());
			return Outcome.FILLED;
		} catch (FoodAnalysisException e) {
			log.warn("Post tag fill failed: postId={}, reason={}, latencyMs={}", target.postId(), e.getMessage(),
					System.currentTimeMillis() - startedAt);
			return Outcome.FAILED;
		}
	}
}
