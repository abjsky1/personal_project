package backend.visit.model.entity;

import java.util.regex.Pattern;

// 휴대폰인지 컴퓨터인지 (사용자 흐름, JM-92). User-Agent에서 이것만 남기고 User-Agent 전체는 남기지 않는다
public enum Device {

	MOBILE, DESKTOP;

	// 서버 기록으로 트래픽을 셀 때와 같은 낱말 (기록 저장소 nginx_journey.py) → 바꾸기 전 숫자와 견줄 수 있다.
	// 안드로이드 태블릿은 휴대폰으로, 요즘 아이패드의 사파리는 컴퓨터와 같은 User-Agent를 보내서 컴퓨터로 보인다
	private static final Pattern MOBILE_AGENT = Pattern.compile("Mobile|iPhone|Android");

	public static Device of(String userAgent) {
		return userAgent != null && MOBILE_AGENT.matcher(userAgent).find() ? MOBILE : DESKTOP;
	}
}
