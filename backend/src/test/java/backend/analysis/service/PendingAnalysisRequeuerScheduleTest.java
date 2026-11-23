package backend.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.util.ReflectionTestUtils;

// 다시 넣기(JM-110)가 application.properties의 값으로 일정에 올라가는지 확인하는 테스트.
// 다른 테스트에서는 꺼 두므로(test 설정의 app.analysis.requeue.enabled=false) 여기서만 켠다.
// 설정 이름에 오타가 있으면 운영 서버에서 켜질 때 처음 드러나므로, 여기서 미리 잡는다.
// 처음 실행은 켜진 지 2분 뒤라서, 이 테스트가 끝나기 전에 실제로 돌지는 않는다
@SpringBootTest(properties = "app.analysis.requeue.enabled=true")
class PendingAnalysisRequeuerScheduleTest {

	@Autowired
	private PendingAnalysisRequeuer requeuer;

	// @Scheduled를 처리하는 Spring 부품이 이 모양으로 등록한 일정을 보여 준다
	@Autowired
	private ScheduledTaskHolder scheduledTaskHolder;

	@Test
	void 켜면_2분_뒤부터_5분마다_도는_일정으로_올라가고_설정_값을_읽는다() {
		FixedDelayTask task = scheduledTaskHolder.getScheduledTasks().stream()
				.map(ScheduledTask::getTask)
				.filter(FixedDelayTask.class::isInstance)
				.map(FixedDelayTask.class::cast)
				// Spring 7은 일정 작업을 결과를 기록하는 껍데기로 한 번 감싼다. 감싼 것의 이름(toString)은 원래 메서드 이름이다
				.filter(t -> t.getRunnable().toString().equals(PendingAnalysisRequeuer.class.getName() + ".requeue"))
				.findFirst()
				.orElseThrow();

		assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofMinutes(2));
		assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofMinutes(5));
		assertThat(ReflectionTestUtils.getField(requeuer, "minAge")).isEqualTo(Duration.ofMinutes(3));
		assertThat(ReflectionTestUtils.getField(requeuer, "maxAge")).isEqualTo(Duration.ofDays(1));
		assertThat(ReflectionTestUtils.getField(requeuer, "batchSize")).isEqualTo(10);
	}
}
