package backend.analysis.model.entity;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import backend.analysis.model.dto.FoodAnalysisResult;
import backend.analysis.model.dto.FoodAnalysisResult.FoodItem;
import backend.analysis.model.dto.FoodAnalyzerResponse;
import backend.post.model.entity.Post;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

// 게시글의 AI 분석 결과 (food_analyses 테이블). 글과 1:1이고, 글을 저장할 때 PENDING 상태로 함께 만들어진다.
@Entity
@Table(name = "food_analyses")
public class FoodAnalysis {

	private static final int MAX_COMMENT_LENGTH = 500;

	// 기본 키 = 게시글 번호 (post_locations와 같은 방식)
	@Id
	private Long postId;

	@MapsId
	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "post_id")
	private Post post;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 20)
	private AnalysisStatus status;

	// 합계는 AI 값이 아니라 서버가 items를 더해서 계산한 값이다. DECIMAL(7,1): 소수점 한 자리까지
	@Column(precision = 7, scale = 1)
	private BigDecimal totalKcal;

	// 컬럼 이름을 직접 적는다. 끝 글자만 대문자이면 자동 변환이 밑줄을 넣지 않아서 carbs_g가 아니라 carbsg가 된다.
	@Column(name = "carbs_g", precision = 7, scale = 1)
	private BigDecimal carbsG;

	@Column(name = "protein_g", precision = 7, scale = 1)
	private BigDecimal proteinG;

	@Column(name = "fat_g", precision = 7, scale = 1)
	private BigDecimal fatG;

	// 음식별 상세를 JSON 글자로 저장한다. 화면에 보여 줄 때 다시 목록으로 바꾼다.
	@Column(columnDefinition = "TEXT")
	private String items;

	@Column(length = 10)
	private String confidence;

	@Column(length = MAX_COMMENT_LENGTH)
	private String comment;

	// 어떤 모델로, 토큰을 얼마나 써서, 몇 ms 걸렸는지 (비용, 성능 확인용)
	@Column(length = 50)
	private String model;

	private Integer inputTokens;

	private Integer outputTokens;

	private Long latencyMs;

	// AI를 부른 횟수 (지금은 다시 시도하지 않아서 0 또는 1이다)
	@Column(nullable = false)
	private int attemptCount;

	// 실패 이유 (예: HTTP_429). 사진이나 응답 내용은 적지 않는다.
	@Column(length = 100)
	private String errorMessage;

	@Column(nullable = false)
	private Instant createdAt;

	// AI 호출이 끝난 시각. 하루 분석 횟수를 셀 때도 쓴다. AI를 부르지 않았으면(PENDING, LIMIT_EXCEEDED) 비어 있다.
	private Instant completedAt;

	// ---- 고쳐 쓰는 AI (JM-74) ----
	// 글을 올릴 때 고른 확정 방식. @ColumnDefault: 이미 있는 글들은 컬럼을 더할 때 AUTO(처음부터 있던 방식)로 채워진다.
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@ColumnDefault("'AUTO'")
	@Column(nullable = false, length = 10)
	private ConfirmMode confirmMode;

	// AI가 낸 후보 전체 (JSON 글자). 음식마다 후보 세 개와 양. "내가 보고 확정"으로 올려서 분석이 끝난 글에만 있다.
	@Column(columnDefinition = "TEXT")
	private String candidates;

	// 사용자가 확정한 결과. 확정하기 전에는 모두 비어 있다.
	// 위의 totalKcal, items 등(AI의 첫 답)은 확정해도 덮어쓰지 않는다 → 둘을 언제든 비교할 수 있다.
	// 확정은 글마다 한 번뿐이다. 이 칸들은 FoodAnalysisRepository.confirmOnce가 UPDATE 한 번으로 채운다
	// (confirmedAt이 비어 있을 때만 쓰는 조건이 그 UPDATE 안에 있다). 그래서 이 클래스에는 확정 값을 바꾸는 메서드가 없다.
	@Column(columnDefinition = "TEXT")
	private String confirmedItems;

	@Column(precision = 7, scale = 1)
	private BigDecimal confirmedTotalKcal;

	@Column(name = "confirmed_carbs_g", precision = 7, scale = 1)
	private BigDecimal confirmedCarbsG;

	@Column(name = "confirmed_protein_g", precision = 7, scale = 1)
	private BigDecimal confirmedProteinG;

	@Column(name = "confirmed_fat_g", precision = 7, scale = 1)
	private BigDecimal confirmedFatG;

	// 확정한 시각. 값이 있으면 확정한 글이다.
	private Instant confirmedAt;

	// ---- 직접 적기 (JM-74) ----
	// 후보에 맞는 음식이 없으면 이름을 직접 적을 수 있고, 그때 AI를 한 번 더 부른다. 그 호출의 기록이다.
	// 글마다 한 번뿐이라 칸도 하나씩이다 → 글 하나에 AI의 답은 많아야 두 번이다 (처음 분석 한 번, 직접 적은 음식 계산 한 번).
	// 이 칸들도 Repository의 UPDATE(claimTyped, recordTypedCall, releaseTyped)가 채운다.

	// 직접 적은 음식으로 AI를 부른 시각. 값이 있으면 이 글은 직접 적기를 이미 쓴 것이다. 하루 분석 횟수를 셀 때도 쓴다.
	private Instant typedCalledAt;

	// 그 호출에 쓴 토큰 수와 걸린 시간 (비용, 성능 확인용)
	private Integer typedInputTokens;

	private Integer typedOutputTokens;

	private Long typedLatencyMs;

	// 확정한 음식 가운데, 직접 적은 것을 AI가 "사진과 맞지 않는다"고 본 것이 있는지. 확정하기 전에는 비어 있다.
	// 맞지 않아도 확정은 받는다 (글쓴이가 사진보다 잘 안다). 나중에 통계나 학습 데이터에서 걸러 낼 수 있게 남기는 표시다.
	private Boolean typedMismatch;

	protected FoodAnalysis() {
	}

	// 처음부터 있던 방식("AI가 바로 확정")으로 만든다
	public FoodAnalysis(Post post) {
		this(post, ConfirmMode.AUTO);
	}

	// confirmMode: 글을 올릴 때 고른 확정 방식 (JM-74)
	public FoodAnalysis(Post post, ConfirmMode confirmMode) {
		this.post = post;
		this.status = AnalysisStatus.PENDING;
		this.confirmMode = confirmMode;
		this.createdAt = Instant.now();
	}

	// 음식 분석이 끝났을 때. itemsJson: 음식별 상세를 JSON 글자로 바꾼 값
	public void markDone(FoodAnalyzerResponse response, String itemsJson, long latencyMs) {
		FoodAnalysisResult result = response.result();
		this.status = AnalysisStatus.DONE;
		this.totalKcal = result.total(FoodItem::kcal);
		this.carbsG = result.total(FoodItem::carbsG);
		this.proteinG = result.total(FoodItem::proteinG);
		this.fatG = result.total(FoodItem::fatG);
		this.items = itemsJson;
		this.confidence = result.confidence();
		this.comment = shorten(result.comment());
		finishCall(response, latencyMs);
	}

	// 분석은 끝났지만 음식 사진이 아니었을 때
	public void markNotFood(FoodAnalyzerResponse response, long latencyMs) {
		this.status = AnalysisStatus.NOT_FOOD;
		finishCall(response, latencyMs);
	}

	// 실패했을 때. reason: 실패 이유 (FoodAnalysisException의 메시지)
	public void markFailed(String reason, long latencyMs) {
		this.status = AnalysisStatus.FAILED;
		this.errorMessage = reason;
		this.attemptCount++;
		this.latencyMs = latencyMs;
		this.completedAt = Instant.now();
	}

	// 오늘 분석 횟수 상한을 넘었을 때. AI를 부르지 않았으므로 시도 횟수와 완료 시각은 그대로 둔다.
	public void markLimitExceeded() {
		this.status = AnalysisStatus.LIMIT_EXCEEDED;
	}

	// AI가 낸 후보 전체를 보관한다 ("내가 보고 확정"으로 올린 글의 분석이 끝났을 때). 사용자가 확정할 때 꺼내 쓴다.
	public void keepCandidates(String candidatesJson) {
		this.candidates = candidatesJson;
	}

	public boolean isConfirmed() {
		return confirmedAt != null;
	}

	// 직접 적기를 이미 썼는지 (글마다 한 번)
	public boolean isTypedUsed() {
		return typedCalledAt != null;
	}

	// 화면에 보여 줄 칼로리 합계: 확정했으면 확정한 값, 아니면 AI의 첫 답 (목록 카드가 쓴다)
	public BigDecimal getShownTotalKcal() {
		return isConfirmed() ? confirmedTotalKcal : totalKcal;
	}

	private void finishCall(FoodAnalyzerResponse response, long latencyMs) {
		this.model = response.model();
		this.inputTokens = response.inputTokens();
		this.outputTokens = response.outputTokens();
		this.errorMessage = null;
		this.attemptCount++;
		this.latencyMs = latencyMs;
		this.completedAt = Instant.now();
	}

	// AI가 설명을 아주 길게 써도 컬럼 길이를 넘어 저장이 실패하지 않게 자른다.
	private String shorten(String text) {
		if (text == null || text.length() <= MAX_COMMENT_LENGTH) {
			return text;
		}
		return text.substring(0, MAX_COMMENT_LENGTH);
	}

	public Long getPostId() {
		return postId;
	}

	public AnalysisStatus getStatus() {
		return status;
	}

	public BigDecimal getTotalKcal() {
		return totalKcal;
	}

	public BigDecimal getCarbsG() {
		return carbsG;
	}

	public BigDecimal getProteinG() {
		return proteinG;
	}

	public BigDecimal getFatG() {
		return fatG;
	}

	public String getItems() {
		return items;
	}

	public String getConfidence() {
		return confidence;
	}

	public String getComment() {
		return comment;
	}

	public String getModel() {
		return model;
	}

	public Integer getInputTokens() {
		return inputTokens;
	}

	public Integer getOutputTokens() {
		return outputTokens;
	}

	public Long getLatencyMs() {
		return latencyMs;
	}

	public int getAttemptCount() {
		return attemptCount;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}

	public ConfirmMode getConfirmMode() {
		return confirmMode;
	}

	public String getCandidates() {
		return candidates;
	}

	public String getConfirmedItems() {
		return confirmedItems;
	}

	public BigDecimal getConfirmedTotalKcal() {
		return confirmedTotalKcal;
	}

	public BigDecimal getConfirmedCarbsG() {
		return confirmedCarbsG;
	}

	public BigDecimal getConfirmedProteinG() {
		return confirmedProteinG;
	}

	public BigDecimal getConfirmedFatG() {
		return confirmedFatG;
	}

	public Instant getConfirmedAt() {
		return confirmedAt;
	}

	public Instant getTypedCalledAt() {
		return typedCalledAt;
	}

	public Integer getTypedInputTokens() {
		return typedInputTokens;
	}

	public Integer getTypedOutputTokens() {
		return typedOutputTokens;
	}

	public Long getTypedLatencyMs() {
		return typedLatencyMs;
	}

	public Boolean getTypedMismatch() {
		return typedMismatch;
	}
}
