package backend.common.web;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 찍먹 앱이 보낸 요청의 앱 이름표(User-Agent)를 읽는다 (JM-90, 기획서 19.3)
// 앱은 모든 요청에 "JjikmukApp/{앱 버전} ({IOS 또는 ANDROID} {OS 버전}; {DEBUG 또는 RELEASE})"를 붙인다.
// 예: JjikmukApp/1.0.0+1 (IOS 27.2; DEBUG), JjikmukApp/1.0.0+1 (ANDROID 17; RELEASE)
// 앱 조회 기록(app_post_views)에 플랫폼, 빌드, 앱 버전을 남길 때 쓴다. OS 버전은 모양만 보고 남기지 않는다.
// 이름표는 누구나 흉내 낼 수 있어서, 이 값으로 사람이나 기기를 가려내지 않는다. 기록을 나눠 세는 데만 쓴다
public record AppAgent(Platform platform, String appVersion, Build build) {

	public enum Platform {
		IOS,
		ANDROID
	}

	// DEBUG: 개발 중에 맥북에서 깐 앱 (운영자의 시험), RELEASE: 나눠 준 앱과 스토어의 앱
	public enum Build {
		DEBUG,
		RELEASE
	}

	// 앱 버전: 점으로 이은 숫자. 플러터 pubspec의 "1.0.0+1"처럼 + 뒤에 빌드 번호를 붙여도 된다 (가장 길면 29자)
	// OS 버전: 숫자, 영문, 점, 밑줄, 붙임표로 20자까지 (아이폰 27.2, 안드로이드 17)
	// 앞뒤에 다른 글자가 붙으면 받지 않는다 (matches: 전체가 이 모양이어야 한다). 대소문자도 가린다
	private static final Pattern FORMAT = Pattern.compile(
			"JjikmukApp/(\\d{1,4}(?:\\.\\d{1,4}){0,3}(?:\\+\\d{1,9})?) \\((IOS|ANDROID) [0-9A-Za-z._-]{1,20}; (DEBUG|RELEASE)\\)");

	// 모양이 맞으면 읽은 값, 없거나 모양이 다르면 빈 값
	public static Optional<AppAgent> parse(String userAgent) {
		if (userAgent == null) {
			return Optional.empty();
		}
		Matcher matcher = FORMAT.matcher(userAgent);
		if (!matcher.matches()) {
			return Optional.empty();
		}
		return Optional.of(new AppAgent(Platform.valueOf(matcher.group(2)), matcher.group(1), Build.valueOf(matcher.group(3))));
	}
}
