package backend.analysis.model.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import backend.analysis.model.entity.PostTag;

// 글의 음식 태그 (post_tags, JM-83). 바꿔 쓰기는 PostTagService.replace가 한 트랜잭션으로 한다
public interface PostTagRepository extends JpaRepository<PostTag, PostTag.Key> {

	// 한 글의 태그 전부 → SELECT ... FROM post_tags WHERE post_id = ? ORDER BY tag
	List<PostTag> findByPostIdOrderByTag(Long postId);

	// 한 글의 태그를 모두 지운다 (다시 채우기 전에). 엔티티를 하나씩 읽어 지우지 않고 DELETE 한 번으로
	// flushAutomatically: 앞에서 바꾼 것이 있으면 먼저 DB로 보낸 뒤 지운다 (FoodAnalysisRepository.markTagged와 같은 이유)
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from PostTag t where t.postId = :postId")
	int deleteByPostId(@Param("postId") Long postId);
}
