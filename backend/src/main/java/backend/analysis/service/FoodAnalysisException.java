package backend.analysis.service;

// AI 분석 실패. 메시지는 DB의 error_message에 그대로 저장되는 짧은 이유다. (예: HTTP_429, EMPTY_RESPONSE)
public class FoodAnalysisException extends RuntimeException {

	public FoodAnalysisException(String reason) {
		super(reason);
	}
}
