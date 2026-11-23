package backend.analysis.model.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import backend.analysis.model.dto.PendingAnalysis;
import backend.analysis.model.entity.AnalysisStatus;
import backend.analysis.model.entity.FoodAnalysis;
import backend.post.model.entity.PostStatus;

public interface FoodAnalysisRepository extends JpaRepository<FoodAnalysis, Long> {

	// 메서드 이름으로 쿼리가 만들어진다: post_id가 같고, 연결된 글(post)의 상태(status)가 같은 분석 기록
	// → 삭제된 글의 분석 상태는 찾지 못하게 한다.
	Optional<FoodAnalysis> findByPostIdAndPostStatus(Long postId, PostStatus status);

	// since 이후에 AI 호출이 끝난 건수
	long countByCompletedAtGreaterThanEqual(Instant since);

	// since 이후에 "직접 적은 음식"으로 AI를 부른 건수 (JM-74). 하루 분석 횟수 상한에 함께 센다.
	long countByTypedCalledAtGreaterThanEqual(Instant since);

	// ---- 확정과 직접 적기 (JM-74, 고쳐 쓰는 AI) ----
	// 아래 네 개는 값을 읽어서 고치고 저장하는 대신, UPDATE 한 번으로 "조건이 맞을 때만" 바꾼다.
	// "확정했는지 읽고 → 쓴다"를 따로 하면 같은 요청이 동시에 둘 왔을 때 둘 다 통과할 수 있다.
	// 조건을 UPDATE의 where에 넣으면 DB가 한 번에 하나씩 처리해서 하나만 통과한다 (좋아요 수를 올리는 PostRepository의 UPDATE와 같은 이유).
	// 돌려주는 값은 바뀐 행 수다. 0이면 조건이 맞지 않아 아무것도 바뀌지 않은 것이다.
	//
	// @Modifying: 조회가 아니라 값을 바꾸는 쿼리라는 표시
	// clearAutomatically: UPDATE를 직접 날렸으므로, JPA가 기억하고 있던 예전 값을 버리고 다음에 읽을 때 DB에서 다시 읽게 한다.
	// @Transactional: 값을 바꾸는 쿼리는 트랜잭션 안에서만 실행할 수 있다. 이 메서드들을 부르는 FoodAnalysisService.confirm에는
	//   트랜잭션이 없어서(AI의 답을 기다리는 동안 DB 연결을 붙잡지 않으려고) 메서드마다 붙인다. 쿼리 하나가 트랜잭션 하나다.

	// 직접 적기 한 번을 차지한다: 아직 쓰지 않았고 아직 확정하지 않은 글일 때만 부른 시각을 적는다.
	// AI를 부르기 "전에" 실행한다 → 같은 요청이 동시에 둘 와도 AI는 한 번만 불린다.
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("update FoodAnalysis a set a.typedCalledAt = :now where a.postId = :postId and a.typedCalledAt is null and a.confirmedAt is null")
	int claimTyped(@Param("postId") Long postId, @Param("now") Instant now);

	// 차지한 한 번을 돌려준다: AI에게서 답을 받지 못했을 때 (연결 실패, AI 서버 오류). 분석이 이루어지지 않았으므로 다시 적어 볼 수 있다.
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("update FoodAnalysis a set a.typedCalledAt = null where a.postId = :postId and a.confirmedAt is null")
	int releaseTyped(@Param("postId") Long postId);

	// AI가 답을 줬을 때, 그 호출에 쓴 토큰 수와 걸린 시간을 적는다.
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("update FoodAnalysis a set a.typedInputTokens = :inputTokens, a.typedOutputTokens = :outputTokens, a.typedLatencyMs = :latencyMs where a.postId = :postId")
	int recordTypedCall(@Param("postId") Long postId, @Param("inputTokens") int inputTokens, @Param("outputTokens") int outputTokens,
			@Param("latencyMs") long latencyMs);

	// 확정한 값을 쓴다. 글마다 딱 한 번만: 아직 확정하지 않은 글(confirmedAt이 비어 있는 글)일 때만 바뀐다.
	// 이미 확정한 글이면 0이 돌아오고 값은 그대로다 → 확정한 뒤에는 바꿀 수 없다.
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("""
			update FoodAnalysis a
			set a.confirmedItems = :items, a.confirmedTotalKcal = :kcal, a.confirmedCarbsG = :carbs,
			    a.confirmedProteinG = :protein, a.confirmedFatG = :fat, a.typedMismatch = :mismatch, a.confirmedAt = :now
			where a.postId = :postId and a.confirmedAt is null
			""")
	int confirmOnce(@Param("postId") Long postId, @Param("items") String items, @Param("kcal") BigDecimal kcal,
			@Param("carbs") BigDecimal carbs, @Param("protein") BigDecimal protein, @Param("fat") BigDecimal fat,
			@Param("mismatch") boolean mismatch, @Param("now") Instant now);

	// ---- 분석 맡기와 다시 넣기 (JM-110) ----
	// 분석 줄은 app의 메모리에만 있어서, app을 끌 때(배포) 줄에서 기다리던 분석은 버려지고 그 글은 PENDING으로 남는다.
	// PendingAnalysisRequeuer가 그런 글을 찾아 다시 줄에 넣는다. 그러면 같은 글이 두 줄에 들어갈 수 있으므로
	// (무중단 배포로 app 둘이 잠깐 같이 떠 있을 때), 분석을 시작할 때 아래 UPDATE로 그 글을 "맡아서" AI는 한 번만 부른다.

	// 분석 한 번을 맡는다: 아직 PENDING이고, 아무도 맡지 않았거나 맡은 지 오래된(그 일꾼의 app이 죽었다고 보는) 글일 때만 시각을 적는다.
	// AI를 부르기 "전에" 실행한다 → 위의 claimTyped와 같은 방식. 1이면 내가 맡은 것, 0이면 다른 일꾼이 맡았거나 이미 끝난 글이다.
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("""
			update FoodAnalysis a set a.claimedAt = :now
			where a.postId = :postId and a.status = :pending and (a.claimedAt is null or a.claimedAt < :staleBefore)
			""")
	int claimAnalysis(@Param("postId") Long postId, @Param("now") Instant now, @Param("staleBefore") Instant staleBefore,
			@Param("pending") AnalysisStatus pending);

	default int claimAnalysis(Long postId, Instant now, Instant staleBefore) {
		return claimAnalysis(postId, now, staleBefore, AnalysisStatus.PENDING);
	}

	// 다시 줄에 넣을 분석: PENDING인 채로 createdBefore보다 먼저 만들어졌고, 아무도 맡지 않았거나 맡은 지 오래됐고(staleBefore보다 전),
	// 글이 지워지지 않은 것. 오래된 것부터 limit개까지. 분석에 필요한 글 번호와 사진 키를 함께 가져온다 (select new: 그 record로 바로 만든다)
	@Query("""
			select new backend.analysis.model.dto.PendingAnalysis(a.postId, p.imageKey)
			from FoodAnalysis a join a.post p
			where a.status = :pending and p.status = :active and a.createdAt < :createdBefore
			  and (a.claimedAt is null or a.claimedAt < :staleBefore)
			order by a.createdAt
			""")
	List<PendingAnalysis> findStalePending(@Param("pending") AnalysisStatus pending, @Param("active") PostStatus active,
			@Param("createdBefore") Instant createdBefore, @Param("staleBefore") Instant staleBefore, Pageable pageable);

	default List<PendingAnalysis> findStalePending(Instant createdBefore, Instant staleBefore, int limit) {
		return findStalePending(AnalysisStatus.PENDING, PostStatus.ACTIVE, createdBefore, staleBefore, PageRequest.of(0, limit));
	}
}
