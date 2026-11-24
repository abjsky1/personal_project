package backend.analysis.model.dto;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import backend.analysis.model.entity.FoodTag;
import backend.analysis.model.entity.FoodTag.Group;

// AI가 사진 전체를 보고 고른 태그 (JM-83). 이름과 타입을 GeminiFoodAnalyzer의 TAGS_SCHEMA와 똑같이 맞춘다.
// 분석의 답(FoodAnalysisResult, FoodCandidates) 안에 함께 오고, 지난 글을 채울 때(classify)는 이것만 온다.
//
// kind, cuisine, temperature: 하나 (temperature는 HOT, COLD, NEUTRAL. NEUTRAL은 태그를 두지 않는다)
// staples, ingredients: 여럿 (없으면 빈 배열)
// spicy: 참, 거짓
public record FoodTags(String kind, String cuisine, List<String> staples, List<String> ingredients, Boolean spicy, String temperature) {

	// 저장할 태그로 바꾼다. 모르는 값과 빈 값은 버린다 (응답 형식이 값을 강제하지만, 저장 전에 한 번 더 거른다).
	// EnumSet: 같은 태그가 두 번 와도 한 번만 들고, FoodTag의 순서(종류, 나라, 주식 ...)로 늘어선다
	public Set<FoodTag> toTags() {
		Set<FoodTag> tags = EnumSet.noneOf(FoodTag.class);
		add(tags, Group.KIND, kind);
		add(tags, Group.CUISINE, cuisine);
		if (staples != null) {
			staples.forEach(code -> add(tags, Group.STAPLE, code));
		}
		if (ingredients != null) {
			ingredients.forEach(code -> add(tags, Group.INGREDIENT, code));
		}
		if (spicy != null) {
			add(tags, Group.SPICY, spicy.toString());
		}
		add(tags, Group.TEMPERATURE, temperature);
		return tags;
	}

	private static void add(Set<FoodTag> tags, Group group, String code) {
		FoodTag tag = FoodTag.of(group, code);
		if (tag != null) {
			tags.add(tag);
		}
	}
}
