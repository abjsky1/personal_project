package backend.analysis.service;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import backend.analysis.model.dto.FoodAnalysisResult;
import backend.analysis.model.dto.FoodAnalyzerResponse;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Google Gemini API로 음식 사진을 분석한다. 지시문과 응답 형식은 모델 비교 때 쓴 것과 같다. (docs/ai-model-comparison.md)
// 로그에는 상태만 남기고, 사진이나 AI 응답 내용은 남기지 않는다.
@Slf4j
@Component
public class GeminiFoodAnalyzer implements FoodAnalyzer {

	private static final String PROMPT = """
			당신은 영양 분석 도우미입니다. 사진 속 음식을 보고 영양 정보를 추정하세요.
			- 음식 사진이 아니면 isFood=false, items는 빈 배열로 답합니다.
			- 음식이면 보이는 음식(반찬 포함)마다 하나씩: 한국어 이름(name), 사진 속 양의 추정 무게(amountG, g), 칼로리(kcal), 탄수화물(carbsG), 단백질(proteinG), 지방(fatG)을 추정합니다.
			- 양은 그릇 크기와 일반적인 1인분을 기준으로 추정합니다.
			- confidence: 추정의 확신 정도 (LOW / MEDIUM / HIGH)
			- comment: 어떻게 추정했는지 한국어 한 문장
			""";

	// responseSchema: AI가 이 모양의 JSON으로만 답하게 강제한다. (프롬프트로 부탁하면 앞뒤에 다른 글이 붙을 수 있다)
	// 이름은 FoodAnalysisResult와 똑같이 맞춘다. 만들어서 보내기만 하는 값이라 record 대신 Map으로 쓴다.
	private static final Map<String, Object> NUMBER = Map.of("type", "NUMBER");
	private static final Map<String, Object> RESPONSE_SCHEMA = Map.of(
			"type", "OBJECT",
			"properties", Map.of(
					"isFood", Map.of("type", "BOOLEAN"),
					"items", Map.of(
							"type", "ARRAY",
							"items", Map.of(
									"type", "OBJECT",
									"properties", Map.of(
											"name", Map.of("type", "STRING"),
											"amountG", NUMBER,
											"kcal", NUMBER,
											"carbsG", NUMBER,
											"proteinG", NUMBER,
											"fatG", NUMBER),
									"required", List.of("name", "amountG", "kcal", "carbsG", "proteinG", "fatG"))),
					"confidence", Map.of("type", "STRING", "enum", List.of("LOW", "MEDIUM", "HIGH")),
					"comment", Map.of("type", "STRING")),
			"required", List.of("isFood", "items", "confidence", "comment"));

	private final RestClient restClient;
	private final JsonMapper jsonMapper;
	private final String apiKey;
	private final String model;

	public GeminiFoodAnalyzer(
			RestClient geminiRestClient,
			JsonMapper jsonMapper,
			@Value("${app.gemini.api-key}") String apiKey,
			@Value("${app.gemini.model}") String model) {
		this.restClient = geminiRestClient;
		this.jsonMapper = jsonMapper;
		this.apiKey = apiKey;
		this.model = model;
	}

	@Override
	public FoodAnalyzerResponse analyze(byte[] jpeg) {
		// 키가 없으면(CI, 테스트) 보내 봤자 거절당하므로 바로 실패로 처리한다.
		if (apiKey.isBlank()) {
			throw new FoodAnalysisException("NO_API_KEY");
		}

		JsonNode root = readJson(call(jpeg));

		// 응답의 바깥 껍데기는 Google이 정한 모양이다. 필요한 값만 꺼내면 돼서 JsonNode로 읽는다.
		// at("/a/0/b"): 경로로 값을 찾는다. 없으면 오류 없이 "빈 값"이 돌아온다.
		JsonNode text = root.at("/candidates/0/content/parts/0/text");
		// AI가 답을 거부하면(안전 필터 등) 정상 응답(200)인데 답이 없다. 이것도 실패로 처리한다.
		if (!text.isString()) {
			log.warn("Gemini returned no text: finishReason={}, blockReason={}",
					root.at("/candidates/0/finishReason").asString(""),
					root.at("/promptFeedback/blockReason").asString(""));
			throw new FoodAnalysisException("EMPTY_RESPONSE");
		}

		return new FoodAnalyzerResponse(
				parseResult(text.asString()),
				model,
				root.at("/usageMetadata/promptTokenCount").asInt(0),
				root.at("/usageMetadata/candidatesTokenCount").asInt(0));
	}

	private String call(byte[] jpeg) {
		try {
			return restClient.post()
					.uri("/models/{model}:generateContent", model)
					// 키는 주소(?key=)가 아니라 헤더로 보낸다. 주소는 로그나 에러 메시지에 남기 쉽다.
					.header("x-goog-api-key", apiKey)
					.contentType(MediaType.APPLICATION_JSON)
					.body(requestBody(jpeg))
					.retrieve()
					.body(String.class);
		} catch (RestClientResponseException e) {
			// 429(한도 초과), 503(혼잡) 등 Gemini가 에러로 답한 경우
			throw new FoodAnalysisException("HTTP_" + e.getStatusCode().value());
		} catch (RestClientException e) {
			// 시간 초과, 연결 실패 등 응답 자체를 못 받은 경우
			throw new FoodAnalysisException("NETWORK");
		}
	}

	// contents: 보낼 내용 (지시문 + 사진) / generationConfig: 답하는 방식
	// 사진은 글자(Base64)로 바꿔서 JSON 안에 넣는다.
	private Map<String, Object> requestBody(byte[] jpeg) {
		String base64 = Base64.getEncoder().encodeToString(jpeg);
		return Map.of(
				"contents", List.of(Map.of("parts", List.of(
						Map.of("text", PROMPT),
						Map.of("inline_data", Map.of("mime_type", "image/jpeg", "data", base64))))),
				"generationConfig", Map.of(
						"responseMimeType", "application/json",
						"responseSchema", RESPONSE_SCHEMA));
	}

	private JsonNode readJson(String body) {
		try {
			return jsonMapper.readTree(body);
		} catch (JacksonException e) {
			throw new FoodAnalysisException("BAD_RESPONSE");
		}
	}

	// 안쪽 text는 우리가 responseSchema로 정한 모양의 JSON 글자다. record로 바꿔서 값을 안전하게 꺼내 쓴다.
	private FoodAnalysisResult parseResult(String text) {
		try {
			return jsonMapper.readValue(text, FoodAnalysisResult.class);
		} catch (JacksonException e) {
			throw new FoodAnalysisException("BAD_RESPONSE");
		}
	}
}
