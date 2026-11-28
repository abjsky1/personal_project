package backend.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import backend.common.web.AppAgent.Build;
import backend.common.web.AppAgent.Platform;

// 앱 이름표(User-Agent) 읽기 (JM-90, 기획서 19.3)
class AppAgentTest {

	@Test
	void 아이폰과_안드로이드의_앱_이름표에서_플랫폼과_버전과_빌드를_읽는다() {
		assertThat(AppAgent.parse("JjikmukApp/1.0.0+1 (IOS 27.2; DEBUG)"))
				.contains(new AppAgent(Platform.IOS, "1.0.0+1", Build.DEBUG));
		assertThat(AppAgent.parse("JjikmukApp/1.2.3 (ANDROID 17; RELEASE)"))
				.contains(new AppAgent(Platform.ANDROID, "1.2.3", Build.RELEASE));
		// OS 버전은 점이 여럿이거나 영문이 섞여도 된다 (남기지는 않는다)
		assertThat(AppAgent.parse("JjikmukApp/2 (IOS 18.0.1; RELEASE)")).isPresent();
		assertThat(AppAgent.parse("JjikmukApp/2 (ANDROID Baklava; DEBUG)")).isPresent();
	}

	@Test
	void 이름표가_없으면_빈_값이다() {
		assertThat(AppAgent.parse(null)).isEmpty();
		assertThat(AppAgent.parse("")).isEmpty();
	}

	@Test
	void 앱이_아닌_이름표는_빈_값이다() {
		// 휴대폰 브라우저
		assertThat(AppAgent.parse("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 Safari/604.1"))
				.isEmpty();
		// 플러터(dart:io)의 기본 이름표: 앱이 이름표를 붙이지 않고 보낸 경우
		assertThat(AppAgent.parse("Dart/3.14 (dart:io)")).isEmpty();
	}

	@Test
	void 모양이_조금이라도_다르면_빈_값이다() {
		// 정해 두지 않은 플랫폼과 빌드, 소문자
		assertThat(AppAgent.parse("JjikmukApp/1.0.0 (WEB 1; DEBUG)")).isEmpty();
		assertThat(AppAgent.parse("JjikmukApp/1.0.0 (IOS 27.2; PROFILE)")).isEmpty();
		assertThat(AppAgent.parse("JjikmukApp/1.0.0 (ios 27.2; debug)")).isEmpty();
		// 빠진 칸
		assertThat(AppAgent.parse("JjikmukApp/1.0.0 (IOS 27.2)")).isEmpty();
		assertThat(AppAgent.parse("JjikmukApp/1.0.0 (IOS; DEBUG)")).isEmpty();
		assertThat(AppAgent.parse("JjikmukApp/ (IOS 27.2; DEBUG)")).isEmpty();
		// 버전이 숫자가 아님, 너무 긴 버전
		assertThat(AppAgent.parse("JjikmukApp/latest (IOS 27.2; DEBUG)")).isEmpty();
		assertThat(AppAgent.parse("JjikmukApp/12345.0.0 (IOS 27.2; DEBUG)")).isEmpty();
		assertThat(AppAgent.parse("JjikmukApp/1.0.0+1234567890 (IOS 27.2; DEBUG)")).isEmpty();
		// 앞이나 뒤에 다른 글자가 붙음
		assertThat(AppAgent.parse("Mozilla/5.0 JjikmukApp/1.0.0 (IOS 27.2; DEBUG)")).isEmpty();
		assertThat(AppAgent.parse("JjikmukApp/1.0.0 (IOS 27.2; DEBUG) extra")).isEmpty();
	}
}
