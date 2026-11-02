package backend.analysis.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import backend.analysis.model.dto.AnalysisStatusResponse;
import backend.analysis.service.FoodAnalysisService;
import lombok.RequiredArgsConstructor;

// @RestController: 화면(JSP)이 아니라 값(JSON)을 돌려준다. 에러도 JSON으로 나간다. (ApiExceptionHandler)
@RestController
@RequiredArgsConstructor
public class FoodAnalysisApiController {

	private final FoodAnalysisService foodAnalysisService;

	// 분석이 끝났는지 알려 준다. 상세 화면의 JavaScript가 2초마다 부른다. (detail.jsp)
	@GetMapping("/api/posts/{id}/analysis")
	public AnalysisStatusResponse status(@PathVariable Long id) {
		return foodAnalysisService.getStatus(id);
	}
}
