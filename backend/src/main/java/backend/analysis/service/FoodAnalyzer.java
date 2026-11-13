package backend.analysis.service;

import java.util.List;

import backend.analysis.model.dto.FoodAnalyzerResponse;
import backend.analysis.model.dto.FoodCandidatesResponse;
import backend.analysis.model.dto.TypedEstimatesResponse;

// 사진을 보고 음식과 칼로리를 추정하는 기능의 "규격" (ImageStorage와 같은 방식)
// AI 공급자를 바꿀 때 구현 클래스만 새로 만들면 되고, 이 규격을 쓰는 코드는 그대로 둘 수 있다.
public interface FoodAnalyzer {

	// jpeg: 위치 정보(EXIF)를 지운 사진. 분석하지 못하면 FoodAnalysisException을 던진다.
	FoodAnalyzerResponse analyze(byte[] jpeg);

	// 음식마다 후보 여러 개를 낸다 ("내가 보고 확정"을 고른 글, JM-74). 분석하지 못하면 FoodAnalysisException을 던진다.
	FoodCandidatesResponse analyzeCandidates(byte[] jpeg);

	// 사용자가 직접 적은 음식 이름마다, 사진과 이름을 함께 보고 100g당 영양을 추정한다 (JM-74).
	// names: 직접 적은 이름들 (한 줄짜리 글자). 답은 names와 같은 순서로 온다. 분석하지 못하면 FoodAnalysisException을 던진다.
	TypedEstimatesResponse estimateTyped(byte[] jpeg, List<String> names);
}
