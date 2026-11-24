package backend.post.model.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import backend.analysis.model.entity.FoodTag;

// 주소의 필터 값을 읽는 규칙 (JM-83). 주소는 누구나 고칠 수 있으므로 모르는 값은 버린다
class FeedFilterTest {

	@Test
	void 태그_이름과_칼로리_범위를_읽는다() {
		FeedFilter filter = FeedFilter.of(List.of("KOREAN", "SPICY"), "400", "800");

		assertThat(filter.tags()).containsExactly(FoodTag.KOREAN, FoodTag.SPICY);
		assertThat(filter.kcalMin()).isEqualTo(400);
		assertThat(filter.kcalMax()).isEqualTo(800);
		assertThat(filter.count()).isEqualTo(3);
		assertThat(filter.query()).isEqualTo("tag=KOREAN&tag=SPICY&kcalMin=400&kcalMax=800");
		assertThat(filter.labels()).containsExactly("한식", "매움", "400 ~ 800 kcal");
	}

	@Test
	void 모르는_태그와_200의_배수가_아니거나_범위_밖의_칼로리는_버린다() {
		FeedFilter filter = FeedFilter.of(List.of("PIZZA", "korean", "", "KOREAN"), "450", "-200");

		assertThat(filter.tags()).containsExactly(FoodTag.KOREAN);
		assertThat(filter.hasKcal()).isFalse();
		assertThat(FeedFilter.of(null, "3200", "abc").isEmpty()).isTrue();
		assertThat(FeedFilter.of(null, "00400", "99999").isEmpty()).isTrue();
		assertThat(FeedFilter.of(null, null, null)).isEqualTo(FeedFilter.NONE);
	}

	@Test
	void 최소_0은_끝이_없는_것으로_보고_최소가_최대보다_크면_바꾼다() {
		assertThat(FeedFilter.of(null, "0", "600").kcalMin()).isNull();
		assertThat(FeedFilter.of(null, "0", "600").labels()).containsExactly("600 kcal 미만");

		FeedFilter swapped = FeedFilter.of(null, "1000", "400");
		assertThat(swapped.kcalMin()).isEqualTo(400);
		assertThat(swapped.kcalMax()).isEqualTo(1000);
	}

	// 최소와 최대를 같게 고르면 그 숫자부터 200 칸 하나. 3000이면 3000 이상 (운영자 결정 11-24: 3000까지 200 단위, 그 위는 한 칸)
	@Test
	void 최소와_최대가_같으면_200_칸_하나이고_3000은_그_이상이다() {
		FeedFilter one = FeedFilter.of(null, "600", "600");
		assertThat(one.kcalMin()).isEqualTo(600);
		assertThat(one.kcalMax()).isEqualTo(800);

		FeedFilter top = FeedFilter.of(null, "3000", "3000");
		assertThat(top.kcalMin()).isEqualTo(3000);
		assertThat(top.kcalMax()).isNull();
		assertThat(top.labels()).containsExactly("3000 kcal 이상");
	}

	@Test
	void 같은_묶음의_태그는_한데_모은다() {
		FeedFilter filter = FeedFilter.of(List.of("KOREAN", "CHINESE", "SPICY"), null, null);

		assertThat(filter.byGroup()).hasSize(2);
		assertThat(filter.byGroup().get(FoodTag.Group.CUISINE)).containsExactly(FoodTag.KOREAN, FoodTag.CHINESE);
	}

	@Test
	void 필터_창의_재료는_고른_칩과_칼로리_칸을_표시한다() {
		FilterView view = FilterView.of(FeedFilter.of(List.of("RICE"), "400", null));

		assertThat(view.groups()).extracting(FilterView.ChipGroup::label).containsExactly("종류", "나라", "주식", "주재료", "맵기", "온도");
		assertThat(view.groups().get(2).chips()).filteredOn(FilterView.Chip::checked).extracting(FilterView.Chip::value).containsExactly("RICE");
		assertThat(view.minOptions()).hasSize(16).filteredOn(FilterView.Option::selected).extracting(FilterView.Option::value).containsExactly("400");
		// 최대는 200 ~ 3000과 "제한 없음"(빈 값). 고르지 않았으므로 제한 없음
		assertThat(view.maxOptions()).hasSize(16).last().isEqualTo(new FilterView.Option("", "제한 없음", true));
		assertThat(view.count()).isEqualTo(2);
	}
}
