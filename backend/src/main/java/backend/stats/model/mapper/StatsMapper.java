// 운영자 통계를 읽는 MyBatis 매퍼를 두는 패키지
package backend.stats.model.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import backend.stats.model.dto.AnalysisCounts;
import backend.stats.model.dto.DailyActivity;
import backend.stats.model.dto.FeedbackCounts;
import backend.stats.model.dto.LocationCounts;

// 운영자 통계 조회 (JM-68). 실제 SQL은 resources/mapper/StatsMapper.xml에 있다.
// JPA Repository는 메서드 이름에서 SQL을 만들어 주고, MyBatis는 사람이 쓴 SQL의 결과를 객체에 담아 준다.
// 여러 표를 합치거나 백분위를 구하는 집계는 SQL로 직접 쓰는 쪽이 쉬워서 여기에만 쓴다. 저장과 일반 조회는 JPA 그대로다. (기획서 18절)
// @Mapper: 이 인터페이스의 구현체를 MyBatis가 실행 중에 만들어 준다.
// from: 이 시각 이후의 기록만 센다. DB의 시각은 UTC로 저장되어 있어서 UTC 기준 값을 넘긴다.
@Mapper
public interface StatsMapper {

	// 날짜별 글, 댓글, 피드백 수. 기록이 있는 날만 나온다.
	List<DailyActivity> findDailyActivity(@Param("from") LocalDateTime from);

	AnalysisCounts findAnalysisCounts(@Param("from") LocalDateTime from);

	// AI 응답 시간의 95번째 백분위 (ms). 기록이 없으면 null
	Long findLatencyP95(@Param("from") LocalDateTime from);

	LocationCounts findLocationCounts(@Param("from") LocalDateTime from);

	// 지금 남아 있는 피드백의 처리 상태별 수 (기간과 상관없이 전체)
	FeedbackCounts findFeedbackCounts();
}
