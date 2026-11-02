package backend.analysis.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import backend.analysis.model.dto.AnalysisStatusResponse;
import backend.analysis.model.dto.FoodAnalysisResult;
import backend.analysis.model.dto.FoodAnalysisResult.FoodItem;
import backend.analysis.model.dto.FoodAnalysisView;
import backend.analysis.model.dto.FoodAnalyzerResponse;
import backend.analysis.model.entity.FoodAnalysis;
import backend.analysis.model.repository.FoodAnalysisRepository;
import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;
import backend.image.ImageStorage;
import backend.post.model.entity.PostStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

// AI 분석을 실행하고 결과를 저장한다.
@Slf4j
@Service
@RequiredArgsConstructor
public class FoodAnalysisService {

	// 합계가 이보다 크면 AI 응답을 믿지 않고 실패로 처리한다. (한 끼 10,000kcal 같은 이상치, 기획서 6.3절)
	private static final double MAX_TOTAL = 10_000;

	private final FoodAnalysisRepository foodAnalysisRepository;
	private final FoodAnalyzer foodAnalyzer;
	private final ImageStorage imageStorage;
	private final JsonMapper jsonMapper;

	// @Async: 이 메서드를 부른 쪽은 끝날 때까지 기다리지 않고, 일은 뒤에서 다른 스레드(일꾼)가 한다.
	// → 사용자는 AI 응답을 기다리지 않고 글 올리기를 바로 끝낼 수 있다. (일꾼 수는 application.properties)
	// 주의: 같은 클래스 안에서 부르면 @Async가 걸리지 않는다. (@Transactional의 self-invocation과 같은 이유)
	@Async
	public void analyzeInBackground(Long postId, String imageKey) {
		analyze(postId, imageKey);
	}

	// 분석 한 번을 끝까지 처리한다. 무슨 일이 있어도 PENDING으로 남겨 두지 않고 결과나 실패를 저장한다.
	// @Transactional을 붙이지 않는다: AI 응답을 기다리는 몇 초 동안 DB 연결을 붙잡고 있지 않게 한다. (사진 처리와 같은 이유)
	public void analyze(Long postId, String imageKey) {
		FoodAnalysis analysis = foodAnalysisRepository.findById(postId).orElse(null);
		if (analysis == null) {
			log.warn("Food analysis row not found: postId={}", postId);
			return;
		}

		long startedAt = System.currentTimeMillis();
		try {
			byte[] jpeg = imageStorage.load(imageKey).orElseThrow(() -> new FoodAnalysisException("IMAGE_NOT_FOUND"));
			FoodAnalyzerResponse response = foodAnalyzer.analyze(jpeg);
			saveResult(analysis, response, System.currentTimeMillis() - startedAt);
		} catch (FoodAnalysisException e) {
			saveFailure(analysis, e.getMessage(), startedAt);
		} catch (RuntimeException e) {
			// 예상하지 못한 오류. 뒤에서 도는 작업이라 여기서 안 잡으면 사용자 화면 없이 글이 계속 "분석 중"으로 남는다.
			log.error("Unexpected food analysis error: postId={}", postId, e);
			saveFailure(analysis, "UNEXPECTED", startedAt);
		}
	}

	private void saveResult(FoodAnalysis analysis, FoodAnalyzerResponse response, long latencyMs) {
		FoodAnalysisResult result = response.result();
		if (result.isFood()) {
			checkRange(result);
			analysis.markDone(response, jsonMapper.writeValueAsString(result.items()), latencyMs);
		} else {
			analysis.markNotFood(response, latencyMs);
		}
		foodAnalysisRepository.save(analysis);
		log.info("Food analysis finished: postId={}, status={}, model={}, latencyMs={}, inputTokens={}, outputTokens={}",
				analysis.getPostId(), analysis.getStatus(), response.model(), latencyMs,
				response.inputTokens(), response.outputTokens());
	}

	private void saveFailure(FoodAnalysis analysis, String reason, long startedAt) {
		long latencyMs = System.currentTimeMillis() - startedAt;
		analysis.markFailed(reason, latencyMs);
		foodAnalysisRepository.save(analysis);
		log.warn("Food analysis failed: postId={}, reason={}, latencyMs={}", analysis.getPostId(), reason, latencyMs);
	}

	// 응답의 모양은 responseSchema가 강제하지만, 값이 맞는지는 보장하지 않는다. 그래서 저장하기 전에 믿어도 되는 값인지 확인한다.
	private void checkRange(FoodAnalysisResult result) {
		if (result.items() == null || result.items().isEmpty()) {
			throw new FoodAnalysisException("EMPTY_ITEMS");
		}
		boolean hasNegative = result.items().stream().anyMatch(item -> item.amountG() < 0 || item.kcal() < 0
				|| item.carbsG() < 0 || item.proteinG() < 0 || item.fatG() < 0);
		boolean tooLarge = Stream.of(
				result.total(FoodItem::kcal),
				result.total(FoodItem::carbsG),
				result.total(FoodItem::proteinG),
				result.total(FoodItem::fatG))
				.anyMatch(total -> total.compareTo(BigDecimal.valueOf(MAX_TOTAL)) > 0);
		if (hasNegative || tooLarge) {
			throw new FoodAnalysisException("OUT_OF_RANGE");
		}
	}

	// 분석 상태만 알려 준다. 없는 글, 삭제된 글, 분석 기록이 없는 글은 404로 응답한다.
	@Transactional(readOnly = true)
	public AnalysisStatusResponse getStatus(Long postId) {
		return foodAnalysisRepository.findByPostIdAndPostStatus(postId, PostStatus.ACTIVE)
				.map(analysis -> new AnalysisStatusResponse(analysis.getStatus()))
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
	}

	// 상세 화면용 분석 결과. AI 분석을 넣기 전에 올린 글은 기록이 없어서 null을 돌려준다.
	@Transactional(readOnly = true)
	public FoodAnalysisView getView(Long postId) {
		return foodAnalysisRepository.findById(postId)
				.map(analysis -> FoodAnalysisView.of(analysis, readItems(analysis.getItems())))
				.orElse(null);
	}

	// JSON 글자로 저장해 둔 음식별 상세를 다시 목록으로 바꾼다. (DONE이 아니면 비어 있다)
	private List<FoodItem> readItems(String itemsJson) {
		if (itemsJson == null) {
			return List.of();
		}
		return jsonMapper.readValue(itemsJson, new TypeReference<List<FoodItem>>() {
		});
	}
}
