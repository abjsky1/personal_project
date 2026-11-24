package backend.analysis.model.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import backend.analysis.model.entity.FoodTag;

// AI가 답한 태그 값을 저장할 태그로 바꾸는 규칙 (JM-83)
class FoodTagsTest {

	@Test
	void 묶음마다_답한_값을_태그로_바꾼다() {
		FoodTags tags = new FoodTags("MEAL", "KOREAN", List.of("RICE", "NOODLE"), List.of("MEAT"), true, "HOT");

		assertThat(tags.toTags()).containsExactly(
				FoodTag.MEAL, FoodTag.KOREAN, FoodTag.RICE, FoodTag.NOODLE, FoodTag.MEAT, FoodTag.SPICY, FoodTag.HOT);
	}

	// 베트남 쌀국수: 아시아, 면, 안 매움, 따뜻함 (운영자 질문 11-24)
	@Test
	void 안_매운_음식과_기타_나라도_태그가_된다() {
		assertThat(new FoodTags("MEAL", "ASIAN", List.of("NOODLE"), List.of("MEAT"), false, "HOT").toTags())
				.containsExactly(FoodTag.MEAL, FoodTag.ASIAN, FoodTag.NOODLE, FoodTag.MEAT, FoodTag.NOT_SPICY, FoodTag.HOT);
		assertThat(new FoodTags("SNACK", "OTHER", List.of(), List.of(), false, "COLD").toTags())
				.containsExactly(FoodTag.SNACK, FoodTag.OTHER_CUISINE, FoodTag.NOT_SPICY, FoodTag.COLD);
	}

	// 빵, 과일처럼 따뜻하지도 차갑지도 않은 음식은 온도 태그를 두지 않는다
	@Test
	void 온도가_NEUTRAL이면_온도_태그가_없다() {
		assertThat(new FoodTags("SNACK", "WESTERN", List.of("BREAD"), List.of(), false, "NEUTRAL").toTags())
				.containsExactly(FoodTag.SNACK, FoodTag.WESTERN, FoodTag.BREAD, FoodTag.NOT_SPICY);
	}

	// 응답 형식이 값을 강제하지만, 저장하기 전에 한 번 더 거른다: 다른 묶음의 값, 모르는 값, 빈 값은 버리고 겹친 값은 하나로
	@Test
	void 모르는_값과_다른_묶음의_값과_빈_값은_버리고_겹친_값은_하나로_둔다() {
		FoodTags tags = new FoodTags("KOREAN", "PIZZA", List.of("RICE", "RICE", "MEAT"), null, null, null);

		assertThat(tags.toTags()).containsExactly(FoodTag.RICE);
		assertThat(new FoodTags(null, null, null, null, null, null).toTags()).isEmpty();
	}

	@Test
	void 저장하는_이름은_모든_묶음에서_겹치지_않고_AI의_값은_묶음_안에서_겹치지_않는다() {
		for (FoodTag tag : FoodTag.values()) {
			assertThat(FoodTag.of(tag.getGroup(), tag.getCode())).isEqualTo(tag);
		}
	}
}
