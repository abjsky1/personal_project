// 위치 도메인의 VO(값 객체)를 두는 패키지
package backend.location.model.vo;

import java.math.BigDecimal;
import java.math.RoundingMode;

// VO(값 객체): 위도·경도 한 쌍 = "지구 위의 한 지점"
// 1. 만들 때 스스로 검사한다 → 범위를 벗어난 좌표는 아예 만들어질 수 없다.
// 2. 값이 같으면 같은 지점이다 → record가 equals()를 값 전체로 비교해 준다.
// 3. 바뀌지 않는다 → record의 필드는 final이라 만든 뒤에 값을 바꿀 수 없다.
//
// BigDecimal: 소수를 정확하게 다루는 타입. double은 0.1 같은 값을 정확히 저장하지 못해서, DB의 DECIMAL 컬럼과 맞출 때 쓴다.
public record GeoPoint(BigDecimal latitude, BigDecimal longitude) {

	// 소수점 아래 6자리까지 저장한다 (약 10cm 단위, DB 컬럼 DECIMAL(9,6)과 같음)
	private static final int SCALE = 6;

	private static final BigDecimal MAX_LATITUDE = BigDecimal.valueOf(90);
	private static final BigDecimal MAX_LONGITUDE = BigDecimal.valueOf(180);

	// compact 생성자: record의 생성자에 검사·정리 코드를 넣는 문법. 괄호 안 매개변수를 다시 적지 않는다.
	// 여기서 값을 바꾸면(latitude = ...) 바뀐 값이 필드에 들어간다.
	public GeoPoint {
		if (latitude == null || longitude == null) {
			throw new IllegalArgumentException("위도·경도가 비어 있습니다.");
		}
		// 위도는 -90 ~ 90 (남극 ~ 북극), 경도는 -180 ~ 180
		// compareTo: BigDecimal끼리 크기를 비교한다 (작으면 음수, 같으면 0, 크면 양수)
		if (latitude.abs().compareTo(MAX_LATITUDE) > 0) {
			throw new IllegalArgumentException("위도 범위를 벗어났습니다: " + latitude);
		}
		if (longitude.abs().compareTo(MAX_LONGITUDE) > 0) {
			throw new IllegalArgumentException("경도 범위를 벗어났습니다: " + longitude);
		}
		// 소수점 6자리로 맞춘다 (7번째 자리에서 반올림)
		latitude = latitude.setScale(SCALE, RoundingMode.HALF_UP);
		longitude = longitude.setScale(SCALE, RoundingMode.HALF_UP);
	}

	// 사진에서 읽은 double 값으로 GeoPoint를 만든다.
	// BigDecimal.valueOf(double): double을 사람이 읽는 십진수 그대로 BigDecimal로 바꾼다 (new BigDecimal(double)보다 정확)
	public static GeoPoint of(double latitude, double longitude) {
		return new GeoPoint(BigDecimal.valueOf(latitude), BigDecimal.valueOf(longitude));
	}
}
