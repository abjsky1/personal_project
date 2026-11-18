// 방문 통계(JM-86)의 서비스를 두는 패키지
package backend.visit.service;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.maxmind.db.Reader;

import backend.visit.model.vo.Sido;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

// 요청을 보낸 IP가 어느 시/도인지 찾는다 (JM-86, 운영자 통계의 시/도별 방문 수)
// 서버에 둔 DB-IP 무료판 파일(IP to City Lite, .mmdb, CC BY 4.0)을 읽는다. 파일은 약 121MB라 git에 넣지 않는다 (기획서 16절)
// IP는 메모리에서 시/도를 찾는 데만 쓰고 저장하지 않는다. 로그와 예외 메시지에도 IP를 넣지 않는다
// 파일이 없으면(내 PC, 테스트, 서버에 파일을 두기 전) 모두 "알 수 없음"이고, 서버가 켜지는 데는 지장이 없다
@Slf4j
@Component
public class IpRegionResolver {

	// 숫자와 점(IPv4) 또는 콜론이 들어간 16진수(IPv6)만 받는다. 이름(도메인)이면 DNS를 찾으러 가지 않도록 바로 "알 수 없음"
	private static final Pattern IP_LITERAL = Pattern.compile("[0-9.]+|[0-9a-fA-F.]*:[0-9a-fA-F:.]*");
	// 파일이 없을 때 다시 찾아보는 간격: 서버에 파일을 두면 app을 다시 켜지 않아도 10분 안에 읽기 시작한다
	private static final long RETRY_MILLIS = 10 * 60 * 1000L;

	private final File file;
	private Reader reader;
	private long nextTryMillis;
	private boolean reportedMissing;

	// path: app.geoip.path. 비어 있으면 파일을 찾지 않는다 (application-prod.properties가 서버의 경로를 넣는다)
	public IpRegionResolver(@Value("${app.geoip.path:}") String path) {
		this.file = path == null || path.isBlank() ? null : new File(path);
	}

	public String sidoOf(String ip) {
		if (ip == null || !IP_LITERAL.matcher(ip).matches()) {
			return Sido.UNKNOWN;
		}
		Reader current = reader();
		if (current == null) {
			return Sido.UNKNOWN;
		}
		try {
			// Map.class: 파일의 한 칸(나라, 시/도, 도시 등)을 Map과 List로 그대로 읽는다
			Map<?, ?> record = current.get(InetAddress.getByName(ip), Map.class);
			return record == null ? Sido.UNKNOWN : Sido.of(countryCode(record), subdivision(record));
		} catch (IOException | RuntimeException e) {
			// 예외 메시지에 IP가 들어 있을 수 있어서 종류만 남긴다
			log.warn("IP region lookup failed: {}", e.getClass().getSimpleName());
			return Sido.UNKNOWN;
		}
	}

	// {"country": {"iso_code": "KR"}}
	private static String countryCode(Map<?, ?> record) {
		return record.get("country") instanceof Map<?, ?> country && country.get("iso_code") instanceof String code ? code : null;
	}

	// {"subdivisions": [{"names": {"en": "Seoul"}}]} 의 첫 번째 시/도
	private static String subdivision(Map<?, ?> record) {
		if (record.get("subdivisions") instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first
				&& first.get("names") instanceof Map<?, ?> names && names.get("en") instanceof String name) {
			return name;
		}
		return null;
	}

	// 파일은 처음 쓸 때 연다. 없으면 10분 뒤에 다시 찾아본다
	// synchronized: 여러 요청이 동시에 처음 와도 파일은 한 번만 연다 (연 뒤의 찾기는 Reader가 여러 요청을 동시에 받아 준다)
	private synchronized Reader reader() {
		if (reader != null || file == null) {
			return reader;
		}
		long now = System.currentTimeMillis();
		if (now < nextTryMillis) {
			return null;
		}
		nextTryMillis = now + RETRY_MILLIS;
		if (!file.isFile()) {
			if (!reportedMissing) {
				reportedMissing = true;
				log.info("IP region file not found, visits count as unknown: {}", file);
			}
			return null;
		}
		try {
			// 기본 방식(MEMORY_MAPPED): 파일을 힙에 올리지 않고 운영체제가 필요한 부분만 읽는다 (자바 힙 512MB를 쓰지 않는다)
			reader = new Reader(file);
			log.info("IP region file loaded: {} ({}, built {})", file, reader.getMetadata().databaseType(),
					reader.getMetadata().buildTime());
		} catch (IOException | RuntimeException e) {
			log.warn("IP region file could not be opened: {} ({})", file, e.getClass().getSimpleName());
		}
		return reader;
	}

	@PreDestroy
	public synchronized void close() throws IOException {
		if (reader != null) {
			reader.close();
			reader = null;
		}
	}
}
