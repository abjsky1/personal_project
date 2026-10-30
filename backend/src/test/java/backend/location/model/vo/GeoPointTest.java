package backend.location.model.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

// GeoPoint(값 객체)의 세 가지 성질을 확인하는 테스트: 스스로 검사, 값으로 비교, 정해진 자릿수
class GeoPointTest {

	@Test
	void 소수점_6자리로_맞춘다() {
		GeoPoint point = GeoPoint.of(37.56653519, 126.97796912);

		// 7번째 자리에서 반올림
		assertThat(point.latitude()).isEqualByComparingTo("37.566535");
		assertThat(point.longitude()).isEqualByComparingTo("126.977969");
	}

	@Test
	void 범위를_벗어난_좌표는_만들_수_없다() {
		// 위도는 -90 ~ 90, 경도는 -180 ~ 180
		assertThatThrownBy(() -> GeoPoint.of(91, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> GeoPoint.of(0, -181)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 값이_같으면_같은_지점이다() {
		// 서로 다른 방법으로 만든 객체 두 개지만, 값이 같으므로 equals가 true다. (record가 값 전체로 비교한다)
		GeoPoint fromDouble = GeoPoint.of(37.5, 127.0);
		GeoPoint fromDecimal = new GeoPoint(new BigDecimal("37.500000"), new BigDecimal("127"));

		assertThat(fromDouble).isEqualTo(fromDecimal);
	}
}
