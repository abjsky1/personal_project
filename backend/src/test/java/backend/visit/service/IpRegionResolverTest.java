package backend.visit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import backend.visit.model.vo.Sido;

// IP로 시/도 찾기 (JM-86). 실제 라이브러리(maxmind-db)로 파일을 읽는다.
// 파일은 프로젝트가 같은 형식으로 직접 만든 아주 작은 시험용이다 (DB-IP 자료가 아님, src/test/resources/geoip/README.md):
// 1.0.0.0/8 한국 Seoul, 2.0.0.0/8 한국 Gyeonggi-do, 3.0.0.0/8 한국(시/도 없음), 4.0.0.0/8 미국 California
class IpRegionResolverTest {

	private static String testFile() throws IOException {
		return new ClassPathResource("geoip/test-city.mmdb").getFile().getPath();
	}

	@Test
	void IP로_시도를_찾는다() throws IOException {
		IpRegionResolver resolver = new IpRegionResolver(testFile());

		assertThat(resolver.sidoOf("1.2.3.4")).isEqualTo("서울특별시");
		assertThat(resolver.sidoOf("2.200.0.1")).isEqualTo("경기도");
		assertThat(resolver.sidoOf("3.3.3.3")).isEqualTo(Sido.UNKNOWN);
		assertThat(resolver.sidoOf("4.4.4.4")).isEqualTo(Sido.ABROAD);
		assertThat(resolver.sidoOf("5.5.5.5")).isEqualTo(Sido.UNKNOWN);
		resolver.close();
	}

	// IP가 아니면 DNS를 찾으러 가지 않고, 찾다가 실패해도 예외 없이 알 수 없음
	@Test
	void IP가_아니거나_찾지_못하면_알_수_없음이다() throws IOException {
		IpRegionResolver resolver = new IpRegionResolver(testFile());

		assertThat(resolver.sidoOf(null)).isEqualTo(Sido.UNKNOWN);
		assertThat(resolver.sidoOf("example.com")).isEqualTo(Sido.UNKNOWN);
		// 시험용 파일은 IPv4만 있어서 IPv6는 라이브러리가 예외를 던진다 → 알 수 없음
		assertThat(resolver.sidoOf("0:0:0:0:0:0:0:1")).isEqualTo(Sido.UNKNOWN);
		resolver.close();
	}

	// 내 PC와 테스트, 서버에 파일을 두기 전: 서버가 켜지고 모두 알 수 없음으로 센다
	@Test
	void 파일이_없으면_모두_알_수_없음이다() {
		assertThat(new IpRegionResolver("").sidoOf("1.2.3.4")).isEqualTo(Sido.UNKNOWN);
		assertThat(new IpRegionResolver("build/no-such-folder/dbip-city-lite.mmdb").sidoOf("1.2.3.4")).isEqualTo(Sido.UNKNOWN);
	}
}
