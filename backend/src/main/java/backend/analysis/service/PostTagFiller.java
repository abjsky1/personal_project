package backend.analysis.service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
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
// - AI에게서 답을 받지 못하면(장애, 시간 초과, 사진 저장소가 잠깐 안 됨) 맡은 것을 돌려주고 그 회차를 멈춘다: 장애가 길어도 5분에 한 번만 부르고,
//   최신 글들이 장애 동안 3번을 다 써 버려 영영 채워지지 않는 일이 없다 (PR 전 검토)
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

	// 일정 스레드는 둘이다 (spring.task.scheduling.pool.size=2): 이 채우기가 AI를 기다리는 동안에도 다시 넣기(PendingAnalysisRequeuer)는 제때 돈다.
	// 한 번에 20개면 AI 응답(태그만 묻는 호출은 가운데 1.6초)으로 40초쯤 걸린다.
	// AI에게서 답을 받지 못하면(장애, 시간 초과) 그 회차는 바로 멈춘다: 장애 동안 20개를 차례로 기다리지 않고, 5분에 한 번만 부르게 (PR 전 검토)
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
				} else if (outcome == Outcome.STOPPED) {
					break;
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

	// 한 글을 채운 결과
	// FILLED: 채움. FAILED: 채우지 못하고 맡은 횟수만 하나 늘었다 (이상한 답, 사진 파일이 없음 등. 3번이면 더 찾지 않는다)
	// STOPPED: AI에게서 답을 받지 못해 맡은 것을 돌려주었다. 이 회차는 여기서 멈춘다. SKIPPED: 다른 app이 맡았거나 이미 채운 글
	enum Outcome {
		FILLED, FAILED, STOPPED, SKIPPED
	}

	// 한 글의 태그를 채운다. 맡은 시각은 마이크로초까지로 자른다: 돌려줄 때 DB 칸(DATETIME(6))의 값과 같은지 비교한다
	Outcome fillOne(UntaggedPost target) {
		Instant claim = Instant.now().truncatedTo(ChronoUnit.MICROS);
		if (foodAnalysisRepository.claimTagging(target.postId(), claim, claim.minus(CLAIM_STALE_AFTER)) == 0) {
			return Outcome.SKIPPED;
		}
		long startedAt = System.currentTimeMillis();
		try {
			FoodTagsResponse response = foodAnalyzer.classify(loadPhoto(target.imageKey()));
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
			long latencyMs = System.currentTimeMillis() - startedAt;
			if (e.isNoAnswer()) {
				foodAnalysisRepository.releaseTagging(target.postId(), claim);
				log.warn("Post tag fill paused, no answer: postId={}, reason={}, latencyMs={}", target.postId(), e.getMessage(), latencyMs);
				return Outcome.STOPPED;
			}
			log.warn("Post tag fill failed: postId={}, reason={}, latencyMs={}", target.postId(), e.getMessage(), latencyMs);
			return Outcome.FAILED;
		} catch (DataAccessException | TransactionException e) {
			// DB 오류는 이 회차를 멈추게 밖(fill)으로 넘긴다
			throw e;
		} catch (RuntimeException e) {
			// 예상하지 못한 오류도 이 글 하나의 실패로 남기고 다음 글로 간다 (밖으로 던지면 Spring이 ERROR로 적어 에러 메일이 간다, PR 전 검토)
			log.warn("Post tag fill failed: postId={}, reason={}, latencyMs={}", target.postId(), e.getClass().getSimpleName(),
					System.currentTimeMillis() - startedAt);
			return Outcome.FAILED;
		}
	}

	// 사진을 읽는다. 파일이 없으면 다시 해도 소용없는 실패(IMAGE_NOT_FOUND), 저장소가 잠깐 안 되면(S3 네트워크, 혼잡) 답을 받지 못한 실패(IMAGE_LOAD)
	private byte[] loadPhoto(String imageKey) {
		Optional<byte[]> photo;
		try {
			photo = imageStorage.load(imageKey);
		} catch (RuntimeException e) {
			throw new FoodAnalysisException("IMAGE_LOAD");
		}
		return photo.orElseThrow(() -> new FoodAnalysisException("IMAGE_NOT_FOUND"));
	}
}
