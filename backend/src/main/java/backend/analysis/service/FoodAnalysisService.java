package backend.analysis.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
import backend.analysis.model.dto.FoodAnalysisView.FoodRow;
import backend.analysis.model.dto.FoodAnalysisView.Option;
import backend.analysis.model.dto.FoodAnalyzerResponse;
import backend.analysis.model.dto.FoodCandidates;
import backend.analysis.model.dto.FoodCandidates.Candidate;
import backend.analysis.model.dto.FoodCandidates.CandidateItem;
import backend.analysis.model.dto.FoodCandidatesResponse;
import backend.analysis.model.dto.TypedEstimates.TypedEstimate;
import backend.analysis.model.dto.TypedEstimatesResponse;
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
	// 확정 칸의 고르는 칸이 보내는 값. 숫자("0", "1", "2")면 후보의 순서이고, 아래 둘은 따로 정한 값이다 (detail.jsp의 option value와 같다)
	private static final String PICK_TYPED = "typed";
	private static final String PICK_REMOVED = "-1";
	// 직접 적는 음식 이름의 길이 (detail.jsp의 maxlength와 같다)
	private static final int MAX_TYPED_NAME_LENGTH = 30;

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

	// 오늘(한국 시간 자정부터) AI를 부른 횟수. 상한 검사와 운영자 통계가 같은 숫자를 본다.
	// 글을 올릴 때의 분석과, 직접 적은 음식을 계산한 호출(JM-74)을 더한다. 둘 다 AI를 한 번씩 부른 것이다.
	public long countToday() {
		Instant startOfToday = LocalDate.now(KOREA).atStartOfDay(KOREA).toInstant();
		return foodAnalysisRepository.countByCompletedAtGreaterThanEqual(startOfToday)
				+ foodAnalysisRepository.countByTypedCalledAtGreaterThanEqual(startOfToday);
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
		boolean outOfRange = item.amountG() < 0 || candidates.stream().anyMatch(candidate -> isOutOfRange(
				candidate.kcalPer100g(), candidate.carbsPer100g(), candidate.proteinPer100g(), candidate.fatPer100g()));
		if (outOfRange) {
			throw new FoodAnalysisException("OUT_OF_RANGE");
		}
		return new CandidateItem(item.amountG(), candidates);
	}

	// 100g당 값이 믿을 수 없는 범위인지: 음수이거나, 칼로리가 터무니없이 큰 경우. 후보와 직접 적은 음식의 답이 함께 쓴다.
	private boolean isOutOfRange(double kcalPer100g, double carbsPer100g, double proteinPer100g, double fatPer100g) {
		return kcalPer100g < 0 || kcalPer100g > MAX_KCAL_PER_100G || carbsPer100g < 0 || proteinPer100g < 0 || fatPer100g < 0;
	}

	private String shortenName(String name) {
		String stripped = name.strip();
		return stripped.length() <= MAX_NAME_LENGTH ? stripped : stripped.substring(0, MAX_NAME_LENGTH);
	}

	// 사용자가 고쳐서 확정한다 (JM-74). 글마다 한 번만 할 수 있고, 확정한 뒤에는 바꿀 수 없다.
	// (여러 번 고칠 수 있으면 "사용자가 무엇으로 고쳤는지"가 하나로 정해지지 않아, 나중에 데이터로 쓰기 어렵다는 운영자 결정)
	//
	// picks: 음식마다 고른 것. "0", "1", "2" = 후보의 순서(0이 AI의 첫 답) / "typed" = 후보에 없어서 이름을 직접 적음 / "-1" = 이 음식 아님
	// amounts: 음식마다 양(g) / names: 음식마다 직접 적은 이름 (직접 적은 줄의 것만 쓴다). 셋 다 화면의 음식 순서와 같다.
	//
	// 화면이 보내는 것은 "몇 번째 후보(또는 적은 이름), 몇 g"뿐이다. 영양은 저장해 둔 후보나 AI의 답에서 꺼내 서버가 계산한다.
	// → 화면의 숫자를 고쳐서 보내도 결과를 마음대로 만들 수 없다.
	// 글의 비밀번호 확인은 PostService가 먼저 한다 (이 클래스는 비밀번호를 모른다).
	//
	// AI를 부르는 횟수: 후보에서만 고르면 부르지 않는다. 직접 적은 음식이 있을 때만 한 번 부르고, 그것도 글마다 한 번뿐이다.
	// → 글 하나에 AI의 답은 많아야 두 번이다 (올릴 때의 분석 한 번, 직접 적은 음식 계산 한 번).
	//
	// @Transactional을 붙이지 않는다: AI의 답을 기다리는 몇 초 동안 DB 연결을 붙잡지 않으려고 (analyze와 같은 이유).
	// 대신 값을 쓰는 곳은 Repository의 UPDATE 한 번씩이고, "아직 확정하지 않았을 때만", "아직 직접 적기를 쓰지 않았을 때만"이라는
	// 조건이 그 UPDATE 안에 있어서, 같은 요청이 동시에 둘 와도 하나만 통과한다.
	public void confirm(Long postId, String imageKey, List<String> picks, List<String> amounts, List<String> names) {
		FoodAnalysis analysis = foodAnalysisRepository.findById(postId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_CONFIRMABLE));
		List<CandidateItem> candidates = readCandidates(analysis);
		// 후보가 없는 글: "AI가 바로 확정"으로 올린 글, 분석이 끝나지 않았거나 실패한 글
		if (candidates.isEmpty()) {
			throw new BusinessException(ErrorCode.NOT_CONFIRMABLE);
		}
		if (analysis.isConfirmed()) {
			throw new BusinessException(ErrorCode.ALREADY_CONFIRMED);
		}
		if (picks == null || amounts == null || picks.size() != candidates.size() || amounts.size() != candidates.size()) {
			throw new BusinessException(ErrorCode.INVALID_CONFIRMATION);
		}

		// 1. 보낸 값을 읽고 검사한다. AI를 부르기 전에 끝낸다 → 양을 잘못 적은 것 같은 실수로 "직접 적기 한 번"을 쓰지 않는다.
		List<Choice> choices = new ArrayList<>();
		for (int i = 0; i < candidates.size(); i++) {
			String name = names != null && i < names.size() ? names.get(i) : null;
			choices.add(readChoice(candidates.get(i), picks.get(i), amounts.get(i), name));
		}
		if (choices.stream().allMatch(Choice::removed)) {
			throw new BusinessException(ErrorCode.INVALID_CONFIRMATION);
		}

		// 2. 후보에서 고른 음식과 뺀 음식은 저장해 둔 후보로 바로 계산한다. 직접 적은 음식의 자리는 비워 둔다 (null).
		List<ConfirmedItem> confirmed = new ArrayList<>();
		for (int i = 0; i < candidates.size(); i++) {
			confirmed.add(fromCandidates(candidates.get(i), choices.get(i)));
		}
		// 여기까지의 합계가 이미 너무 크면 AI를 부르지 않고 돌려보낸다 (직접 적은 음식이 더해지면 더 커질 뿐이다)
		totalsOf(confirmed);

		// 3. 직접 적은 음식이 있으면 AI에게 한 번 물어서 빈자리를 채운다
		List<String> typedNames = choices.stream().filter(Choice::typed).map(Choice::name).toList();
		if (!typedNames.isEmpty()) {
			if (analysis.isTypedUsed()) {
				throw new BusinessException(ErrorCode.TYPED_ALREADY_USED);
			}
			List<TypedEstimate> estimates = askTyped(postId, imageKey, typedNames);
			int next = 0;
			for (int i = 0; i < choices.size(); i++) {
				if (choices.get(i).typed()) {
					confirmed.set(i, fromTyped(choices.get(i), estimates.get(next++)));
				}
			}
		}

		// 4. 합계는 AI의 답과 같은 방식으로 서버가 더하고, 한 번만 쓰이는 UPDATE로 저장한다
		FoodAnalysisResult result = totalsOf(confirmed);
		boolean mismatch = confirmed.stream().anyMatch(item -> Boolean.FALSE.equals(item.matchesPhoto()));
		int updated = foodAnalysisRepository.confirmOnce(postId, jsonMapper.writeValueAsString(confirmed),
				result.total(FoodItem::kcal), result.total(FoodItem::carbsG), result.total(FoodItem::proteinG),
				result.total(FoodItem::fatG), mismatch, Instant.now());
		// 0이면 그사이에 다른 요청이 먼저 확정한 것이다
		if (updated == 0) {
			throw new BusinessException(ErrorCode.ALREADY_CONFIRMED);
		}

		// 나중에 "AI의 첫 답이 얼마나 맞았는지"를 볼 수 있게, 무엇을 골랐는지 숫자로만 남긴다 (음식 이름, 적은 글자는 남기지 않는다)
		long keptFirst = confirmed.stream().filter(item -> Integer.valueOf(0).equals(item.pick())).count();
		long typed = typedNames.size();
		long removed = confirmed.stream().filter(item -> !item.kept()).count();
		log.info("Analysis confirmed: postId={}, items={}, keptFirst={}, pickedOther={}, typed={}, removed={}, typedMismatch={}, aiKcal={}, confirmedKcal={}",
				postId, confirmed.size(), keptFirst, confirmed.size() - keptFirst - typed - removed, typed, removed, mismatch,
				analysis.getTotalKcal(), result.total(FoodItem::kcal));
	}

	// 확정 칸의 한 줄에서 읽은 값. kind: 무엇을 했는지 (ConfirmedItem의 CANDIDATE, TYPED, REMOVED)
	// pick: 고른 후보의 순서 (후보에서 골랐을 때만) / name: 직접 적은 이름 (직접 적었을 때만) / amountG: 양
	private record Choice(String kind, int pick, String name, double amountG) {

		boolean typed() {
			return ConfirmedItem.TYPED.equals(kind);
		}

		boolean removed() {
			return ConfirmedItem.REMOVED.equals(kind);
		}
	}

	// 한 줄의 값을 읽는다. 맞지 않는 값이면 INVALID_CONFIRMATION
	private Choice readChoice(CandidateItem item, String pickText, String amountText, String nameText) {
		String pick = pickText == null ? "" : pickText.strip();
		// 뺀 음식은 양을 보지 않는다 (비워 보내도 된다)
		if (PICK_REMOVED.equals(pick)) {
			return new Choice(ConfirmedItem.REMOVED, -1, null, 0);
		}
		double amount = Math.round(parseNumber(amountText) * 10) / 10.0;
		if (amount < MIN_AMOUNT || amount > MAX_AMOUNT) {
			throw new BusinessException(ErrorCode.INVALID_CONFIRMATION);
		}
		if (PICK_TYPED.equals(pick)) {
			return new Choice(ConfirmedItem.TYPED, -1, cleanTypedName(nameText), amount);
		}
		// 그 밖에는 후보의 순서여야 한다: 숫자 한두 자리이고, 그 음식의 후보 수보다 작아야 한다
		if (!pick.matches("\\d{1,2}") || Integer.parseInt(pick) >= item.candidates().size()) {
			throw new BusinessException(ErrorCode.INVALID_CONFIRMATION);
		}
		return new Choice(ConfirmedItem.CANDIDATE, Integer.parseInt(pick), null, amount);
	}

	// 직접 적은 이름을 다듬고 검사한다.
	// 줄바꿈, 탭 같은 글자는 빈칸으로 바꾸고 이어진 빈칸은 하나로 줄인다: AI에게 보내는 지시문에 이름을 한 줄에 하나씩 넣기 때문이다.
	// (?U): \s가 한글 자판의 넓은 빈칸 같은 유니코드 공백도 잡게 한다 / \p{Cntrl}: 화면에 보이지 않는 제어 문자
	private String cleanTypedName(String raw) {
		String name = raw == null ? "" : raw.replaceAll("(?U)[\\s\\p{Cntrl}]+", " ").strip();
		if (name.isEmpty() || name.length() > MAX_TYPED_NAME_LENGTH) {
			throw new BusinessException(ErrorCode.INVALID_CONFIRMATION);
		}
		return name;
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

	// 후보에서 고른 음식, 뺀 음식의 확정 값을 만든다. 직접 적은 음식은 AI의 답이 있어야 해서 여기서는 null을 돌려준다.
	private ConfirmedItem fromCandidates(CandidateItem item, Choice choice) {
		if (choice.typed()) {
			return null;
		}
		if (choice.removed()) {
			// 뺀 음식: AI가 처음에 무엇이라고 했는지만 이름으로 남긴다
			return ConfirmedItem.removed(item.candidates().get(0).name());
		}
		return ConfirmedItem.candidate(choice.pick(), item.candidates().get(choice.pick()).toItem(choice.amountG()));
	}

	// 직접 적은 음식의 확정 값을 만든다. 이름은 사용자가 적은 것, 100g당 영양은 AI가 사진과 이름을 함께 보고 낸 답이다.
	// 후보와 같은 계산(100g당 값 × 양 ÷ 100)을 쓰려고, 적은 이름과 AI의 답을 후보 하나로 묶는다.
	private ConfirmedItem fromTyped(Choice choice, TypedEstimate estimate) {
		Candidate asCandidate = new Candidate(choice.name(), estimate.kcalPer100g(), estimate.carbsPer100g(),
				estimate.proteinPer100g(), estimate.fatPer100g());
		return ConfirmedItem.typed(asCandidate.toItem(choice.amountG()), estimate.matchesPhoto());
	}

	// 뺀 음식과 아직 빈자리(null)를 제외하고 합계를 낼 수 있게 묶는다. 합계가 터무니없이 크면 CONFIRMATION_TOO_LARGE
	private FoodAnalysisResult totalsOf(List<ConfirmedItem> confirmed) {
		List<FoodItem> kept = confirmed.stream()
				.filter(Objects::nonNull)
				.filter(ConfirmedItem::kept)
				.map(ConfirmedItem::toFoodItem)
				.toList();
		FoodAnalysisResult result = new FoodAnalysisResult(true, kept, null, null);
		if (isTooLarge(result)) {
			throw new BusinessException(ErrorCode.CONFIRMATION_TOO_LARGE);
		}
		return result;
	}

	// 직접 적은 음식을 AI에게 물어본다. 글마다 한 번만 물어볼 수 있다. names와 같은 순서, 같은 개수의 답을 돌려준다.
	//
	// "한 번"을 세는 기준: AI가 답을 줬으면 쓴 것이다. 그 답으로 확정하지 못했어도(음식이 아니라고 함, 쓸 수 없는 답) 쓴 것으로 친다.
	// AI에게서 답을 받지 못했으면(연결 실패, AI 서버 오류) 분석이 이루어지지 않은 것이라 쓰지 않은 것으로 돌려놓는다.
	private List<TypedEstimate> askTyped(Long postId, String imageKey, List<String> names) {
		// 오늘 분석 횟수가 다 찼으면 부르지 않는다 (직접 적기를 쓴 것으로 세지 않는다)
		if (isDailyLimitReached()) {
			log.warn("Daily analysis limit reached, typed estimate skipped: postId={}, limit={}", postId, dailyLimit);
			throw new BusinessException(ErrorCode.TYPED_AI_UNAVAILABLE);
		}
		byte[] jpeg = imageStorage.load(imageKey).orElse(null);
		if (jpeg == null) {
			log.warn("Typed estimate image not found: postId={}", postId);
			throw new BusinessException(ErrorCode.TYPED_AI_UNAVAILABLE);
		}

		// AI를 부르기 전에 "직접 적기 한 번"을 먼저 차지한다. 0이면 그사이에 다른 요청이 먼저 썼거나 확정한 것이다.
		if (foodAnalysisRepository.claimTyped(postId, Instant.now()) == 0) {
			throw new BusinessException(ErrorCode.TYPED_ALREADY_USED);
		}

		long startedAt = System.currentTimeMillis();
		TypedEstimatesResponse response;
		try {
			response = foodAnalyzer.estimateTyped(jpeg, names);
		} catch (FoodAnalysisException e) {
			long latencyMs = System.currentTimeMillis() - startedAt;
			if (gotNoAnswer(e)) {
				foodAnalysisRepository.releaseTyped(postId);
				log.warn("Typed estimate unavailable: postId={}, reason={}, latencyMs={}", postId, e.getMessage(), latencyMs);
				throw new BusinessException(ErrorCode.TYPED_AI_UNAVAILABLE);
			}
			log.warn("Typed estimate failed: postId={}, reason={}, latencyMs={}", postId, e.getMessage(), latencyMs);
			throw new BusinessException(ErrorCode.TYPED_AI_FAILED);
		}
		long latencyMs = System.currentTimeMillis() - startedAt;
		foodAnalysisRepository.recordTypedCall(postId, response.inputTokens(), response.outputTokens(), latencyMs);

		List<TypedEstimate> estimates = response.result() == null ? null : response.result().items();
		if (!isUsable(estimates, names.size())) {
			log.warn("Typed estimate failed: postId={}, reason=UNUSABLE_ANSWER, latencyMs={}", postId, latencyMs);
			throw new BusinessException(ErrorCode.TYPED_AI_FAILED);
		}
		// 적은 글자는 로그에 남기지 않고 개수만 남긴다
		long notFood = estimates.stream().filter(estimate -> !estimate.isFood()).count();
		long mismatch = estimates.stream().filter(estimate -> estimate.isFood() && !estimate.matchesPhoto()).count();
		log.info("Typed foods estimated: postId={}, names={}, notFood={}, mismatch={}, model={}, latencyMs={}, inputTokens={}, outputTokens={}",
				postId, names.size(), notFood, mismatch, response.model(), latencyMs, response.inputTokens(), response.outputTokens());

		// 음식이 아닌 글자가 하나라도 있으면 확정을 받지 않는다 (장난 글이 확정 값으로 남지 않게).
		// 음식이지만 사진과 다르다고 본 것은 받는다: 글쓴이가 사진보다 잘 안다. 대신 저장할 때 표시해 둔다 (typedMismatch)
		if (notFood > 0) {
			throw new BusinessException(ErrorCode.TYPED_NOT_FOOD);
		}
		return estimates;
	}

	// AI에게서 답을 받지 못한 실패인지 (GeminiFoodAnalyzer가 붙이는 이유로 가린다):
	// 키가 없어 부르지 못함, 연결 실패나 시간 초과(NETWORK), AI 서버가 오류로 답함(HTTP_429, HTTP_503 등)
	private boolean gotNoAnswer(FoodAnalysisException e) {
		String reason = e.getMessage();
		return "NO_API_KEY".equals(reason) || "NETWORK".equals(reason) || reason.startsWith("HTTP_");
	}

	// AI의 답을 쓸 수 있는지: 적은 이름과 같은 개수이고, 음식이라고 한 것의 100g당 값이 후보와 같은 범위 안이어야 한다
	private boolean isUsable(List<TypedEstimate> estimates, int expectedCount) {
		if (estimates == null || estimates.size() != expectedCount || estimates.stream().anyMatch(Objects::isNull)) {
			return false;
		}
		return estimates.stream().noneMatch(estimate -> estimate.isFood() && isOutOfRange(
				estimate.kcalPer100g(), estimate.carbsPer100g(), estimate.proteinPer100g(), estimate.fatPer100g()));
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

	// 화면은 AI의 답을 언제나 보여 주고, 확정한 글이면 그 옆에 확정한 값을 함께 보여 준다 (확정했다고 AI의 답을 지우지 않는다).
	private FoodAnalysisView toView(FoodAnalysis analysis) {
		List<FoodItem> aiItems = readItems(analysis.getItems());

		// 확정한 글: 음식마다 AI의 답과 확정한 값을 짝지은 줄을 만든다. 확정은 한 번뿐이라 확정 칸은 더 그리지 않는다 (빈 목록).
		if (analysis.isConfirmed()) {
			List<ConfirmedItem> confirmed = readConfirmed(analysis.getConfirmedItems());
			List<FoodRow> rows = new ArrayList<>();
			for (int i = 0; i < aiItems.size(); i++) {
				// 확정한 값은 AI가 낸 음식과 같은 순서, 같은 개수로 저장돼 있다. 혹시 모자라면 AI의 줄만 그린다
				rows.add(i < confirmed.size() ? compare(aiItems.get(i), confirmed.get(i)) : FoodRow.aiOnly(aiItems.get(i)));
			}
			return FoodAnalysisView.of(analysis, rows, List.of());
		}

		// 확정 전: AI의 줄만 그리고, 후보가 있는 글이면 확정 칸의 줄들을 만든다 (음식마다 후보와 AI가 추정한 양)
		List<CandidateItem> candidates = readCandidates(analysis);
		List<ConfirmRow> confirmRows = new ArrayList<>();
		for (int i = 0; i < candidates.size(); i++) {
			CandidateItem item = candidates.get(i);
			List<Option> options = new ArrayList<>();
			for (int n = 0; n < item.candidates().size(); n++) {
				options.add(new Option(String.valueOf(n), item.candidates().get(n).name(), item.candidates().get(n).kcalPer100g()));
			}
			confirmRows.add(new ConfirmRow(i, options, item.amountG()));
		}
		return FoodAnalysisView.of(analysis, aiItems.stream().map(FoodRow::aiOnly).toList(), confirmRows);
	}

	// 음식 하나에 대해 AI의 답과 확정한 값을 견주어, 화면의 줄을 만든다.
	// "AI와 같음"의 기준: AI의 첫 답을 그대로 고르고 양도 그대로 둔 경우.
	// 양은 g 단위의 정수로 견준다: 확정 칸이 AI의 양을 정수로 보여 주므로, AI가 152.4g이라고 했어도 손대지 않고 확정하면 152g으로 온다.
	private FoodRow compare(FoodItem ai, ConfirmedItem confirmed) {
		if (!confirmed.kept()) {
			return new FoodRow(ai, null, FoodRow.REMOVED);
		}
		boolean keptFirst = ConfirmedItem.CANDIDATE.equals(confirmed.choice()) && Integer.valueOf(0).equals(confirmed.pick());
		boolean sameAmount = Math.round(ai.amountG()) == Math.round(confirmed.amountG());
		return new FoodRow(ai, confirmed.toFoodItem(), keptFirst && sameAmount ? FoodRow.SAME : FoodRow.CHANGED);
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
