// 운영자 통계를 만드는 서비스를 두는 패키지
package backend.stats.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import backend.analysis.model.dto.ConfirmedItem;
import backend.analysis.model.dto.FoodAnalysisResult.FoodItem;
import backend.analysis.service.FoodAnalysisService;
import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;
import backend.location.service.RegionLookup;
import backend.stats.model.dto.AdminStatsResponse;
import backend.stats.model.dto.AdminStatsResponse.AnalysisStats;
import backend.stats.model.dto.AdminStatsResponse.ConfirmStats;
import backend.stats.model.dto.AdminStatsResponse.LocationStats;
import backend.stats.model.dto.AdminStatsResponse.RegionStats;
import backend.stats.model.dto.AdminStatsResponse.Totals;
import backend.stats.model.dto.ConfirmedPost;
import backend.stats.model.dto.ConfirmedRow;
import backend.stats.model.dto.DailyActivity;
import backend.stats.model.mapper.StatsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

// 운영자 통계 (JM-68, 기획서 17절 성공 지표). 운영자 API와 관리자 화면의 통계(JM-75)가 같은 값을 쓴다
@Slf4j
@Service
@RequiredArgsConstructor
public class StatsService {

	private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
	private static final int MAX_DAYS = 90;

	// 고쳐 쓰는 AI(JM-74)를 배포한 시각 (UTC, 한국 시각으로 2026-11-13 10:05). 그 전에 올라온 글은 컬럼 기본값 때문에 모두
	// "AI가 바로 확정"으로 남아 있어서, 확정 통계(JM-75)는 이 시각 뒤에 올라온 글만 센다.
	// 2027-02-11부터는 가장 긴 90일 기간도 이 시각 뒤에 시작해서 효과가 없다 (그때 지워도 된다)
	static final LocalDateTime CONFIRM_RELEASED_UTC = LocalDateTime.of(2026, 11, 13, 1, 5);

	private static final TypeReference<List<FoodItem>> FOOD_ITEMS = new TypeReference<>() {
	};
	private static final TypeReference<List<ConfirmedItem>> CONFIRMED_ITEMS = new TypeReference<>() {
	};

	private final StatsMapper statsMapper;
	private final FoodAnalysisService foodAnalysisService;
	private final JsonMapper jsonMapper;
	private final RegionLookup regionLookup;

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
				LocationStats.of(statsMapper.findLocationCounts(from)), statsMapper.findFeedbackCounts(),
				confirmStats(confirmStart(from)), regionStats(from));
	}

	// 확정 통계를 셀 시작 시각 (UTC): 기간의 시작과 고쳐 쓰는 AI 배포 시각 중 늦은 쪽
	static LocalDateTime confirmStart(LocalDateTime windowStartUtc) {
		return windowStartUtc.isAfter(CONFIRM_RELEASED_UTC) ? windowStartUtc : CONFIRM_RELEASED_UTC;
	}

	// 고쳐 쓰는 AI의 확정 통계 (JM-75). 건수는 SQL이 세고, 음식마다 무엇을 했는지는 확정한 글의 JSON을 풀어 센다.
	// 읽지 못한 기록은 글 번호만 경고 로그에 남기고 skipped로 센다 (음식 이름은 로그에 남기지 않는다)
	private ConfirmStats confirmStats(LocalDateTime fromUtc) {
		List<ConfirmedPost> posts = new ArrayList<>();
		long skipped = 0;
		for (ConfirmedRow row : statsMapper.findConfirmedRows(fromUtc)) {
			try {
				posts.add(new ConfirmedPost(readList(row.items(), FOOD_ITEMS), readList(row.confirmedItems(), CONFIRMED_ITEMS),
						row.totalKcal(), row.confirmedTotalKcal()));
			} catch (RuntimeException e) {
				skipped++;
				log.warn("Confirmed analysis could not be read for stats: postId={}", row.postId());
			}
		}
		LocalDateTime sinceInKorea = fromUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(KOREA).toLocalDateTime();
		return ConfirmStats.of(sinceInKorea, statsMapper.findConfirmCounts(fromUtc), posts, skipped);
	}

	// 사진 위치로 본 지역별 글 수 (JM-85). 좌표는 여기서 행정동을 찾는 데만 쓰고, 이름과 개수만 내보낸다 (로그에도 남기지 않는다)
	private RegionStats regionStats(LocalDateTime fromUtc) {
		return RegionStats.of(statsMapper.findLocatedPoints(fromUtc).stream().map(regionLookup::find).toList());
	}

	private <T> List<T> readList(String json, TypeReference<List<T>> type) {
		if (json == null) {
			throw new IllegalStateException("empty json");
		}
		return jsonMapper.readValue(json, type);
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
