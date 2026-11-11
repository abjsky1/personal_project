// 운영자 통계의 결과를 담는 DTO를 두는 패키지
package backend.stats.model.dto;

import java.time.LocalDate;

// 하루의 글, 댓글, 피드백 수 (한국 날짜 기준, 삭제된 것도 포함)
public record DailyActivity(LocalDate day, long posts, long comments, long feedbacks) {

	// 기록이 없는 날을 0으로 채울 때 쓴다
	public static DailyActivity empty(LocalDate day) {
		return new DailyActivity(day, 0, 0, 0);
	}
}
