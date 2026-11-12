package backend.analysis.model.entity;

// 칼로리를 누가 확정하는지 (JM-74, 고쳐 쓰는 AI). 글을 올릴 때 고른다.
public enum ConfirmMode {

	// AI가 바로 확정: AI의 답이 그대로 결과다. 처음부터 있던 방식이고 기본값이다.
	AUTO,

	// 내가 보고 확정: AI가 음식마다 후보를 내고, 글쓴이가 고르고 고쳐서 확정한다.
	USER
}
