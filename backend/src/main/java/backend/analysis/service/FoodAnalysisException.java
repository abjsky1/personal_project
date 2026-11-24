package backend.analysis.service;

// AI 분석 실패. 메시지는 DB의 error_message에 그대로 저장되는 짧은 이유다. (예: HTTP_429, EMPTY_RESPONSE)
public class FoodAnalysisException extends RuntimeException {

	public FoodAnalysisException(String reason) {
		super(reason);
	}

	// AI에게서 답을 받지 못한 실패인지 (GeminiFoodAnalyzer와 태그 채우기가 붙이는 이유로 가린다):
	// 키가 없어 부르지 못함, 연결 실패나 시간 초과(NETWORK), AI 서버가 오류로 답함(HTTP_429, HTTP_503 등),
	// 사진 저장소(S3)가 잠깐 안 됨(IMAGE_LOAD, JM-83). 잠시 뒤 다시 하면 될 수 있는 실패라서, 횟수를 쓰지 않은 것으로 돌려준다
	public boolean isNoAnswer() {
		String reason = getMessage();
		return "NO_API_KEY".equals(reason) || "NETWORK".equals(reason) || "IMAGE_LOAD".equals(reason)
				|| (reason != null && reason.startsWith("HTTP_"));
	}
}
