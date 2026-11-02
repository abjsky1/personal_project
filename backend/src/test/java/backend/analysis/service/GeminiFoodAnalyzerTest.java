package backend.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import backend.analysis.model.dto.FoodAnalyzerResponse;
import tools.jackson.databind.json.JsonMapper;

// Gemini로 보내는 요청과 응답 처리를 확인하는 테스트. 실제 Gemini는 부르지 않는다.
// MockRestServiceServer: 진짜 서버 대신 정해 둔 응답을 돌려주는 가짜 서버
class GeminiFoodAnalyzerTest {

	private static final String URL = "https://generativelanguage.googleapis.com/v1beta/models/test-model:generateContent";

	private final JsonMapper jsonMapper = JsonMapper.builder().build();
	private final byte[] jpeg = {1, 2, 3};

	private MockRestServiceServer server;
	private GeminiFoodAnalyzer analyzer;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://generativelanguage.googleapis.com/v1beta");
		server = MockRestServiceServer.bindTo(builder).build();
		analyzer = new GeminiFoodAnalyzer(builder.build(), jsonMapper, "test-key", "test-model");
	}

	@Test
	void 사진과_형식을_보내고_응답을_결과로_바꾼다() {
		String result = """
				{"isFood":true,
				 "items":[{"name":"김치찌개","amountG":400,"kcal":320.5,"carbsG":12,"proteinG":22,"fatG":20}],
				 "confidence":"MEDIUM","comment":"1인분 기준"}
				""";
		server.expect(requestTo(URL))
				// 키는 주소가 아니라 헤더로 간다 (requestTo가 주소 전체를 비교하므로 주소에 키가 없다는 것도 함께 확인된다)
				.andExpect(header("x-goog-api-key", "test-key"))
				.andExpect(jsonPath("$.contents[0].parts[1].inline_data.mime_type").value("image/jpeg"))
				// {1, 2, 3}을 Base64로 바꾼 값
				.andExpect(jsonPath("$.contents[0].parts[1].inline_data.data").value("AQID"))
				.andExpect(jsonPath("$.generationConfig.responseMimeType").value("application/json"))
				.andExpect(jsonPath("$.generationConfig.responseSchema.required[0]").value("isFood"))
				.andRespond(withSuccess(geminiResponse(result), MediaType.APPLICATION_JSON));

		FoodAnalyzerResponse response = analyzer.analyze(jpeg);

		server.verify();
		assertThat(response.result().isFood()).isTrue();
		assertThat(response.result().items().get(0).name()).isEqualTo("김치찌개");
		assertThat(response.result().items().get(0).kcal()).isEqualTo(320.5);
		assertThat(response.result().confidence()).isEqualTo("MEDIUM");
		assertThat(response.model()).isEqualTo("test-model");
		assertThat(response.inputTokens()).isEqualTo(1300);
		assertThat(response.outputTokens()).isEqualTo(100);
	}

	@Test
	void 키가_없으면_Gemini를_부르지_않고_실패한다() {
		// server.expect를 하나도 안 적었으므로, 요청을 보내면 가짜 서버가 에러를 낸다.
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer noCallServer = MockRestServiceServer.bindTo(builder).build();
		GeminiFoodAnalyzer noKey = new GeminiFoodAnalyzer(builder.build(), jsonMapper, "", "test-model");

		assertThatThrownBy(() -> noKey.analyze(jpeg))
				.isInstanceOf(FoodAnalysisException.class)
				.hasMessage("NO_API_KEY");
		noCallServer.verify();
	}

	@Test
	void Gemini가_에러로_답하면_상태_코드를_이유로_실패한다() {
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

		assertThatThrownBy(() -> analyzer.analyze(jpeg))
				.isInstanceOf(FoodAnalysisException.class)
				.hasMessage("HTTP_429");
	}

	@Test
	void 시간이_초과되면_NETWORK로_실패한다() {
		server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("timeout")));

		assertThatThrownBy(() -> analyzer.analyze(jpeg))
				.isInstanceOf(FoodAnalysisException.class)
				.hasMessage("NETWORK");
	}

	@Test
	void 답을_거부해_글자가_없으면_EMPTY_RESPONSE로_실패한다() {
		String blocked = """
				{"promptFeedback":{"blockReason":"SAFETY"}}
				""";
		server.expect(requestTo(URL)).andRespond(withSuccess(blocked, MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> analyzer.analyze(jpeg))
				.isInstanceOf(FoodAnalysisException.class)
				.hasMessage("EMPTY_RESPONSE");
	}

	@Test
	void 글자가_JSON이_아니면_BAD_RESPONSE로_실패한다() {
		server.expect(requestTo(URL))
				.andRespond(withSuccess(geminiResponse("김치찌개는 약 320kcal입니다"), MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> analyzer.analyze(jpeg))
				.isInstanceOf(FoodAnalysisException.class)
				.hasMessage("BAD_RESPONSE");
	}

	// Gemini 응답의 바깥 껍데기로 text를 감싼다.
	private String geminiResponse(String text) {
		return jsonMapper.writeValueAsString(Map.of(
				"candidates", List.of(Map.of("content", Map.of("parts", List.of(Map.of("text", text))))),
				"usageMetadata", Map.of("promptTokenCount", 1300, "candidatesTokenCount", 100)));
	}
}
