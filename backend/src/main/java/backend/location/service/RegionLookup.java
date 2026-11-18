// 위치 도메인의 서비스를 두는 패키지
package backend.location.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import backend.location.model.vo.GeoPoint;
import backend.location.model.vo.Region;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

// 사진 위치(위도, 경도)가 어느 행정동에 있는지 찾는다 (JM-85, 운영자 통계의 지역별 글 수, 기획서 6.2절)
// 경계: 통계청 SGIS의 행정동 경계를 vuski/admdongkor가 가공한 2026-10-01판을, 이 프로젝트가 30m 간격으로 줄이고 이름 칸만 남긴 파일
//       (출처, 라이선스 CC BY 4.0, 만든 명령은 resources/region/README.md)
// 좌표는 메모리에서 지역을 찾는 데만 쓴다. 로그와 예외 메시지에 좌표를 넣지 않는다
@Component
@RequiredArgsConstructor
public class RegionLookup {

	static final String BOUNDARY_FILE = "region/hangjeongdong.geojson.gz";

	private static final char MIDDLE_DOT = (char) 0xB7;

	// 일반구가 있는 시는 원본에 "안양시만안구"처럼 붙어 있어서, 공식 표기대로 "안양시 만안구"로 띄운다 (39곳)
	private static final Pattern SI_GU = Pattern.compile("^(.+시)(.+구)$");

	private final JsonMapper jsonMapper;

	// 행정동 3,559개. 서버가 켜질 때가 아니라 처음 찾을 때 한 번 읽는다 (켜지는 시간과 테스트마다 읽는 시간을 아끼려고)
	private List<Area> areas;

	// 한국 밖이나 바다 위처럼 어느 행정동에도 들지 않으면 빈 값
	public Optional<Region> find(GeoPoint point) {
		// GeoJSON의 좌표는 [경도, 위도] 순서다: x = 경도, y = 위도 (GeoPoint는 위도가 먼저라 헷갈리기 쉽다)
		double x = point.longitude().doubleValue();
		double y = point.latitude().doubleValue();
		for (Area area : areas()) {
			if (area.contains(x, y)) {
				return Optional.of(area.region());
			}
		}
		return Optional.empty();
	}

	// synchronized: 두 요청이 동시에 처음 찾아도 파일은 한 번만 읽는다
	private synchronized List<Area> areas() {
		if (areas == null) {
			areas = load();
		}
		return areas;
	}

	// 파일 전체를 한꺼번에 트리로 만들지 않고, features 배열의 행정동을 하나씩 읽는다 (메모리를 덜 쓴다)
	private List<Area> load() {
		List<Area> loaded = new ArrayList<>();
		// 행정동 하나를 읽은 뒤에도 다음 행정동이 이어지므로, "값 뒤에 다른 값이 있으면 실패" 검사를 끈 reader로 읽는다
		ObjectReader featureReader = jsonMapper.readerFor(JsonNode.class).without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
		try (InputStream in = new GZIPInputStream(new ClassPathResource(BOUNDARY_FILE).getInputStream());
				JsonParser parser = jsonMapper.createParser(in)) {
			while (parser.nextToken() != null) {
				if (parser.currentToken() == JsonToken.PROPERTY_NAME && "features".equals(parser.currentName())) {
					parser.nextToken();
					while (parser.nextToken() == JsonToken.START_OBJECT) {
						loaded.add(Area.of(featureReader.readTree(parser)));
					}
					break;
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException("행정동 경계 파일을 읽지 못했습니다: " + BOUNDARY_FILE, e);
		}
		if (loaded.isEmpty()) {
			throw new IllegalStateException("행정동 경계 파일에 행정동이 없습니다: " + BOUNDARY_FILE);
		}
		return List.copyOf(loaded);
	}

	// 행정동 하나: 이름, 경계를 둘러싼 네모(빨리 거르는 용도), 경계선들
	// rings: 바깥 경계와 구멍(안쪽 경계), 섬이 여럿이면 그 경계까지 모두. 한 줄은 [x0, y0, x1, y1, ...]
	private record Area(Region region, double minX, double minY, double maxX, double maxY, List<double[]> rings) {

		static Area of(JsonNode feature) {
			JsonNode properties = feature.path("properties");
			String sido = text(properties, "sidonm");
			String sigungu = sido + " " + SI_GU.matcher(text(properties, "sggnm")).replaceFirst("$1 $2");
			// adm_nm은 "시/도 시/군/구 동"이라, 마지막 낱말(동 이름)만 떼어 띄운 시/군/구 뒤에 붙인다
			String admName = text(properties, "adm_nm");
			Region region = new Region(sido, sigungu, sigungu + " " + admName.substring(admName.lastIndexOf(' ') + 1));
			JsonNode geometry = feature.path("geometry");
			List<double[]> rings = new ArrayList<>();
			switch (text(geometry, "type")) {
				case "Polygon" -> addRings(geometry.path("coordinates"), rings);
				case "MultiPolygon" -> geometry.path("coordinates").values().forEach(polygon -> addRings(polygon, rings));
				default -> throw new IllegalStateException("행정동 경계의 모양을 읽지 못했습니다: " + region.dong());
			}
			double minX = Double.MAX_VALUE;
			double minY = Double.MAX_VALUE;
			double maxX = -Double.MAX_VALUE;
			double maxY = -Double.MAX_VALUE;
			for (double[] ring : rings) {
				for (int i = 0; i < ring.length; i += 2) {
					minX = Math.min(minX, ring[i]);
					maxX = Math.max(maxX, ring[i]);
					minY = Math.min(minY, ring[i + 1]);
					maxY = Math.max(maxY, ring[i + 1]);
				}
			}
			return new Area(region, minX, minY, maxX, maxY, List.copyOf(rings));
		}

		// 폴리곤 하나 = 경계선 여러 줄 (첫 줄이 바깥, 나머지는 구멍). 한 줄 = [[경도, 위도], ...]
		private static void addRings(JsonNode polygon, List<double[]> rings) {
			for (JsonNode line : polygon.values()) {
				double[] ring = new double[line.size() * 2];
				int i = 0;
				for (JsonNode position : line.values()) {
					ring[i++] = position.get(0).doubleValue();
					ring[i++] = position.get(1).doubleValue();
				}
				rings.add(ring);
			}
		}

		// 공식 이름의 가운뎃점(U+00B7. 예: 종로1, 2, 3, 4가를 묶은 행정동)은 쉼표로 바꾼다 (운영자 규칙: 화면과 문서에 가운뎃점을 쓰지 않는다)
		private static String text(JsonNode node, String name) {
			JsonNode value = node.path(name);
			if (!value.isString() || value.stringValue().isBlank()) {
				throw new IllegalStateException("행정동 경계 파일에 " + name + " 값이 없습니다");
			}
			return value.stringValue().replace(MIDDLE_DOT, ',');
		}

		// 짝홀 규칙: 점에서 오른쪽으로 그은 선이 경계선을 홀수 번 건너면 안, 짝수 번이면 밖이다
		// 구멍과 섬의 경계까지 모두 세면 구멍 안의 점은 짝수 번이 되어 밖으로 나온다
		boolean contains(double x, double y) {
			if (x < minX || x > maxX || y < minY || y > maxY) {
				return false;
			}
			boolean inside = false;
			for (double[] ring : rings) {
				for (int i = 0, j = ring.length - 2; i < ring.length; j = i, i += 2) {
					double xi = ring[i];
					double yi = ring[i + 1];
					double xj = ring[j];
					double yj = ring[j + 1];
					if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
						inside = !inside;
					}
				}
			}
			return inside;
		}
	}
}
