package backend.post.model.repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import backend.analysis.model.entity.AnalysisStatus;
import backend.analysis.model.entity.FoodTag;
import backend.post.model.dto.FeedFilter;
import backend.post.model.entity.Post;
import backend.post.model.entity.PostStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

// 필터로 글을 찾는다 (JM-83). 피드의 필터와, 검색어와 필터를 함께 쓴 검색이 쓴다.
// 고른 묶음 수와 칼로리, 검색어에 따라 조건이 달라져서 @Query 대신 SQL을 여기서 이어 붙인다.
// SQL에 들어가는 값은 모두 매개변수(:g0, :kcalMin, :pattern ...)로 넘긴다. 이어 붙이는 글자는 정해 둔 조각뿐이다 (주소의 값이 SQL이 되지 않는다).
//
// 예: 한식이나 중식, 매운 것, 400 ~ 800 kcal
//   select p.* from posts p join food_analyses fa on fa.post_id = p.id
//   where p.status = 'ACTIVE'
//     and exists (select 1 from post_tags t where t.post_id = p.id and t.tag in ('KOREAN', 'CHINESE'))   ← 묶음 안은 "하나라도"
//     and exists (select 1 from post_tags t where t.post_id = p.id and t.tag in ('SPICY'))                ← 묶음끼리는 "모두"
//     and fa.status = 'DONE' and 보이는 칼로리 >= 400 and 보이는 칼로리 < 800
//   order by p.id desc limit 24
// 보이는 칼로리: 확정한 글은 확정한 합계, 아니면 AI의 합계 (목록 카드에 보이는 숫자와 같다, FoodAnalysis.getShownTotalKcal)
// 검색어가 있으면 검색과 같은 조건(PostRepository.SEARCH_MATCH)을 하나 더 건다. 그 조건 안의 분석 기록 이름이 a라서, 칼로리용은 fa로 부른다
// post_tags의 기본 키(post_id, tag)가 exists를 바로 찾는다. 피드처럼 최신 글부터 번호를 거꾸로 훑다가 24개가 차면 멈춘다
@Repository
public class PostFilterQuery {

	private static final String SHOWN_KCAL = "(case when fa.confirmed_at is not null then fa.confirmed_total_kcal else fa.total_kcal end)";

	@PersistenceContext
	private EntityManager entityManager;

	// pattern: 검색어의 LIKE 모양 (PostService.likePattern). 검색이 아니면 null
	public Page<Post> find(FeedFilter filter, String pattern, Pageable pageable) {
		Map<String, Object> params = new HashMap<>();
		String where = where(filter, pattern, params);

		Query select = entityManager.createNativeQuery("select p.*" + where + " order by p.id desc", Post.class);
		params.forEach(select::setParameter);
		select.setFirstResult((int) pageable.getOffset());
		select.setMaxResults(pageable.getPageSize());
		@SuppressWarnings("unchecked")
		List<Post> posts = select.getResultList();

		Query count = entityManager.createNativeQuery("select count(*)" + where);
		params.forEach(count::setParameter);
		long total = ((Number) count.getSingleResult()).longValue();
		return new PageImpl<>(posts, pageable, total);
	}

	private String where(FeedFilter filter, String pattern, Map<String, Object> params) {
		StringBuilder sql = new StringBuilder(" from posts p");
		if (filter.hasKcal()) {
			sql.append(" join food_analyses fa on fa.post_id = p.id");
		}
		sql.append(" where p.status = :active");
		params.put("active", PostStatus.ACTIVE.name());

		int g = 0;
		for (Set<FoodTag> tags : filter.byGroup().values()) {
			sql.append(" and exists (select 1 from post_tags t where t.post_id = p.id and t.tag in (:g").append(g).append("))");
			params.put("g" + g, tags.stream().map(FoodTag::name).toList());
			g++;
		}

		if (filter.hasKcal()) {
			sql.append(" and fa.status = :done");
			params.put("done", AnalysisStatus.DONE.name());
			if (filter.kcalMin() != null) {
				sql.append(" and ").append(SHOWN_KCAL).append(" >= :kcalMin");
				params.put("kcalMin", filter.kcalMin());
			}
			if (filter.kcalMax() != null) {
				sql.append(" and ").append(SHOWN_KCAL).append(" < :kcalMax");
				params.put("kcalMax", filter.kcalMax());
			}
		}

		if (pattern != null) {
			sql.append(" and ").append(PostRepository.SEARCH_MATCH);
			params.put("pattern", pattern);
		}
		return sql.toString();
	}
}
