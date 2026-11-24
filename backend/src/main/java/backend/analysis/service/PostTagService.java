package backend.analysis.service;

import java.time.Instant;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import backend.analysis.model.entity.FoodTag;
import backend.analysis.model.entity.PostTag;
import backend.analysis.model.repository.FoodAnalysisRepository;
import backend.analysis.model.repository.PostTagRepository;
import lombok.RequiredArgsConstructor;

// 글의 음식 태그를 저장한다 (JM-83). 분석이 끝났을 때(FoodAnalysisService)와 지난 글을 채울 때(PostTagFiller)가 함께 쓴다
@Service
@RequiredArgsConstructor
public class PostTagService {

	private final PostTagRepository postTagRepository;
	private final FoodAnalysisRepository foodAnalysisRepository;

	// 한 글의 태그를 통째로 바꿔 쓴다: 있던 태그를 지우고, 새 태그를 넣고, 분석 기록에 "태그를 채웠음"(tagged_at)을 적는다.
	// @Transactional: 셋이 한꺼번에 되거나 한꺼번에 안 된다. 중간에 실패하면 태그가 없는 글로 남고, 태그 채우기가 다시 채운다
	@Transactional
	public void replace(Long postId, Set<FoodTag> tags) {
		Instant now = Instant.now();
		postTagRepository.deleteByPostId(postId);
		postTagRepository.saveAll(tags.stream().map(tag -> new PostTag(postId, tag, now)).toList());
		foodAnalysisRepository.markTagged(postId, now);
	}
}
