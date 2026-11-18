package backend.visit.model.repository;

import java.time.LocalDate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import backend.visit.model.entity.DailyRegionCount;

public interface DailyRegionCountRepository extends JpaRepository<DailyRegionCount, DailyRegionCount.Key> {

	// 그날 그 시/도의 횟수를 1 올린다. 줄이 없으면 1로 만든다 (MySQL의 INSERT ... ON DUPLICATE KEY UPDATE).
	// SQL 한 문장이라 두 사람이 동시에 와도 숫자를 잃지 않는다.
	// @Transactional: 이 저장만의 트랜잭션. 실패해도 부른 쪽(VisitCounter)이 받아서 화면에는 영향이 없다
	@Transactional
	@Modifying
	@Query(value = "INSERT INTO daily_region_counts (stat_day, metric, region, hit_count) VALUES (:day, :metric, :region, 1) "
			+ "ON DUPLICATE KEY UPDATE hit_count = hit_count + 1", nativeQuery = true)
	int increment(@Param("day") LocalDate day, @Param("metric") String metric, @Param("region") String region);
}
