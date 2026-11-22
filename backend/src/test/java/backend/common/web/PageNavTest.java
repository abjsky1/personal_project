package backend.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// 주소의 ?page= 값을 DB에서 읽을 수 있는 쪽 번호로 맞추는지 검사한다 (JM-107)
class PageNavTest {

	@Test
	void 쪽_번호는_1부터_건너뛸_글_수가_int를_넘지_않는_쪽까지다() {
		// 0이나 음수는 1쪽, 보통 번호는 그대로
		assertThat(PageNav.readablePage(0, 24)).isEqualTo(1);
		assertThat(PageNav.readablePage(-5, 24)).isEqualTo(1);
		assertThat(PageNav.readablePage(3, 24)).isEqualTo(3);

		// 아주 큰 번호(?page=2147483647)는 넘지 않는 가장 큰 쪽으로 줄인다
		int largest = PageNav.readablePage(Integer.MAX_VALUE, 24);
		assertThat(largest).isEqualTo(Integer.MAX_VALUE / 24);
		// 건너뛸 글 수 = (쪽 번호 - 1) x 24. 이것이 int를 넘으면 Spring Data JPA가 예외를 던진다 (전에는 500)
		assertThat((long) (largest - 1) * 24).isLessThanOrEqualTo(Integer.MAX_VALUE);
		// 피드백 목록(한 쪽에 20개)도 같다
		assertThat((long) (PageNav.readablePage(Integer.MAX_VALUE, 20) - 1) * 20).isLessThanOrEqualTo(Integer.MAX_VALUE);
	}
}
