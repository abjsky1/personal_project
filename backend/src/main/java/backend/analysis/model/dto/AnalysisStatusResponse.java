package backend.analysis.model.dto;

import backend.analysis.model.entity.AnalysisStatus;

// 분석 상태 API의 응답. JSON으로 바뀌어 나간다: {"status":"PENDING"}
public record AnalysisStatusResponse(AnalysisStatus status) {
}
