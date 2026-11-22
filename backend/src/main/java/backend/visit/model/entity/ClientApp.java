package backend.visit.model.entity;

import java.util.regex.Pattern;

// 무엇으로 들어왔나 (사용자 흐름, JM-92). 방문의 첫 줄에만 남긴다. User-Agent 전체는 남기지 않는다
// 앞의 여섯은 앱 안의 브라우저라 들어온 곳도 알려 준다 (카카오톡 대화방의 링크, 인스타그램 등).
// 앱 안의 브라우저도 User-Agent에 Chrome이나 Safari가 들어 있어서 위에서부터 차례로 보고 처음 맞는 것을 고른다.
// 서버 기록으로 셀 때(기록 저장소 nginx_journey.py)와 같은 순서와 낱말이라 바꾸기 전 숫자와 견줄 수 있다.
// 거기에 아이폰과 안드로이드의 엣지, 아이폰의 파이어폭스 이름만 더했다 (예전 규칙으로는 사파리, 크롬으로 셌다)
public enum ClientApp {

	INSTAGRAM("Instagram"),
	KAKAOTALK("KAKAOTALK"),
	NAVER("NAVER"),
	FACEBOOK("FBAN|FBAV"),
	LINE(" Line/"),
	DAUM("DaumApps"),
	SAMSUNG("SamsungBrowser"),
	WHALE("Whale"),
	EDGE("Edg/|EdgA/|EdgiOS"),
	FIREFOX("Firefox|FxiOS"),
	CHROME("Chrome|CriOS"),
	SAFARI("Safari"),
	OTHER(null);

	// 대소문자를 가린다 (카카오톡은 KAKAOTALK처럼 대문자로 보낸다)
	private final Pattern agent;

	ClientApp(String agent) {
		this.agent = agent == null ? null : Pattern.compile(agent);
	}

	public static ClientApp of(String userAgent) {
		if (userAgent != null) {
			for (ClientApp app : values()) {
				if (app.agent != null && app.agent.matcher(userAgent).find()) {
					return app;
				}
			}
		}
		return OTHER;
	}
}
