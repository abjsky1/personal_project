package backend.analysis.model.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import backend.analysis.model.entity.FoodAnalysis;
import backend.post.model.entity.PostStatus;

public interface FoodAnalysisRepository extends JpaRepository<FoodAnalysis, Long> {

	// 메서드 이름으로 쿼리가 만들어진다: post_id가 같고, 연결된 글(post)의 상태(status)가 같은 분석 기록
	// → 삭제된 글의 분석 상태는 찾지 못하게 한다.
	Optional<FoodAnalysis> findByPostIdAndPostStatus(Long postId, PostStatus status);
}
