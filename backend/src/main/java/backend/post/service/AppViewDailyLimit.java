package backend.post.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

// 앱 조회 기록(app_post_views)의 하루 상한 (JM-90, 기획서 19.3)
// 기록은 지우지 않고 모두 보관하고, 앱 이름표는 누구나 흉내 낼 수 있다. 그래서 스크립트가 표를 끝없이 늘리지 못하게 하루에 받는 줄 수를 막는다.
// 사용자 흐름(PageFlowRecorder)의 하루 상한과 같은 까닭, 같은 방식이다: 한국 날짜로 세고, 넘으면 그날 남은 기록은 버리고
// 1분에 한 번 경고 로그를 남긴다 (에러 알림은 울리지 않는다). 수는 서버 메모리에 있어서 서버를 다시 켜면(배포) 0부터 다시 센다
@Slf4j
@Component
public class AppViewDailyLimit {

	private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

	private final int dailyLimit;
	private final Clock clock;
	private LocalDate countedDay;
	private int countedToday;
	// 경고 로그를 마지막으로 남긴 분 (1분에 한 줄만)
	private long lastWarnMinute = -1;

	@Autowired
	public AppViewDailyLimit(@Value("${app.app-view.daily-limit:5000}") int dailyLimit) {
		this(dailyLimit, Clock.system(KOREA));
	}

	// 상한과 시계를 바꿔 보는 테스트도 이 생성자를 쓴다
	AppViewDailyLimit(int dailyLimit, Clock clock) {
		this.dailyLimit = dailyLimit;
		this.clock = clock;
	}

	// 오늘 받은 기록 수를 하나 올리고 true. 오늘 상한을 다 썼으면 올리지 않고 false (1분에 한 번 경고 로그)
	// 한국 날짜가 바뀌면 0부터 다시 센다
	public synchronized boolean tryCount() {
		LocalDate today = LocalDate.now(clock);
		if (!today.equals(countedDay)) {
			countedDay = today;
			countedToday = 0;
		}
		if (countedToday >= dailyLimit) {
			long minute = clock.millis() / 60_000;
			if (minute != lastWarnMinute) {
				lastWarnMinute = minute;
				log.warn("App view dropped: daily limit {} reached", dailyLimit);
			}
			return false;
		}
		countedToday++;
		return true;
	}
}
