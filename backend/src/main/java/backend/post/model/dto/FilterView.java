package backend.post.model.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import backend.analysis.model.entity.FoodTag;
import backend.analysis.model.entity.FoodTag.Group;

// 필터 창을 그리는 재료 (JM-83). 화면은 posts/list.jsp의 필터 창(dialog)이 그린다.
// 검색 칸의 [필터] 버튼을 누르면 뜨는 창에 묶음마다 칩(체크 상자)과 칼로리 범위(최소, 최대 고르는 칸)가 있고, [적용]을 누르면 한 번에 거른다
// - groups: 종류, 나라, 주식, 주재료, 맵기, 온도 (칩마다 태그 이름과 지금 골랐는지)
// - minOptions, maxOptions: 칼로리 범위의 고르는 칸. 값이 빈 것이 "끝 없음"(최소 0, 최대 제한 없음)
// - count: 고른 조건 수 (버튼에 "필터 2"), labels: 고른 것의 이름 (결과 줄)
public record FilterView(List<ChipGroup> groups, List<Option> minOptions, List<Option> maxOptions, int count, List<String> labels) {

	public record Chip(String label, String value, boolean checked) {
	}

	public record ChipGroup(String label, List<Chip> chips) {
	}

	public record Option(String value, String label, boolean selected) {
	}

	public static FilterView of(FeedFilter filter) {
		List<ChipGroup> groups = List.of(
				group("종류", Group.KIND, filter),
				group("나라", Group.CUISINE, filter),
				group("주식", Group.STAPLE, filter),
				group("주재료", Group.INGREDIENT, filter),
				group("맵기", Group.SPICY, filter),
				group("온도", Group.TEMPERATURE, filter));

		// 최소: 0(끝 없음), 200, ... 3000. 최대: 200, ... 3000, 제한 없음
		List<Option> min = new ArrayList<>();
		min.add(new Option("", "0", filter.kcalMin() == null));
		steps(FeedFilter.KCAL_STEP, FeedFilter.KCAL_TOP)
				.forEach(kcal -> min.add(new Option(String.valueOf(kcal), String.valueOf(kcal), Integer.valueOf(kcal).equals(filter.kcalMin()))));
		List<Option> max = new ArrayList<>();
		steps(FeedFilter.KCAL_STEP, FeedFilter.KCAL_TOP)
				.forEach(kcal -> max.add(new Option(String.valueOf(kcal), String.valueOf(kcal), Integer.valueOf(kcal).equals(filter.kcalMax()))));
		max.add(new Option("", "제한 없음", filter.kcalMax() == null));

		return new FilterView(groups, min, max, filter.count(), filter.labels());
	}

	private static List<Integer> steps(int from, int to) {
		return IntStream.rangeClosed(from / FeedFilter.KCAL_STEP, to / FeedFilter.KCAL_STEP)
				.map(i -> i * FeedFilter.KCAL_STEP).boxed().toList();
	}

	private static ChipGroup group(String label, Group group, FeedFilter filter) {
		List<Chip> chips = new ArrayList<>();
		for (FoodTag tag : FoodTag.values()) {
			if (tag.getGroup() == group) {
				chips.add(new Chip(tag.getLabel(), tag.name(), filter.tags().contains(tag)));
			}
		}
		return new ChipGroup(label, chips);
	}
}
