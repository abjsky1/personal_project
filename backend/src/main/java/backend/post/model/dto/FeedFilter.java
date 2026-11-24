package backend.post.model.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import backend.analysis.model.entity.FoodTag;
import backend.analysis.model.entity.FoodTag.Group;

// 피드와 검색의 음식 필터 (JM-83, 피드백 11번과 13번). 주소의 ?tag=KOREAN&tag=SPICY&kcalMin=400&kcalMax=800 을 읽은 값이다.
// - tags: 고른 태그 (AI가 글마다 고른 것, post_tags). 같은 묶음 안에서 여럿 고르면 "하나라도", 다른 묶음끼리는 "모두"
// - kcalMin, kcalMax: 칼로리 범위 (보이는 칼로리가 kcalMin 이상 kcalMax 미만). 비어 있으면 그쪽 끝이 없다.
//   200 단위로 0부터 3000까지 고른다 (운영자 결정 11-24: 200 단위로 3000까지, 그 위는 한 칸). 최소 3000에 최대 없음이 "3000 이상"
// 모르는 값은 버린다 (주소는 누구나 고칠 수 있다). 그래서 이 값으로 만드는 SQL에는 정해 둔 이름과 숫자만 들어간다
public record FeedFilter(Set<FoodTag> tags, Integer kcalMin, Integer kcalMax) {

	public static final int KCAL_STEP = 200;
	public static final int KCAL_TOP = 3000;

	public static final FeedFilter NONE = new FeedFilter(Set.of(), null, null);

	public FeedFilter {
		tags = Collections.unmodifiableSet(tags.isEmpty() ? EnumSet.noneOf(FoodTag.class) : EnumSet.copyOf(tags));
	}

	// 주소의 값들을 읽는다. 없는 태그 이름, 200의 배수가 아니거나 범위 밖의 숫자는 버린다.
	// 최소 0은 "끝 없음"과 같아서 버리고, 최소가 최대보다 크면 둘을 바꾼다
	public static FeedFilter of(List<String> tagParams, String kcalMinParam, String kcalMaxParam) {
		Set<FoodTag> tags = EnumSet.noneOf(FoodTag.class);
		if (tagParams != null) {
			for (String value : tagParams) {
				for (FoodTag tag : FoodTag.values()) {
					if (tag.name().equals(value)) {
						tags.add(tag);
					}
				}
			}
		}
		Integer min = parseKcal(kcalMinParam);
		Integer max = parseKcal(kcalMaxParam);
		if (min != null && min == 0) {
			min = null;
		}
		if (max != null && max == 0) {
			max = null;
		}
		if (min != null && max != null && min > max) {
			Integer swap = min;
			min = max;
			max = swap;
		}
		// 최소와 최대를 같게 고르면 그 숫자부터 200 칸 하나로 본다 (3000이면 3000 이상)
		if (min != null && min.equals(max)) {
			max = min >= KCAL_TOP ? null : min + KCAL_STEP;
		}
		return new FeedFilter(tags, min, max);
	}

	private static Integer parseKcal(String value) {
		if (value == null || !value.matches("\\d{1,4}")) {
			return null;
		}
		int kcal = Integer.parseInt(value);
		return kcal <= KCAL_TOP && kcal % KCAL_STEP == 0 ? kcal : null;
	}

	public boolean hasKcal() {
		return kcalMin != null || kcalMax != null;
	}

	public boolean isEmpty() {
		return tags.isEmpty() && !hasKcal();
	}

	// 고른 조건의 수 (필터 버튼에 "필터 3"으로 보인다). 칼로리 범위는 하나로 센다
	public int count() {
		return tags.size() + (hasKcal() ? 1 : 0);
	}

	// 고른 태그를 묶음별로 (묶음마다 SQL 조건 하나)
	public Map<Group, Set<FoodTag>> byGroup() {
		Map<Group, Set<FoodTag>> groups = new EnumMap<>(Group.class);
		for (FoodTag tag : tags) {
			groups.computeIfAbsent(tag.getGroup(), group -> EnumSet.noneOf(FoodTag.class)).add(tag);
		}
		return groups;
	}

	// 주소 뒤에 붙일 값: tag=KOREAN&tag=SPICY&kcalMin=400 (없으면 빈 글자). 태그 이름과 숫자뿐이라 URL 글자로 바꿀 것이 없다
	public String query() {
		List<String> parts = new ArrayList<>();
		tags.forEach(tag -> parts.add("tag=" + tag.name()));
		if (kcalMin != null) {
			parts.add("kcalMin=" + kcalMin);
		}
		if (kcalMax != null) {
			parts.add("kcalMax=" + kcalMax);
		}
		return String.join("&", parts);
	}

	// 고른 것의 이름들 (결과 줄): 한식, 매움, 400 ~ 800 kcal
	public List<String> labels() {
		List<String> labels = new ArrayList<>();
		tags.forEach(tag -> labels.add(tag.getLabel()));
		if (hasKcal()) {
			labels.add(kcalLabel());
		}
		return labels;
	}

	private String kcalLabel() {
		if (kcalMin != null && kcalMax != null) {
			return kcalMin + " ~ " + kcalMax + " kcal";
		}
		return kcalMin != null ? kcalMin + " kcal 이상" : kcalMax + " kcal 미만";
	}
}
