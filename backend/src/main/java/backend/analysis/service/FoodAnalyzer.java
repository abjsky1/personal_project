package backend.analysis.service;

import backend.analysis.model.dto.FoodAnalyzerResponse;

// 사진을 보고 음식과 칼로리를 추정하는 기능의 "규격" (ImageStorage와 같은 방식)
// AI 공급자를 바꿀 때 구현 클래스만 새로 만들면 되고, 이 규격을 쓰는 코드는 그대로 둘 수 있다.
public interface FoodAnalyzer {

	// jpeg: 위치 정보(EXIF)를 지운 사진. 분석하지 못하면 FoodAnalysisException을 던진다.
	FoodAnalyzerResponse analyze(byte[] jpeg);
}
