// 운영자 통계를 만드는 서비스를 두는 패키지
package backend.stats.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import backend.analysis.service.FoodAnalysisService;
import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;
import backend.stats.model.dto.AdminStatsResponse;
import backend.stats.model.dto.AdminStatsResponse.AnalysisStats;
import backend.stats.model.dto.AdminStatsResponse.LocationStats;
import backend.stats.model.dto.AdminStatsResponse.Totals;
import backend.stats.model.dto.DailyActivity;
import backend.stats.model.mapper.StatsMapper;
import lombok.RequiredArgsConstructor;

// 운영자 통계 (JM-68, 기획서 17절 성공 지표)
@Service
@RequiredArgsConstructor
public class StatsService {

	private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
	private static final int MAX_DAYS = 90;

	private final StatsMapper statsMapper;
	private final FoodAnalysisService foodAnalysisService;

	// 오늘을 포함한 최근 days일의 통계. 여러 쿼리가 같은 시점의 데이터를 보도록 트랜잭션 하나로 묶는다.
	@Transactional(readOnly = true)
	public AdminStatsResponse getStats(int days) {
		if (days < 1 || days > MAX_DAYS) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}

		LocalDate today = LocalDate.now(KOREA);
		LocalDate firstDay = today.minusDays(days - 1L);
		LocalDateTime from = startInUtc(firstDay);

		List<DailyActivity> daily = fillEmptyDays(statsMapper.findDailyActivity(from), firstDay, today);
		AnalysisStats analysis = AnalysisStats.of(statsMapper.findAnalysisCounts(from), statsMapper.findLatencyP95(from),
				foodAnalysisService.countToday(), foodAnalysisService.getDailyLimit());

		return new AdminStatsResponse(days, firstDay, today, daily, Totals.of(daily), analysis,
				LocationStats.of(statsMapper.findLocationCounts(from)), statsMapper.findFeedbackCounts());
	}

	// 한국 날짜의 자정을 DB가 저장하는 UTC 시각으로 바꾼다. 예: 11월 5일 00:00(한국) → 11월 4일 15:00(UTC)
	private LocalDateTime startInUtc(LocalDate day) {
		return day.atStartOfDay(KOREA).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
	}

	// SQL은 기록이 있는 날만 돌려주므로, 기록이 없는 날을 0으로 채워 날짜가 빠지지 않게 한다.
	private List<DailyActivity> fillEmptyDays(List<DailyActivity> found, LocalDate firstDay, LocalDate lastDay) {
		Map<LocalDate, DailyActivity> byDay = found.stream().collect(Collectors.toMap(DailyActivity::day, Function.identity()));
		return firstDay.datesUntil(lastDay.plusDays(1))
				.map(day -> byDay.getOrDefault(day, DailyActivity.empty(day)))
				.toList();
	}
}
