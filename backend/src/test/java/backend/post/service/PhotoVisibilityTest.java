package backend.post.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import backend.analysis.model.entity.AnalysisStatus;

// 사진 주소의 기준 (JM-95): 지우지 않았고 사진을 가리지 않은 글의 사진만 보인다. 가리는 기준은 Post.isPhotoHeld와 같다 (JM-80)
class PhotoVisibilityTest {

	private static final Timestamp APPROVED = Timestamp.from(Instant.parse("2026-11-26T03:00:00Z"));

	@Test
	void 지우지_않은_글은_AI가_음식이_아니라고_보고_운영자가_올리지_않았을_때만_가린다() {
		// 분석 기록이 없는 옛날 글
		assertThat(PhotoVisibility.isPublic("ACTIVE", null, null)).isTrue();
		for (AnalysisStatus status : AnalysisStatus.values()) {
			assertThat(PhotoVisibility.isPublic("ACTIVE", null, status.name())).as(status.name())
					.isEqualTo(status != AnalysisStatus.NOT_FOOD);
			assertThat(PhotoVisibility.isPublic("ACTIVE", APPROVED, status.name())).as(status.name()).isTrue();
		}
	}

	@Test
	void 지운_글은_분석_결과와_올린_시각에_상관없이_보이지_않는다() {
		for (String deleted : new String[] { "DELETED_BY_AUTHOR", "DELETED_BY_ADMIN" }) {
			assertThat(PhotoVisibility.isPublic(deleted, null, null)).isFalse();
			assertThat(PhotoVisibility.isPublic(deleted, null, "DONE")).isFalse();
			assertThat(PhotoVisibility.isPublic(deleted, APPROVED, "NOT_FOOD")).isFalse();
		}
	}
}
