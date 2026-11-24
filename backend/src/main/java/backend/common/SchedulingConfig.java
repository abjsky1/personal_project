package backend.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling: @Scheduled가 붙은 메서드를 정해진 간격마다 부르게 켠다 (JM-110, PendingAnalysisRequeuer).
// Spring이 일정 전용 스레드를 만들어 부른다. 2026-11-24(JM-83)부터 둘이다 (spring.task.scheduling.pool.size=2): 태그 채우기(PostTagFiller)가
// AI를 기다리는 동안에도 분석 다시 넣기가 제때 돈다. 분석 일꾼(spring.task.execution.*의 applicationTaskExecutor)과는 따로다
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
