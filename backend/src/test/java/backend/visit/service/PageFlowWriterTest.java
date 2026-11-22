package backend.visit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import backend.visit.model.entity.ClientApp;
import backend.visit.model.entity.Device;
import backend.visit.model.entity.PageView;
import backend.visit.model.entity.Screen;
import backend.visit.model.vo.VisitEntry;

// 운영과 같은 길로 저장하는지 (JM-92): 일꾼 스레드에서, 트랜잭션 없이(autocommit), 실제 MySQL에.
// 다른 테스트는 app.page-flow.async=false로 요청 스레드에서 저장해 테스트의 트랜잭션과 함께 되돌리므로 이 길을 지나지 않는다.
// 예: HikariCP의 auto-commit을 끄면 트랜잭션 밖의 INSERT는 커밋되지 않고 사라지는데, 그때 이 테스트가 실패한다.
// @Transactional을 붙이지 않아 줄이 실제로 커밋된다. 끝나면 이 테스트의 방문 번호로 지운다
@SpringBootTest
class PageFlowWriterTest {

	private final long visitKey = new SecureRandom().nextLong() & Long.MAX_VALUE;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM page_views WHERE visit_key = ?", visitKey);
	}

	@Test
	void 일꾼_스레드가_트랜잭션_없이_저장해도_줄이_남는다() throws Exception {
		PageFlowRecorder recorder = new PageFlowRecorder(jdbcTemplate, 10, 1000, Clock.system(ZoneId.of("Asia/Seoul")), 5000);
		VisitEntry entry = new VisitEntry(ClientApp.KAKAOTALK, "www.google.com", "instagram", "bio", "writer-test", "fbclid");

		recorder.record(new PageView(visitKey, 1, Screen.FEED, 1L, null, Device.MOBILE, entry));
		recorder.record(new PageView(visitKey, 2, Screen.POST, 7L, Screen.FEED, Device.MOBILE, null));
		recorder.stop();

		List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM page_views WHERE visit_key = ? ORDER BY seq", visitKey);
		assertThat(rows).hasSize(2);
		assertThat(rows.get(0)).containsEntry("screen", "FEED").containsEntry("app", "KAKAOTALK").containsEntry("ref_host", "www.google.com")
				.containsEntry("utm_source", "instagram").containsEntry("utm_medium", "bio").containsEntry("utm_campaign", "writer-test")
				.containsEntry("click_id", "fbclid");
		assertThat(rows.get(1)).containsEntry("seq", 2).containsEntry("screen", "POST").containsEntry("target", 7L)
				.containsEntry("prev_screen", "FEED");
		Long secondsAgo = jdbcTemplate.queryForObject("SELECT MAX(ABS(TIMESTAMPDIFF(SECOND, viewed_at, UTC_TIMESTAMP(6)))) "
				+ "FROM page_views WHERE visit_key = ?", Long.class, visitKey);
		assertThat(secondsAgo).isLessThan(120L);
	}
}
