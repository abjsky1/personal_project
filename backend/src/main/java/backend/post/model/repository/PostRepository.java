package backend.post.model.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import backend.analysis.model.entity.AnalysisStatus;
import backend.post.model.entity.Post;
import backend.post.model.entity.PostStatus;

// 게시글을 DB에서 읽고 쓰는 Repository
// JpaRepository<엔티티, 기본 키 타입>를 상속하면 save(), findById() 같은 기본 메서드를 Spring이 자동으로 만들어 준다.
// interface만 선언하면 실제 구현은 Spring이 실행할 때 만들어 넣는다.
public interface PostRepository extends JpaRepository<Post, Long> {

	// 메서드 이름을 규칙대로 지으면 Spring Data JPA가 이름을 읽고 SQL을 만들어 준다.
	// findBy Status + Pageable → SELECT ... FROM posts WHERE status = ? ORDER BY ? LIMIT ? OFFSET ?
	//
	// 목록 쪽 번호 방식 (JM-57): Pageable에 "몇 쪽, 한 쪽에 몇 개, 정렬"을 담아 넘기면 그 쪽만 가져온다.
	// Page로 받으면 전체 글 수(COUNT 쿼리 한 번 더)도 같이 와서 전체 쪽 수를 알 수 있다.
	// 처음엔 "더 보기" 커서 방식(id < 마지막 번호)이었는데, 화면에 "이전"이 없어 앞 쪽으로 돌아가기 어렵다는 의견으로 바꿨다.
	Page<Post> findByStatus(PostStatus status, Pageable pageable);

	// ---- 검색 (JM-52): 닉네임이나 음식 이름에 검색어가 들어 있는 글 ----
	// 피드백 6번과 11번의 요청. 메모는 찾지 않는다 (운영자 결정, 11-23).
	//
	// 음식 이름은 posts가 아니라 food_analyses에 JSON 글자로 있다: items(AI의 첫 답)와 confirmed_items(글쓴이가 확정한 값, JM-74).
	//   [{"name":"라면","amountG":500.0,"kcal":...}, ...]
	// 그래서 JPQL이 아니라 MySQL의 SQL을 직접 쓴다 (nativeQuery). JSON_TABLE이 JSON 배열을 "음식 하나 = 한 줄"의 표로 펼쳐 주고,
	// 그 표의 name 칸에서 찾는다. 글자 전체에 LIKE를 걸면 "kcal", "TYPED" 같은 칸 이름과 값까지 걸리므로 이름 칸만 본다.
	// - JSON_MERGE_PRESERVE: 두 배열을 하나로 잇는다 (AI의 이름과 확정한 이름을 한 번에 본다)
	// - CASE WHEN JSON_VALID: 값이 없거나(분석 중, 실패, 확정 전) 깨진 글자면 빈 배열로 본다. 깨진 글자를 JSON_TABLE에 넣으면 쿼리 전체가 실패한다.
	//   다만 100단보다 깊게 겹친 JSON은 JSON_VALID 자체가 오류를 낸다. 앱은 평평한 배열만 쓰므로(Jackson이 쓴 List) DB를 손으로 고칠 때만 조심하면 된다
	// - REPLACE(..., ' ', ''): 띄어쓰기는 보지 않는다. AI는 "치즈 떡볶이"처럼 띄어 쓰기도, "치즈떡볶이"처럼 붙여 쓰기도 해서
	//   이름과 검색어 양쪽에서 빈칸을 빼고 견준다 (검색어 쪽은 PostService.likePattern이 뺀다. JM-52 PR 전 검토)
	// - CHARACTER SET, COLLATE: 펼친 칸의 글자 규칙을 직접 정한다. 적지 않으면 접속의 규칙을 따라서(로컬 mysql 명령은 latin1) 한글이 깨질 수 있다.
	//   utf8mb4_0900_ai_ci는 posts.nickname과 같은 규칙이라 대소문자를 가리지 않는다 (pizza로 Pizza를 찾는다)
	// - ESCAPE '!': 검색어의 %와 _는 "아무 글자"가 아니라 그 글자 자체로 찾는다. PostService.likePattern이 앞에 !를 붙여 둔다
	// 글이 수백 개인 지금은 글마다 JSON을 펼쳐도 빠르다. 숫자는 docs/performance.md, 글이 많아지면 음식 이름을 따로 칸에 두는 쪽으로 (기획서 18절)
	// 검색어 조건만 따로 둔다: 필터와 함께 찾을 때(PostFilterQuery, JM-83)도 같은 조건을 쓴다. 글은 p, 검색어는 :pattern
	String SEARCH_MATCH = """
			(replace(p.nickname, ' ', '') like :pattern escape '!'
			       or exists (
			         select 1
			         from food_analyses a,
			              json_table(
			                json_merge_preserve(
			                  case when json_valid(a.items) then a.items else '[]' end,
			                  case when json_valid(a.confirmed_items) then a.confirmed_items else '[]' end),
			                '$[*]' columns (name varchar(500) character set utf8mb4 collate utf8mb4_0900_ai_ci path '$.name')) f
			         where a.post_id = p.id and replace(f.name, ' ', '') like :pattern escape '!'))
			""";

	String SEARCH_WHERE = "from posts p where p.status = :status and " + SEARCH_MATCH;

	// 최신 글(번호 큰 순)부터 한 쪽. countQuery: 전체 쪽 수를 알려고 같은 조건으로 한 번 더 센다 (피드의 findByStatus와 같다)
	// 정렬은 SQL에 직접 적는다 (네이티브 쿼리에 Pageable의 정렬을 맡기지 않는다). 넘기는 Pageable에는 쪽 번호와 크기만 담는다
	@Query(value = "select p.* " + SEARCH_WHERE + " order by p.id desc",
			countQuery = "select count(*) " + SEARCH_WHERE,
			nativeQuery = true)
	Page<Post> search(@Param("status") String status, @Param("pattern") String pattern, Pageable pageable);

	// 어느 시각 이후에 올라온 글 수 (상태와 상관없이, 지운 글도 센다) → SELECT COUNT(*) FROM posts WHERE created_at >= ?
	// 업로드 하루 전체 상한에 쓴다 (UploadLimiter, JM-79). DB에서 세므로 서버를 다시 켜도 0으로 돌아가지 않는다
	long countByCreatedAtGreaterThanEqual(Instant since);

	// 번호와 상태가 모두 맞는 글 하나 → SELECT ... FROM posts WHERE id = ? AND status = ?
	// Optional: 결과가 없을 수도 있다는 표시. 없으면 비어 있는 Optional이 돌아온다 (null 대신)
	// 삭제된 글을 실수로 보여 주지 않도록, 상세, 삭제는 항상 이 메서드로 ACTIVE인 글만 찾는다.
	Optional<Post> findByIdAndStatus(Long id, PostStatus status);

	// 사진을 가린 글 (JM-80): 지우지 않은 글 중 AI가 음식이 아니라고 봤고 운영자가 아직 올리지 않은 글, 최신순.
	// 기준은 Post.isPhotoHeld()와 같다. 분석 기록은 글과 번호가 같아서(FoodAnalysis의 postId) 번호로 잇는다
	@Query("select p from Post p, FoodAnalysis a where a.postId = p.id and p.status = :status"
			+ " and a.status = :analysisStatus and p.photoApprovedAt is null order by p.id desc")
	List<Post> findPhotoHeld(@Param("status") PostStatus status, @Param("analysisStatus") AnalysisStatus analysisStatus);

	// 조회수 1 올리기 (JM-72) → UPDATE posts SET view_count = view_count + 1 WHERE id = ? AND status = ?
	// 글을 읽어서 +1 한 뒤 저장하면, 두 사람이 동시에 열었을 때 둘 다 같은 숫자를 읽고 같은 값으로 저장해서 하나를 잃는다.
	// UPDATE 한 번으로 DB가 직접 더하게 하면 잃지 않는다. (공지 조회수 NoticeViewRepository와 같은 방식)
	// @Modifying: 조회가 아니라 값을 바꾸는 쿼리라는 표시. 돌려주는 값은 바뀐 행 수 (글이 없거나 삭제됐으면 0)
	// clearAutomatically: 실행 뒤 JPA가 기억하고 있던 글을 잊게 한다 → 바로 다시 읽으면 올라간 숫자가 나온다
	@Modifying(clearAutomatically = true)
	@Query("update Post p set p.viewCount = p.viewCount + 1 where p.id = :id and p.status = :status")
	int incrementViewCount(@Param("id") Long id, @Param("status") PostStatus status);

	// 좋아요 1 올리기 (JM-59). 조회수와 같은 방식
	@Modifying(clearAutomatically = true)
	@Query("update Post p set p.likeCount = p.likeCount + 1 where p.id = :id and p.status = :status")
	int incrementLikeCount(@Param("id") Long id, @Param("status") PostStatus status);
}
