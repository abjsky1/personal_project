// 방문 통계(JM-86)의 엔티티를 두는 패키지
package backend.visit.model.entity;

import java.io.Serializable;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 시/도별 하루 합계 (daily_region_counts 테이블, JM-86). 날짜, 종류(방문, 글 올리기), 시/도마다 한 줄에 횟수만 둔다.
// IP, 세션 번호, 글 번호처럼 사람이나 요청을 알아볼 수 있는 값은 두지 않는다.
// 숫자는 DailyRegionCountRepository.increment가 SQL 한 문장으로 올린다 (이 클래스는 테이블 모양을 정하는 데만 쓴다)
@Entity
@Table(name = "daily_region_counts")
@IdClass(DailyRegionCount.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyRegionCount {

	// 한국 날짜
	@Id
	@Column(name = "stat_day")
	private LocalDate statDay;

	@Id
	@Enumerated(EnumType.STRING)
	@Column(length = 10)
	private VisitMetric metric;

	// 시/도 이름, 해외, 알 수 없음 (Sido)
	@Id
	@Column(length = 20)
	private String region;

	@Column(name = "hit_count", nullable = false)
	private long hitCount;

	// 기본 키가 칸 세 개(날짜, 종류, 시/도)라서 키를 담는 클래스를 따로 둔다. JPA가 기본 생성자와 equals, hashCode를 요구한다
	@Getter
	@NoArgsConstructor
	@AllArgsConstructor
	@EqualsAndHashCode
	public static class Key implements Serializable {

		private LocalDate statDay;
		private VisitMetric metric;
		private String region;
	}
}
