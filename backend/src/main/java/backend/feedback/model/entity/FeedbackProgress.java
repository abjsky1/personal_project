// 피드백 도메인의 엔티티와 상태값을 두는 패키지
package backend.feedback.model.entity;

// 피드백 처리 상태 (기획서 12절). 삭제 여부(status)와는 별개로, 운영자가 어디까지 처리했는지를 나타낸다.
// label: 화면에 보여 줄 한국어 / cssClass: 배지 색을 정하는 CSS 클래스 이름 (app.css의 .badge-*)
public enum FeedbackProgress {
	// 글을 올리면 이 상태로 시작
	RECEIVED("접수", "badge-received"),
	IN_PROGRESS("진행 중", "badge-in-progress"),
	DONE("반영 완료", "badge-done"),
	ON_HOLD("보류", "badge-on-hold");

	private final String label;
	private final String cssClass;

	FeedbackProgress(String label, String cssClass) {
		this.label = label;
		this.cssClass = cssClass;
	}

	public String getLabel() {
		return label;
	}

	public String getCssClass() {
		return cssClass;
	}
}
