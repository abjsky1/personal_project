package backend.visit.service;

import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.stereotype.Service;

import backend.visit.model.entity.VisitMetric;
import backend.visit.model.repository.DailyRegionCountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 방문과 글 올리기를 시/도별 하루 합계에 1 더한다 (JM-86). IP는 시/도를 찾는 데만 쓰고 저장하지 않는다
@Slf4j
@Service
@RequiredArgsConstructor
public class VisitCounter {

	private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

	private final IpRegionResolver ipRegionResolver;
	private final DailyRegionCountRepository dailyRegionCountRepository;

	// 세지 못해도 화면은 그대로 나가야 해서, 예외를 밖으로 던지지 않고 경고 로그만 남긴다 (IP는 남기지 않는다)
	public void count(VisitMetric metric, String ip) {
		try {
			dailyRegionCountRepository.increment(LocalDate.now(KOREA), metric.name(), ipRegionResolver.sidoOf(ip));
		} catch (RuntimeException e) {
			log.warn("Visit count failed: metric={}, {}", metric, e.getClass().getSimpleName());
		}
	}
}
