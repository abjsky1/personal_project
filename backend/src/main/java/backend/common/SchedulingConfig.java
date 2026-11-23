package backend.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling: @Scheduled가 붙은 메서드를 정해진 간격마다 부르게 켠다 (JM-110, PendingAnalysisRequeuer).
// Spring이 일정 전용 스레드 하나(scheduling-1)를 만들어 차례로 부른다. 분석 일꾼(spring.task.execution.*의 applicationTaskExecutor)과는 따로다
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
