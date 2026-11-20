package backend.visit.model.entity;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 사용자 흐름(JM-92)에 남기는 화면. 주소의 경로로 정한다 (Controller의 화면 주소와 같다, 기획서 13절)
// UPLOAD만 화면이 아니라 글 올리기다: POST /posts가 새 글로 넘어간 것. 올리기까지 간 방문을 세려고 남긴다
// 새 화면을 만들면 여기에 더한다. 더하지 않으면 PageFlowTest가 실패한다 (사용자 화면의 주소를 모두 훑어 본다)
public enum Screen {

	FEED("/"),
	POST("/posts/(\\d{1,18})"),
	POST_NEW("/posts/new"),
	UPLOAD(null),
	NOTICE_LIST("/notice"),
	NOTICE("/notice/(\\d{1,18})"),
	FEEDBACK_LIST("/feedback"),
	FEEDBACK("/feedback/(\\d{1,18})"),
	FEEDBACK_NEW("/feedback/new"),
	PRIVACY("/privacy"),
	TERMS("/terms");

	// 경로 전체가 이 모양이어야 그 화면이다. 괄호 안은 글 번호 (18자리까지: long에 들어가는 길이)
	private final Pattern path;

	Screen(String path) {
		this.path = path == null ? null : Pattern.compile(path);
	}

	// 경로(예: /posts/7)가 어느 화면인지와 주소 안의 글 번호. 화면이 아니면 null
	// 지금 받은 화면과, Referer로 본 바로 앞 화면을 같은 규칙으로 찾는다
	public static Match match(String path) {
		if (path == null) {
			return null;
		}
		for (Screen screen : values()) {
			if (screen.path == null) {
				continue;
			}
			Matcher matcher = screen.path.matcher(path);
			if (matcher.matches()) {
				return new Match(screen, matcher.groupCount() > 0 ? Long.valueOf(matcher.group(1)) : null);
			}
		}
		return null;
	}

	// 찾은 화면과 주소 안의 글 번호 (번호가 없는 화면이면 null)
	public record Match(Screen screen, Long id) {
	}
}
