package backend.visit.model.vo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// IP 위치 데이터의 영어 시/도 이름을 우리말로 바꾸기 (JM-86). 같은 곳도 표기가 여러 가지라 낱말로 찾는다
class SidoTest {

	@Test
	void 표기가_달라도_같은_시도로_바꾼다() {
		assertThat(Sido.of("KR", "Seoul")).isEqualTo("서울특별시");
		assertThat(Sido.of("KR", "Seoul-teukbyeolsi")).isEqualTo("서울특별시");
		assertThat(Sido.of("kr", "Busan")).isEqualTo("부산광역시");
		assertThat(Sido.of("KR", "Gyeonggi-do")).isEqualTo("경기도");
		assertThat(Sido.of("KR", "Gangwon State")).isEqualTo("강원특별자치도");
		assertThat(Sido.of("KR", "Sejong-si")).isEqualTo("세종특별자치시");
		assertThat(Sido.of("KR", "Jeju-do")).isEqualTo("제주특별자치도");
	}

	// 북도와 남도: 앞 글자가 같아서 긴 낱말부터 본다
	@Test
	void 북도와_남도를_섞지_않는다() {
		assertThat(Sido.of("KR", "Chungcheongbuk-do")).isEqualTo("충청북도");
		assertThat(Sido.of("KR", "North Chungcheong")).isEqualTo("충청북도");
		assertThat(Sido.of("KR", "Chungcheongnam-do")).isEqualTo("충청남도");
		assertThat(Sido.of("KR", "Jeonbuk State")).isEqualTo("전북특별자치도");
		assertThat(Sido.of("KR", "Gyeongsangbuk-do")).isEqualTo("경상북도");
		assertThat(Sido.of("KR", "South Gyeongsang")).isEqualTo("경상남도");
	}

	// 사진 위치 통계(JM-85)의 행정동 경계 2026-10-01판과 같게, 광주와 전남은 합친 이름으로 센다
	@Test
	void 광주와_전남은_전남광주통합특별시로_센다() {
		assertThat(Sido.of("KR", "Gwangju")).isEqualTo("전남광주통합특별시");
		assertThat(Sido.of("KR", "Jeollanam-do")).isEqualTo("전남광주통합특별시");
		assertThat(Sido.of("KR", "South Jeolla")).isEqualTo("전남광주통합특별시");
	}

	@Test
	void 한국이_아니면_해외이고_모르면_알_수_없음이다() {
		assertThat(Sido.of("US", "California")).isEqualTo(Sido.ABROAD);
		assertThat(Sido.of("JP", null)).isEqualTo(Sido.ABROAD);
		assertThat(Sido.of("KR", null)).isEqualTo(Sido.UNKNOWN);
		assertThat(Sido.of("KR", "Atlantis")).isEqualTo(Sido.UNKNOWN);
		assertThat(Sido.of(null, "Seoul")).isEqualTo(Sido.UNKNOWN);
		assertThat(Sido.of(" ", null)).isEqualTo(Sido.UNKNOWN);
	}
}
