package backend.analysis.service;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

// Gemini API를 부를 때 쓰는 RestClient를 등록한다.
// 시간 제한을 꼭 정한다. 기본값은 제한이 없어서, 응답이 안 오면 분석 일꾼이 계속 기다리며 멈춰 있게 된다.
@Configuration
public class GeminiConfig {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
	// 모델 비교에서 5초를 넘은 적이 없어서 30초면 충분하다. (docs/ai-model-comparison.md)
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

	// RestClient: 다른 서버에 HTTP 요청을 보내는 도구. WebClient와 쓰는 모양이 거의 같은데, 라이브러리를 더 넣지 않아도 된다.
	@Bean
	public RestClient geminiRestClient() {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
		requestFactory.setReadTimeout(READ_TIMEOUT);

		return RestClient.builder()
				.baseUrl("https://generativelanguage.googleapis.com/v1beta")
				.requestFactory(requestFactory)
				.build();
	}
}
