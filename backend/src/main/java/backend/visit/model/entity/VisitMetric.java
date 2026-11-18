package backend.visit.model.entity;

// 시/도별로 세는 것 (JM-86)
// VISIT: 사람이 사이트에 들어온 횟수. 브라우저가 화면을 처음 받을 때 한 번 (30분 넘게 쉬었다가 오면 다시 한 번)
// UPLOAD: 글을 올린 횟수
public enum VisitMetric {
	VISIT, UPLOAD
}
