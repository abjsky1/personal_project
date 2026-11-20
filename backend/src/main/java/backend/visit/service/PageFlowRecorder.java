package backend.visit.service;

import java.sql.Types;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import backend.visit.model.entity.PageView;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

// 사용자 흐름(JM-92)의 한 줄을 저장한다. 화면 응답을 기다리게 하지 않는 것이 핵심이다 (숫자: docs/performance.md 8절)
//
// ① 화면 밖에서 저장: 인터셉터는 화면을 다 그린 뒤에 부르지만, 응답의 끝은 그 뒤에 나간다 → 그 자리에서 저장하면 사람은 그만큼 더 기다린다.
//    그래서 줄(queue)에 넣기만 하고 바로 돌아가고, 저장은 이 클래스의 일꾼 스레드 하나가 차례로 한다.
//    일꾼을 Spring의 실행기(Executor) 빈으로 두지 않는다: 빈이 하나 더 생기면 Spring Boot가 AI 분석용 실행기
//    (spring.task.execution, 2명, 줄 50)를 만들지 않아 분석이 엉뚱한 실행기에서 돌 수 있다.
// ② INSERT 한 문장: JPA(save)로 저장하면 트랜잭션을 열고 닫느라 문장이 네 번 간다 (SET autocommit=0, INSERT, COMMIT,
//    SET autocommit=1. 2026-11-20 로컬 MySQL의 general log로 확인). JdbcTemplate으로 한 문장만 보낸다 (autocommit).
//    JdbcTemplate: Spring이 만들어 두는 SQL 실행 도구 (DB 연결은 HikariCP 풀에서 빌려 쓰고 바로 돌려준다).
//    테이블 모양은 PageView 엔티티가 정한다 (ddl-auto=update가 만든다. DailyRegionCount와 같은 방식)
// ③ 하루 상한: 줄은 지우지 않고 모두 보관하므로, 쿠키를 안 쓰는 스크립트가 끝없이 줄을 늘리지 못하게 하루에 받는 줄 수를 막는다
@Slf4j
@Service
public class PageFlowRecorder {

	// 저장을 기다리는 줄의 최대 개수. 지금 사람의 화면은 하루 400번쯤이라 DB가 잠깐 멈춰도 충분히 버틴다.
	// 가득 차면 새 줄은 버린다 (화면을 기다리게 하지도, 메모리를 끝없이 쓰지도 않는다)
	static final int QUEUE_SIZE = 1000;

	private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

	private static final String INSERT = "INSERT INTO page_views (visit_key, seq, viewed_at, screen, target, prev_screen, device, "
			+ "app, ref_host, utm_source, utm_medium, utm_campaign, click_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

	// 칸마다의 SQL 타입. 빈 값(null)을 보낼 때도 드라이버에 타입을 물어보지 않게 미리 정해 둔다
	private static final int[] TYPES = { Types.BIGINT, Types.INTEGER, Types.TIMESTAMP, Types.VARCHAR, Types.BIGINT, Types.VARCHAR,
			Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR };

	private final JdbcTemplate jdbcTemplate;

	// 저장을 맡는 일꾼 스레드 하나와 그 줄. null이면 부른 스레드에서 바로 저장한다
	// (app.page-flow.async=false: 테스트가 저장한 줄을 바로 읽고, 테스트의 트랜잭션과 함께 되돌린다)
	private final ThreadPoolExecutor writer;

	// 하루(한국 날짜)에 받는 줄의 상한과 오늘 받은 수
	private final int dailyLimit;
	private final Clock clock;
	private LocalDate countedDay;
	private int countedToday;

	// 서버를 끌 때 남은 줄을 마저 저장하며 기다리는 시간
	private final long drainMillis;

	// 경고 로그는 1분에 한 줄만 남기고, 그 사이에 건너뛴 수를 다음 줄에 붙인다 (DB가 멈추면 화면마다 한 줄씩 쏟아지지 않게)
	private final AtomicLong lastWarnMinute = new AtomicLong(-1);
	private final AtomicInteger skippedWarns = new AtomicInteger();

	@Autowired
	public PageFlowRecorder(JdbcTemplate jdbcTemplate, @Value("${app.page-flow.async:true}") boolean async,
			@Value("${app.page-flow.daily-limit:20000}") int dailyLimit) {
		this(jdbcTemplate, async ? QUEUE_SIZE : 0, dailyLimit, Clock.system(KOREA), 5000);
	}

	// queueSize가 0이면 일꾼 없이 바로 저장한다. 줄 크기, 상한, 시계, 기다리는 시간을 바꿔 보는 테스트도 이 생성자를 쓴다
	PageFlowRecorder(JdbcTemplate jdbcTemplate, int queueSize, int dailyLimit, Clock clock, long drainMillis) {
		this.jdbcTemplate = jdbcTemplate;
		this.dailyLimit = dailyLimit;
		this.clock = clock;
		this.drainMillis = drainMillis;
		this.writer = queueSize <= 0 ? null
				: new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueSize), task -> {
					Thread thread = new Thread(task, "page-flow-writer");
					thread.setDaemon(true);
					return thread;
				});
	}

	// 줄에 넣고 바로 돌아간다. 오늘의 상한을 넘었거나 줄이 가득 차서 못 넣으면 이 줄은 버리고 경고만 남긴다
	public void record(PageView view) {
		if (!countToday()) {
			warn("Page view dropped: daily limit {}", dailyLimit);
			return;
		}
		if (writer == null) {
			insert(view);
			return;
		}
		// 일꾼 스레드의 로그에도 이 요청의 [traceId]가 찍히게 넘겨준다 (AsyncConfig와 같은 이유)
		Map<String, String> context = MDC.getCopyOfContextMap();
		try {
			writer.execute(() -> {
				if (context != null) {
					MDC.setContextMap(context);
				}
				try {
					insert(view);
				} finally {
					MDC.clear();
				}
			});
		} catch (RejectedExecutionException e) {
			warn(writer.isShutdown() ? "Page view dropped: {}, shutting down" : "Page view dropped: {}, queue full", view.getScreen());
		}
	}

	// 서버를 끌 때(배포) 줄에 남은 것을 5초까지 마저 저장한다. Spring은 이 빈을 DB 연결보다 먼저 정리해서 그때도 저장할 수 있다.
	// 5초 안에 못 끝내면 남은 일을 거두고(일꾼도 멈춘다) 몇 줄을 못 남겼는지 한 번만 적는다
	@PreDestroy
	void stop() throws InterruptedException {
		if (writer == null) {
			return;
		}
		writer.shutdown();
		if (!writer.awaitTermination(drainMillis, TimeUnit.MILLISECONDS)) {
			int left = writer.shutdownNow().size();
			log.warn("Page views not saved at shutdown: {}", left);
		}
	}

	// 오늘 받은 줄 수를 하나 올리고, 상한 안이면 true. 한국 날짜가 바뀌면 0부터 다시 센다
	private synchronized boolean countToday() {
		LocalDate today = LocalDate.now(clock);
		if (!today.equals(countedDay)) {
			countedDay = today;
			countedToday = 0;
		}
		countedToday++;
		return countedToday <= dailyLimit;
	}

	// 저장하지 못해도 예외를 밖으로 던지지 않고 경고 로그만 남긴다 (방문 수의 VisitCounter와 같다).
	// 에러 알림(ERROR 로그)은 울리지 않는다. 로그에는 화면 이름과 예외 이름만 남긴다
	private void insert(PageView view) {
		try {
			jdbcTemplate.update(INSERT, new Object[] {
					view.getVisitKey(), view.getSeq(),
					// 시각은 UTC로 둔다 (다른 테이블의 Instant 칸을 JPA가 저장하는 것과 같다, 기획서 9.3절)
					LocalDateTime.ofInstant(view.getViewedAt(), ZoneOffset.UTC),
					view.getScreen().name(), view.getTarget(), nameOf(view.getPrevScreen()), view.getDevice().name(),
					nameOf(view.getApp()), view.getRefHost(), view.getUtmSource(), view.getUtmMedium(), view.getUtmCampaign(),
					view.getClickId() }, TYPES);
		} catch (RuntimeException e) {
			warn("Page view record failed: {}, " + e.getClass().getSimpleName(), view.getScreen());
		}
	}

	// 1분에 한 줄만. 건너뛴 경고가 있었으면 그 수를 붙인다
	private void warn(String message, Object value) {
		long minute = clock.millis() / 60_000;
		long last = lastWarnMinute.get();
		if (minute != last && lastWarnMinute.compareAndSet(last, minute)) {
			int skipped = skippedWarns.getAndSet(0);
			if (skipped > 0) {
				log.warn(message + " (and {} more page flow warnings before this)", value, skipped);
			} else {
				log.warn(message, value);
			}
		} else {
			skippedWarns.incrementAndGet();
		}
	}

	private static String nameOf(Enum<?> value) {
		return value == null ? null : value.name();
	}
}
