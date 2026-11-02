package backend.analysis.model.entity;

import java.math.BigDecimal;
import java.time.Instant;

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

	// 어떤 모델로, 토큰을 얼마나 써서, 몇 ms 걸렸는지 (비용·성능 확인용)
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

	protected FoodAnalysis() {
	}

	public FoodAnalysis(Post post) {
		this.post = post;
		this.status = AnalysisStatus.PENDING;
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
}
