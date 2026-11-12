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
import backend.analysis.model.dto.FoodCandidates;
import backend.analysis.model.dto.FoodCandidatesResponse;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Google Gemini API로 음식 사진을 분석한다. 지시문과 응답 형식은 모델 비교 때 쓴 것과 같다. (docs/ai-model-comparison.md)
// 로그에는 상태만 남기고, 사진이나 AI 응답 내용은 남기지 않는다.
//
// 지시문이 둘이다 (JM-74, 고쳐 쓰는 AI)
// - analyze: 음식마다 답 하나. "AI가 바로 확정"으로 올린 글. 처음부터 쓰던 것 그대로다.
// - analyzeCandidates: 음식마다 후보 세 개와 100g당 영양. "내가 보고 확정"으로 올린 글.
//   답이 길어서 더 느리고 토큰을 더 쓰므로, 고른 글에만 쓴다.
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

	// 후보용 지시문. 영양을 "100g당"으로 받는 이유: 사용자가 양을 고치거나 다른 후보를 골라도 서버가 곱해서 다시 계산할 수 있다.
	private static final String CANDIDATES_PROMPT = """
			당신은 영양 분석 도우미입니다. 사진 속 음식을 보고 영양 정보를 추정하세요. 사용자가 맞는 것을 고를 수 있게 음식마다 후보를 냅니다.
			- 음식 사진이 아니면 isFood=false, items는 빈 배열로 답합니다.
			- 음식이면 보이는 음식(반찬 포함)마다 하나씩: 사진 속 양의 추정 무게(amountG, g)와 후보(candidates) 3개를 냅니다.
			- 후보는 그 음식일 가능성이 높은 순서로 놓습니다. 첫 번째가 가장 그럴듯한 답이고, 나머지 둘은 사진에서 비슷하게 보일 수 있는 다른 음식입니다. 세 후보의 이름은 서로 달라야 합니다.
			- 후보마다: 한국어 이름(name), 100g당 칼로리(kcalPer100g), 100g당 탄수화물(carbsPer100g), 100g당 단백질(proteinPer100g), 100g당 지방(fatPer100g)을 추정합니다. 사진 속 양이 아니라 100g 기준입니다.
			- 양은 그릇 크기와 일반적인 1인분을 기준으로 추정합니다.
			- confidence: 첫 번째 후보들에 대한 확신 정도 (LOW / MEDIUM / HIGH)
			- comment: 어떻게 추정했는지 한국어 한 문장
			""";

	// responseSchema: AI가 이 모양의 JSON으로만 답하게 강제한다. (프롬프트로 부탁하면 앞뒤에 다른 글이 붙을 수 있다)
	// 이름은 FoodAnalysisResult와 똑같이 맞춘다. 만들어서 보내기만 하는 값이라 record 대신 Map으로 쓴다.
	private static final Map<String, Object> NUMBER = Map.of("type", "NUMBER");
	private static final Map<String, Object> STRING = Map.of("type", "STRING");
	private static final Map<String, Object> CONFIDENCE = Map.of("type", "STRING", "enum", List.of("LOW", "MEDIUM", "HIGH"));
	private static final Map<String, Object> RESPONSE_SCHEMA = Map.of(
			"type", "OBJECT",
			"properties", Map.of(
					"isFood", Map.of("type", "BOOLEAN"),
					"items", Map.of(
							"type", "ARRAY",
							"items", Map.of(
									"type", "OBJECT",
									"properties", Map.of(
											"name", STRING,
											"amountG", NUMBER,
											"kcal", NUMBER,
											"carbsG", NUMBER,
											"proteinG", NUMBER,
											"fatG", NUMBER),
									"required", List.of("name", "amountG", "kcal", "carbsG", "proteinG", "fatG"))),
					"confidence", CONFIDENCE,
					"comment", STRING),
			"required", List.of("isFood", "items", "confidence", "comment"));

	// 후보용 응답 형식. 이름은 FoodCandidates와 똑같이 맞춘다.
	private static final Map<String, Object> CANDIDATES_SCHEMA = Map.of(
			"type", "OBJECT",
			"properties", Map.of(
					"isFood", Map.of("type", "BOOLEAN"),
					"items", Map.of(
							"type", "ARRAY",
							"items", Map.of(
									"type", "OBJECT",
									"properties", Map.of(
											"amountG", NUMBER,
											"candidates", Map.of(
													"type", "ARRAY",
													"items", Map.of(
															"type", "OBJECT",
															"properties", Map.of(
																	"name", STRING,
																	"kcalPer100g", NUMBER,
																	"carbsPer100g", NUMBER,
																	"proteinPer100g", NUMBER,
																	"fatPer100g", NUMBER),
															"required", List.of("name", "kcalPer100g", "carbsPer100g", "proteinPer100g", "fatPer100g")))),
									"required", List.of("amountG", "candidates"))),
					"confidence", CONFIDENCE,
					"comment", STRING),
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
		JsonNode root = ask(PROMPT, RESPONSE_SCHEMA, jpeg);
		return new FoodAnalyzerResponse(
				parse(answerOf(root), FoodAnalysisResult.class),
				model,
				root.at("/usageMetadata/promptTokenCount").asInt(0),
				root.at("/usageMetadata/candidatesTokenCount").asInt(0));
	}

	@Override
	public FoodCandidatesResponse analyzeCandidates(byte[] jpeg) {
		JsonNode root = ask(CANDIDATES_PROMPT, CANDIDATES_SCHEMA, jpeg);
		return new FoodCandidatesResponse(
				parse(answerOf(root), FoodCandidates.class),
				model,
				root.at("/usageMetadata/promptTokenCount").asInt(0),
				root.at("/usageMetadata/candidatesTokenCount").asInt(0));
	}

	// 지시문과 사진을 보내고 Gemini의 응답 전체를 돌려준다. 두 분석이 함께 쓴다.
	private JsonNode ask(String prompt, Map<String, Object> schema, byte[] jpeg) {
		// 키가 없으면(CI, 테스트) 보내 봤자 거절당하므로 바로 실패로 처리한다.
		if (apiKey.isBlank()) {
			throw new FoodAnalysisException("NO_API_KEY");
		}
		return readJson(call(prompt, schema, jpeg));
	}

	// 응답의 바깥 껍데기는 Google이 정한 모양이다. 필요한 값만 꺼내면 돼서 JsonNode로 읽는다.
	// at("/a/0/b"): 경로로 값을 찾는다. 없으면 오류 없이 "빈 값"이 돌아온다.
	private String answerOf(JsonNode root) {
		JsonNode text = root.at("/candidates/0/content/parts/0/text");
		// AI가 답을 거부하면(안전 필터 등) 정상 응답(200)인데 답이 없다. 이것도 실패로 처리한다.
		if (!text.isString()) {
			log.warn("Gemini returned no text: finishReason={}, blockReason={}",
					root.at("/candidates/0/finishReason").asString(""),
					root.at("/promptFeedback/blockReason").asString(""));
			throw new FoodAnalysisException("EMPTY_RESPONSE");
		}
		return text.asString();
	}

	private String call(String prompt, Map<String, Object> schema, byte[] jpeg) {
		try {
			return restClient.post()
					.uri("/models/{model}:generateContent", model)
					// 키는 주소(?key=)가 아니라 헤더로 보낸다. 주소는 로그나 에러 메시지에 남기 쉽다.
					.header("x-goog-api-key", apiKey)
					.contentType(MediaType.APPLICATION_JSON)
					.body(requestBody(prompt, schema, jpeg))
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
	private Map<String, Object> requestBody(String prompt, Map<String, Object> schema, byte[] jpeg) {
		String base64 = Base64.getEncoder().encodeToString(jpeg);
		return Map.of(
				"contents", List.of(Map.of("parts", List.of(
						Map.of("text", prompt),
						Map.of("inline_data", Map.of("mime_type", "image/jpeg", "data", base64))))),
				"generationConfig", Map.of(
						"responseMimeType", "application/json",
						"responseSchema", schema));
	}

	private JsonNode readJson(String body) {
		try {
			return jsonMapper.readTree(body);
		} catch (JacksonException e) {
			throw new FoodAnalysisException("BAD_RESPONSE");
		}
	}

	// 안쪽 text는 우리가 responseSchema로 정한 모양의 JSON 글자다. record로 바꿔서 값을 안전하게 꺼내 쓴다.
	private <T> T parse(String text, Class<T> type) {
		try {
			return jsonMapper.readValue(text, type);
		} catch (JacksonException e) {
			throw new FoodAnalysisException("BAD_RESPONSE");
		}
	}
}
