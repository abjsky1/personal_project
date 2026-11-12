package backend.analysis.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import backend.analysis.model.dto.AnalysisStatusResponse;
import backend.analysis.model.dto.ConfirmedItem;
import backend.analysis.model.dto.FoodAnalysisResult;
import backend.analysis.model.dto.FoodAnalysisResult.FoodItem;
import backend.analysis.model.dto.FoodAnalysisView;
import backend.analysis.model.dto.FoodAnalysisView.ConfirmRow;
import backend.analysis.model.dto.FoodAnalysisView.Option;
import backend.analysis.model.dto.FoodAnalyzerResponse;
import backend.analysis.model.dto.FoodCandidates;
import backend.analysis.model.dto.FoodCandidates.Candidate;
import backend.analysis.model.dto.FoodCandidates.CandidateItem;
import backend.analysis.model.dto.FoodCandidatesResponse;
import backend.analysis.model.entity.AnalysisStatus;
import backend.analysis.model.entity.ConfirmMode;
import backend.analysis.model.entity.FoodAnalysis;
import backend.analysis.model.repository.FoodAnalysisRepository;
import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;
import backend.image.ImageStorage;
import backend.post.model.entity.PostStatus;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

// AI 분석을 실행하고 결과를 저장한다. 사용자가 고쳐서 확정하는 것도 여기서 처리한다 (JM-74, 고쳐 쓰는 AI).
@Slf4j
@Service
public class FoodAnalysisService {

	// 합계가 이보다 크면 AI 응답을 믿지 않고 실패로 처리한다. (한 끼 10,000kcal 같은 이상치, 기획서 6.3절)
	private static final double MAX_TOTAL = 10_000;
	// 하루의 기준: 한국 시간 자정
	private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
	// 음식마다 보관하는 후보 수, 후보 이름의 길이 (AI가 더 많이, 더 길게 줘도 여기까지만 쓴다)
	private static final int MAX_CANDIDATES = 3;
	private static final int MAX_NAME_LENGTH = 50;
	// 100g당 칼로리의 상한. 기름이 100g당 약 900kcal라서 이보다 크면 "사진 속 양" 기준으로 잘못 답한 것으로 본다.
	private static final double MAX_KCAL_PER_100G = 1_000;
	// 확정할 때 받는 양의 범위 (g)
	private static final double MIN_AMOUNT = 1;
	private static final double MAX_AMOUNT = 5_000;

	private final FoodAnalysisRepository foodAnalysisRepository;
	private final FoodAnalyzer foodAnalyzer;
	private final ImageStorage imageStorage;
	private final JsonMapper jsonMapper;
	// 전체 하루 분석 횟수 상한 (application.properties의 app.analysis.daily-limit)
	private final int dailyLimit;

	public FoodAnalysisService(
			FoodAnalysisRepository foodAnalysisRepository,
			FoodAnalyzer foodAnalyzer,
			ImageStorage imageStorage,
			JsonMapper jsonMapper,
			@Value("${app.analysis.daily-limit}") int dailyLimit) {
		this.foodAnalysisRepository = foodAnalysisRepository;
		this.foodAnalyzer = foodAnalyzer;
		this.imageStorage = imageStorage;
		this.jsonMapper = jsonMapper;
		this.dailyLimit = dailyLimit;
	}

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

		if (isDailyLimitReached()) {
			analysis.markLimitExceeded();
			foodAnalysisRepository.save(analysis);
			log.warn("Daily analysis limit reached: postId={}, limit={}", postId, dailyLimit);
			return;
		}

		long startedAt = System.currentTimeMillis();
		try {
			byte[] jpeg = imageStorage.load(imageKey).orElseThrow(() -> new FoodAnalysisException("IMAGE_NOT_FOUND"));
			if (analysis.getConfirmMode() == ConfirmMode.USER) {
				// "내가 보고 확정"으로 올린 글: 음식마다 후보를 받는다 (JM-74)
				FoodCandidatesResponse response = foodAnalyzer.analyzeCandidates(jpeg);
				saveCandidates(analysis, response, System.currentTimeMillis() - startedAt);
			} else {
				FoodAnalyzerResponse response = foodAnalyzer.analyze(jpeg);
				saveResult(analysis, response, System.currentTimeMillis() - startedAt);
			}
		} catch (FoodAnalysisException e) {
			saveFailure(analysis, e.getMessage(), startedAt);
		} catch (RuntimeException e) {
			// 예상하지 못한 오류. 뒤에서 도는 작업이라 여기서 안 잡으면 사용자 화면 없이 글이 계속 "분석 중"으로 남는다.
			log.error("Unexpected food analysis error: postId={}", postId, e);
			saveFailure(analysis, "UNEXPECTED", startedAt);
		}
	}

	// 오늘(한국 시간 자정부터) AI 호출이 몇 번 끝났는지 DB에서 센다.
	// 메모리가 아니라 DB에서 세는 이유: 배포로 서버를 다시 켜도 오늘 횟수가 0으로 돌아가지 않는다.
	// 일꾼 2명이 동시에 분석하면 상한을 1~2건 넘을 수 있지만, 비용을 막는 장치라 그 정도는 괜찮다.
	private boolean isDailyLimitReached() {
		return countToday() >= dailyLimit;
	}

	// 오늘(한국 시간 자정부터) 끝난 AI 호출 수. 상한 검사와 운영자 통계가 같은 숫자를 본다.
	public long countToday() {
		Instant startOfToday = LocalDate.now(KOREA).atStartOfDay(KOREA).toInstant();
		return foodAnalysisRepository.countByCompletedAtGreaterThanEqual(startOfToday);
	}

	public int getDailyLimit() {
		return dailyLimit;
	}

	private void saveResult(FoodAnalysis analysis, FoodAnalyzerResponse response, long latencyMs) {
		markFinished(analysis, response, latencyMs);
		foodAnalysisRepository.save(analysis);
		logFinished(analysis, response, latencyMs);
	}

	// 후보 분석의 결과를 저장한다 (JM-74).
	// 음식마다 첫 번째 후보를 "AI의 첫 답"으로 삼아 지금까지와 같은 자리(합계, 음식 목록)에 저장하고,
	// 후보 전체는 따로 보관해 두었다가 사용자가 확정할 때 꺼내 쓴다. AI의 첫 답은 확정해도 덮어쓰지 않는다.
	private void saveCandidates(FoodAnalysis analysis, FoodCandidatesResponse response, long latencyMs) {
		FoodCandidates candidates = clean(response.result());
		FoodAnalyzerResponse first = new FoodAnalyzerResponse(
				candidates.firstAnswer(), response.model(), response.inputTokens(), response.outputTokens());
		markFinished(analysis, first, latencyMs);
		if (analysis.getStatus() == AnalysisStatus.DONE) {
			analysis.keepCandidates(jsonMapper.writeValueAsString(candidates.items()));
		}
		foodAnalysisRepository.save(analysis);
		logFinished(analysis, first, latencyMs);
	}

	private void markFinished(FoodAnalysis analysis, FoodAnalyzerResponse response, long latencyMs) {
		FoodAnalysisResult result = response.result();
		if (result.isFood()) {
			checkRange(result);
			analysis.markDone(response, jsonMapper.writeValueAsString(result.items()), latencyMs);
		} else {
			analysis.markNotFood(response, latencyMs);
		}
	}

	private void logFinished(FoodAnalysis analysis, FoodAnalyzerResponse response, long latencyMs) {
		log.info("Food analysis finished: postId={}, status={}, mode={}, model={}, latencyMs={}, inputTokens={}, outputTokens={}",
				analysis.getPostId(), analysis.getStatus(), analysis.getConfirmMode(), response.model(), latencyMs,
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
		if (hasNegative || isTooLarge(result)) {
			throw new FoodAnalysisException("OUT_OF_RANGE");
		}
	}

	private boolean isTooLarge(FoodAnalysisResult result) {
		return Stream.of(
				result.total(FoodItem::kcal),
				result.total(FoodItem::carbsG),
				result.total(FoodItem::proteinG),
				result.total(FoodItem::fatG))
				.anyMatch(total -> total.compareTo(BigDecimal.valueOf(MAX_TOTAL)) > 0);
	}

	// AI가 준 후보를 저장하기 전에 다듬고 검사한다.
	// 음식마다 후보는 세 개까지만 두고, 이름이 빈 후보는 버린다. 후보가 하나도 남지 않은 음식이 있거나
	// 음수, 터무니없는 100g당 칼로리가 있으면 믿을 수 없는 답으로 보고 실패로 처리한다.
	private FoodCandidates clean(FoodCandidates raw) {
		if (!raw.isFood()) {
			return new FoodCandidates(false, List.of(), raw.confidence(), raw.comment());
		}
		if (raw.items() == null || raw.items().isEmpty()) {
			throw new FoodAnalysisException("EMPTY_ITEMS");
		}
		List<CandidateItem> items = raw.items().stream().map(this::cleanItem).toList();
		return new FoodCandidates(true, items, raw.confidence(), raw.comment());
	}

	private CandidateItem cleanItem(CandidateItem item) {
		List<Candidate> candidates = item.candidates() == null ? List.of() : item.candidates().stream()
				.filter(candidate -> candidate.name() != null && !candidate.name().isBlank())
				.limit(MAX_CANDIDATES)
				.map(candidate -> new Candidate(shortenName(candidate.name()), candidate.kcalPer100g(),
						candidate.carbsPer100g(), candidate.proteinPer100g(), candidate.fatPer100g()))
				.toList();
		if (candidates.isEmpty()) {
			throw new FoodAnalysisException("EMPTY_CANDIDATES");
		}
		boolean outOfRange = item.amountG() < 0 || candidates.stream().anyMatch(candidate -> candidate.kcalPer100g() < 0
				|| candidate.kcalPer100g() > MAX_KCAL_PER_100G || candidate.carbsPer100g() < 0
				|| candidate.proteinPer100g() < 0 || candidate.fatPer100g() < 0);
		if (outOfRange) {
			throw new FoodAnalysisException("OUT_OF_RANGE");
		}
		return new CandidateItem(item.amountG(), candidates);
	}

	private String shortenName(String name) {
		String stripped = name.strip();
		return stripped.length() <= MAX_NAME_LENGTH ? stripped : stripped.substring(0, MAX_NAME_LENGTH);
	}

	// 사용자가 고쳐서 확정한다 (JM-74).
	// picks: 음식마다 고른 후보의 순서 (0 = AI의 첫 답, -1 = 이 음식 아님) / amounts: 음식마다 양(g). 둘 다 화면의 음식 순서와 같다.
	//
	// 화면이 보내는 것은 "몇 번째 후보, 몇 g"뿐이다. 이름과 영양은 저장해 둔 후보에서 꺼내 서버가 계산한다.
	// → 화면의 숫자를 고쳐서 보내도 결과를 마음대로 만들 수 없다.
	// 글의 비밀번호 확인은 PostService가 먼저 한다 (이 클래스는 비밀번호를 모른다).
	// 여러 번 확정할 수 있다. 그때마다 확정한 결과를 새로 쓰고 횟수를 올린다. AI의 첫 답은 건드리지 않는다.
	@Transactional
	public void confirm(Long postId, List<String> picks, List<String> amounts) {
		FoodAnalysis analysis = foodAnalysisRepository.findById(postId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_CONFIRMABLE));
		List<CandidateItem> candidates = readCandidates(analysis);
		// 후보가 없는 글: "AI가 바로 확정"으로 올린 글, 분석이 끝나지 않았거나 실패한 글
		if (candidates.isEmpty()) {
			throw new BusinessException(ErrorCode.NOT_CONFIRMABLE);
		}
		if (picks == null || amounts == null || picks.size() != candidates.size() || amounts.size() != candidates.size()) {
			throw new BusinessException(ErrorCode.INVALID_CONFIRMATION);
		}

		List<ConfirmedItem> confirmed = new ArrayList<>();
		for (int i = 0; i < candidates.size(); i++) {
			confirmed.add(confirmItem(candidates.get(i), picks.get(i), amounts.get(i)));
		}
		List<FoodItem> kept = confirmed.stream()
				.filter(item -> item.pick() != ConfirmedItem.REMOVED)
				.map(ConfirmedItem::toFoodItem)
				.toList();
		// 합계는 AI의 답과 같은 방식으로 서버가 더한다. 전부 뺐거나 합계가 터무니없으면 받지 않는다.
		FoodAnalysisResult result = new FoodAnalysisResult(true, kept, null, null);
		if (kept.isEmpty() || isTooLarge(result)) {
			throw new BusinessException(ErrorCode.INVALID_CONFIRMATION);
		}

		analysis.confirm(jsonMapper.writeValueAsString(confirmed), result.total(FoodItem::kcal),
				result.total(FoodItem::carbsG), result.total(FoodItem::proteinG), result.total(FoodItem::fatG));

		// 나중에 "AI의 첫 답이 얼마나 맞았는지"를 볼 수 있게, 무엇을 골랐는지 숫자로만 남긴다 (음식 이름은 남기지 않는다)
		long keptFirst = confirmed.stream().filter(item -> item.pick() == 0).count();
		long removed = confirmed.size() - kept.size();
		log.info("Analysis confirmed: postId={}, items={}, keptFirst={}, pickedOther={}, removed={}, aiKcal={}, confirmedKcal={}, confirmCount={}",
				postId, confirmed.size(), keptFirst, kept.size() - keptFirst, removed,
				analysis.getTotalKcal(), analysis.getConfirmedTotalKcal(), analysis.getConfirmCount());
	}

	// 음식 하나의 확정 값을 만든다. 보낸 값이 숫자가 아니거나 범위를 벗어나면 INVALID_CONFIRMATION
	private ConfirmedItem confirmItem(CandidateItem item, String pickText, String amountText) {
		int pick = (int) parseNumber(pickText);
		if (pick == ConfirmedItem.REMOVED) {
			// 뺀 음식: AI가 처음에 무엇이라고 했는지만 이름으로 남긴다
			return new ConfirmedItem(ConfirmedItem.REMOVED, item.candidates().get(0).name(), 0, 0, 0, 0, 0);
		}
		if (pick < 0 || pick >= item.candidates().size()) {
			throw new BusinessException(ErrorCode.INVALID_CONFIRMATION);
		}
		double amount = Math.round(parseNumber(amountText) * 10) / 10.0;
		if (amount < MIN_AMOUNT || amount > MAX_AMOUNT) {
			throw new BusinessException(ErrorCode.INVALID_CONFIRMATION);
		}
		FoodItem food = item.candidates().get(pick).toItem(amount);
		return new ConfirmedItem(pick, food.name(), food.amountG(), food.kcal(), food.carbsG(), food.proteinG(), food.fatG());
	}

	private double parseNumber(String text) {
		try {
			double value = Double.parseDouble(text == null ? "" : text.strip());
			// NaN, Infinity 같은 값은 숫자로 읽히지만 쓸 수 없다
			if (Double.isNaN(value) || Double.isInfinite(value)) {
				throw new BusinessException(ErrorCode.INVALID_CONFIRMATION);
			}
			return value;
		} catch (NumberFormatException e) {
			throw new BusinessException(ErrorCode.INVALID_CONFIRMATION);
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
		return foodAnalysisRepository.findById(postId).map(this::toView).orElse(null);
	}

	private FoodAnalysisView toView(FoodAnalysis analysis) {
		List<CandidateItem> candidates = readCandidates(analysis);
		List<ConfirmedItem> confirmed = readConfirmed(analysis.getConfirmedItems());

		// 화면의 음식 표: 확정했으면 확정한 음식(뺀 것은 제외), 아니면 AI의 첫 답
		List<FoodItem> shown = analysis.isConfirmed()
				? confirmed.stream().filter(item -> item.pick() != ConfirmedItem.REMOVED).map(ConfirmedItem::toFoodItem).toList()
				: readItems(analysis.getItems());

		// 확정 칸의 줄들: 음식마다 후보와 "지금 골라져 있는 값".
		// 확정한 적이 있으면 그때 고른 값에서 이어서 고치게 하고(뺀 음식의 양은 AI가 추정한 양을 다시 보여 준다), 없으면 AI의 첫 답에서 시작한다.
		List<ConfirmRow> rows = new ArrayList<>();
		for (int i = 0; i < candidates.size(); i++) {
			CandidateItem item = candidates.get(i);
			List<Option> options = new ArrayList<>();
			for (int n = 0; n < item.candidates().size(); n++) {
				options.add(new Option(n, item.candidates().get(n).name(), item.candidates().get(n).kcalPer100g()));
			}
			ConfirmedItem now = i < confirmed.size() ? confirmed.get(i) : null;
			int pick = now == null ? 0 : now.pick();
			double amount = now == null || now.pick() == ConfirmedItem.REMOVED ? item.amountG() : now.amountG();
			rows.add(new ConfirmRow(i, options, pick, amount));
		}
		return FoodAnalysisView.of(analysis, shown, rows);
	}

	// JSON 글자로 저장해 둔 음식별 상세를 다시 목록으로 바꾼다. (DONE이 아니면 비어 있다)
	private List<FoodItem> readItems(String itemsJson) {
		if (itemsJson == null) {
			return List.of();
		}
		return jsonMapper.readValue(itemsJson, new TypeReference<List<FoodItem>>() {
		});
	}

	// 저장해 둔 후보. 분석이 끝난(DONE) 글에만 있고, "AI가 바로 확정"으로 올린 글에는 없다 (빈 목록)
	private List<CandidateItem> readCandidates(FoodAnalysis analysis) {
		if (analysis.getStatus() != AnalysisStatus.DONE || analysis.getCandidates() == null) {
			return List.of();
		}
		return jsonMapper.readValue(analysis.getCandidates(), new TypeReference<List<CandidateItem>>() {
		});
	}

	private List<ConfirmedItem> readConfirmed(String confirmedJson) {
		if (confirmedJson == null) {
			return List.of();
		}
		return jsonMapper.readValue(confirmedJson, new TypeReference<List<ConfirmedItem>>() {
		});
	}
}
