package backend.analysis.model.entity;

// 글의 음식 태그 (JM-83, 피드백 11번과 13번의 "음식 카테고리"). 분석할 때 AI가 사진 전체를 보고 고른다.
// 묶음(group)이 여섯이고, 한 글은 묶음마다 하나(종류, 나라, 맵기, 온도) 또는 여럿(주식, 주재료)을 가진다.
// 칼로리 구간은 AI가 고르지 않고 이미 있는 칼로리 숫자로 나누므로 여기에 없다.
//
// 이름(MEAL, KOREAN ...)이 그대로 post_tags.tag 칸에 저장된다 (@Enumerated(STRING)). 이름을 바꾸면 저장된 값과 어긋나므로 바꾸지 않는다.
// 새 값은 더해도 된다 (enum 칸에 CHECK 제약이 없다, NoColumnCheckMySQLDialect).
// AI가 답하는 값(code)은 묶음 안에서만 겹치지 않으면 되지만, 저장하는 이름은 모든 묶음에서 겹치지 않아야 한다 (예: 나라의 기타는 OTHER_CUISINE)
public enum FoodTag {

	// 종류: 끼니가 되는 식사, 간식이나 디저트, 음료만 있는 사진
	MEAL(Group.KIND, "MEAL", "식사"),
	SNACK(Group.KIND, "SNACK", "간식"),
	DRINK(Group.KIND, "DRINK", "음료"),

	// 나라: 주된 음식의 나라. 아시아는 한, 중, 일을 뺀 아시아(베트남 쌀국수, 태국, 인도 커리 등). 기타는 어느 나라라 하기 어려운 것(과일, 생수, 과자)
	KOREAN(Group.CUISINE, "KOREAN", "한식"),
	CHINESE(Group.CUISINE, "CHINESE", "중식"),
	JAPANESE(Group.CUISINE, "JAPANESE", "일식"),
	WESTERN(Group.CUISINE, "WESTERN", "양식"),
	ASIAN(Group.CUISINE, "ASIAN", "아시아"),
	OTHER_CUISINE(Group.CUISINE, "OTHER", "기타"),

	// 주식: 사진에 보이는 것 모두 (라면과 김밥 → 면, 밥)
	RICE(Group.STAPLE, "RICE", "밥"),
	BREAD(Group.STAPLE, "BREAD", "빵"),
	NOODLE(Group.STAPLE, "NOODLE", "면"),
	RICE_CAKE(Group.STAPLE, "RICE_CAKE", "떡"),

	// 주재료: 주된 재료 모두. 반찬으로 조금 있는 재료는 넣지 않는다
	MEAT(Group.INGREDIENT, "MEAT", "고기"),
	SEAFOOD(Group.INGREDIENT, "SEAFOOD", "해산물"),
	VEGETABLE(Group.INGREDIENT, "VEGETABLE", "채소"),

	// 맵기: 주된 음식이 매운지 (AI는 참, 거짓으로 답한다)
	SPICY(Group.SPICY, "true", "매움"),
	NOT_SPICY(Group.SPICY, "false", "안 매움"),

	// 온도: 주된 음식을 따뜻하게 먹는지 차갑게 먹는지. 빵, 과일, 김밥처럼 어느 쪽도 아닌 음식은 AI가 NEUTRAL로 답하고 태그를 두지 않는다
	HOT(Group.TEMPERATURE, "HOT", "따뜻함"),
	COLD(Group.TEMPERATURE, "COLD", "차가움");

	// 묶음. 피드에서 같은 묶음 안의 태그를 여럿 고르면 "둘 중 하나라도", 다른 묶음끼리는 "모두"로 거른다 (② 화면)
	public enum Group {
		KIND, CUISINE, STAPLE, INGREDIENT, SPICY, TEMPERATURE
	}

	private final Group group;
	// AI가 답하는 값 (GeminiFoodAnalyzer의 TAGS_SCHEMA에 적힌 값)
	private final String code;
	// 화면에 보이는 이름
	private final String label;

	FoodTag(Group group, String code, String label) {
		this.group = group;
		this.code = code;
		this.label = label;
	}

	public Group getGroup() {
		return group;
	}

	public String getCode() {
		return code;
	}

	public String getLabel() {
		return label;
	}

	// AI가 답한 값을 태그로 바꾼다. 그 묶음에 없는 값이면 null (모르는 값은 버린다)
	public static FoodTag of(Group group, String code) {
		if (code == null) {
			return null;
		}
		for (FoodTag tag : values()) {
			if (tag.group == group && tag.code.equals(code)) {
				return tag;
			}
		}
		return null;
	}
}
