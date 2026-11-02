package backend.analysis.model.entity;

// AI 분석의 진행 상태 (기획서 12절)
public enum AnalysisStatus {
	// 분석을 기다리거나 분석하는 중
	PENDING,
	// 분석이 끝나서 음식과 칼로리가 저장됨
	DONE,
	// 분석은 끝났지만 음식 사진이 아니었음
	NOT_FOOD,
	// 실패 (시간 초과, 이상한 응답 등). 이유는 errorMessage에 적는다.
	FAILED,
	// 오늘 분석 횟수 상한을 넘어서 AI를 부르지 않았음
	LIMIT_EXCEEDED
}
