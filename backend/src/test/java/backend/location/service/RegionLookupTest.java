package backend.location.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import backend.location.model.vo.GeoPoint;
import backend.location.model.vo.Region;
import tools.jackson.databind.json.JsonMapper;

// 행정동 찾기 (JM-85): 프로젝트에 담아 둔 실제 경계 파일을 읽는다. 파일이 깨지거나 빠지면 CI에서 여기서 실패한다
// 기대하는 이름은 줄이기 전의 원본 경계 파일에서 같은 방법(짝홀 규칙)으로 찾은 값이다
class RegionLookupTest {

	private final RegionLookup lookup = new RegionLookup(JsonMapper.builder().build());

	// 위도와 경도를 바꿔 읽으면 이 점은 어느 동에도 들지 않아서, 순서 실수도 여기서 잡힌다
	@Test
	void 서울시청은_서울특별시_중구_명동이다() {
		assertThat(lookup.find(GeoPoint.of(37.566535, 126.977969)))
				.contains(new Region("서울특별시", "서울특별시 중구", "서울특별시 중구 명동"));
	}

	@Test
	void 부산역과_제주도청과_정부세종청사() {
		assertThat(lookup.find(GeoPoint.of(35.115225, 129.042243)))
				.contains(new Region("부산광역시", "부산광역시 동구", "부산광역시 동구 초량3동"));
		assertThat(lookup.find(GeoPoint.of(33.488936, 126.498229)))
				.contains(new Region("제주특별자치도", "제주특별자치도 제주시", "제주특별자치도 제주시 연동"));
		assertThat(lookup.find(GeoPoint.of(36.504090, 127.265030)))
				.contains(new Region("세종특별자치시", "세종특별자치시 세종시", "세종특별자치시 세종시 어진동"));
	}

	// 탑골공원: 공식 이름은 숫자 사이가 가운뎃점인데, 쉼표로 바꿔 낸다
	@Test
	void 이름의_가운뎃점은_쉼표로_바꾼다() {
		assertThat(lookup.find(GeoPoint.of(37.571070, 126.988230)))
				.contains(new Region("서울특별시", "서울특별시 종로구", "서울특별시 종로구 종로1,2,3,4가동"));
	}

	// 일반구가 있는 시: 원본의 "수원시팔달구", "성남시분당구"를 "수원시 팔달구", "성남시 분당구"로 띄워 낸다
	@Test
	void 일반구가_있는_시는_시와_구를_띄워_쓴다() {
		assertThat(lookup.find(GeoPoint.of(37.265910, 127.000123)))
				.contains(new Region("경기도", "경기도 수원시 팔달구", "경기도 수원시 팔달구 매산동"));
		assertThat(lookup.find(GeoPoint.of(37.394761, 127.111217)))
				.contains(new Region("경기도", "경기도 성남시 분당구", "경기도 성남시 분당구 삼평동"));
	}

	@Test
	void 바다_위와_한국_밖은_찾지_못한다() {
		assertThat(lookup.find(GeoPoint.of(36.0, 124.0))).isEmpty();
		assertThat(lookup.find(GeoPoint.of(35.681236, 139.767125))).isEmpty();
	}
}
