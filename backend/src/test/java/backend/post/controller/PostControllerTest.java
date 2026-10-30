package backend.post.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import backend.common.error.ErrorCode;
import backend.image.TestImages;
import backend.location.model.entity.PostLocation;
import backend.location.model.repository.PostLocationRepository;
import backend.post.model.entity.Post;
import backend.post.model.entity.PostStatus;
import backend.post.model.repository.PostRepository;

// 글쓰기 요청을 실제로 보내 보고, 저장과 입력 검사가 제대로 되는지 확인하는 테스트
//
// @Transactional: 테스트 하나가 끝나면 그 테스트에서 DB에 저장한 내용을 모두 되돌린다.
// → 로컬에서 돌려도 내 개발 DB에 테스트 글이 쌓이지 않는다.
// @TestPropertySource: 이 테스트에서만 설정을 바꾼다. 테스트로 올린 사진은 uploads/ 대신 build/test-uploads/에 저장한다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.upload-dir=build/test-uploads")
class PostControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private PostLocationRepository postLocationRepository;

	// 테스트용 사진 파일: (form의 칸 이름, 파일 이름, 형식, 내용)
	// 서버가 파일 내용을 진짜 사진인지 검사하므로, 코드로 만든 실제 JPEG(300 × 200)를 넣는다.
	private final MockMultipartFile photo = new MockMultipartFile("image", "food.jpg", "image/jpeg", TestImages.jpeg(300, 200));

	// GPS(서울시청 근처)와 촬영 시각(한국 시간 2026-10-29 12:30)이 들어 있는 사진
	private final MockMultipartFile photoWithGps = new MockMultipartFile("image", "food.jpg", "image/jpeg",
			TestImages.withGps(TestImages.jpeg(300, 200), 37.566535, 126.977969, "2026:10:29 12:30:00"));

	@Test
	void 위치_제공에_동의하면_위치와_촬영_시각을_저장한다() throws Exception {
		mockMvc.perform(multipart("/posts").file(photoWithGps)
				.param("nickname", "테스터")
				.param("password", "1234")
				.param("locationConsent", "true"))
				.andExpect(status().is3xxRedirection());

		Post saved = latestPost();
		assertThat(saved.getPhotoTakenAt()).isEqualTo(Instant.parse("2026-10-29T03:30:00Z"));

		PostLocation location = postLocationRepository.findById(saved.getId()).orElseThrow();
		assertThat(location.getPoint().latitude()).isEqualByComparingTo("37.566535");
		assertThat(location.getPoint().longitude()).isEqualByComparingTo("126.977969");
		assertThat(location.getSource()).isEqualTo("EXIF");
	}

	@Test
	void 동의하지_않으면_사진에_위치가_있어도_저장하지_않는다() throws Exception {
		// locationConsent를 보내지 않음 = 체크박스를 체크하지 않음
		mockMvc.perform(multipart("/posts").file(photoWithGps)
				.param("nickname", "테스터")
				.param("password", "1234"))
				.andExpect(status().is3xxRedirection());

		Post saved = latestPost();
		assertThat(saved.getPhotoTakenAt()).isNull();
		assertThat(postLocationRepository.findById(saved.getId())).isEmpty();
	}

	@Test
	void 동의했지만_사진에_위치가_없으면_글만_저장한다() throws Exception {
		mockMvc.perform(multipart("/posts").file(photo)
				.param("nickname", "테스터")
				.param("password", "1234")
				.param("locationConsent", "true"))
				.andExpect(status().is3xxRedirection());

		assertThat(postLocationRepository.findById(latestPost().getId())).isEmpty();
	}

	@Test
	void 글을_삭제하면_위치는_완전히_지운다() throws Exception {
		mockMvc.perform(multipart("/posts").file(photoWithGps)
				.param("nickname", "테스터")
				.param("password", "1234")
				.param("locationConsent", "true"));
		Long postId = latestPost().getId();
		assertThat(postLocationRepository.findById(postId)).isPresent();

		mockMvc.perform(post("/posts/" + postId + "/delete").param("password", "1234"))
				.andExpect(redirectedUrl("/"));

		// 게시글은 상태만 바뀌어 남아 있지만, 위치 행은 아예 없어야 한다.
		assertThat(postRepository.findById(postId)).isPresent();
		assertThat(postLocationRepository.findById(postId)).isEmpty();
	}

	// 가장 최근에 저장된 글(번호가 가장 큰 글)
	private Post latestPost() {
		return postRepository.findAll(Sort.by(Sort.Direction.DESC, "id")).get(0);
	}

	@Test
	void 글을_올리면_저장되고_목록으로_이동한다() throws Exception {
		// multipart(): 파일이 포함된 form 전송을 흉내 낸다. param(): 글자 칸의 값
		mockMvc.perform(multipart("/posts").file(photo)
				.param("nickname", "테스터")
				.param("password", "1234")
				.param("memo", "점심"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/"));

		// 가장 최근에 저장된 글(번호가 가장 큰 글)을 꺼내 확인한다.
		Post saved = postRepository.findAll(Sort.by(Sort.Direction.DESC, "id")).get(0);
		assertThat(saved.getNickname()).isEqualTo("테스터");
		assertThat(saved.getMemo()).isEqualTo("점심");
		assertThat(saved.getStatus()).isEqualTo(PostStatus.ACTIVE);
		// 비밀번호는 원문 그대로가 아니라 해시로 저장되고, 원문과 맞춰 볼 수는 있어야 한다.
		assertThat(saved.getPasswordHash()).isNotEqualTo("1234");
		// 큰 사진과 썸네일 파일이 실제로 저장돼 있어야 한다.
		assertThat(Files.exists(Path.of("build/test-uploads", saved.getImageKey()))).isTrue();
		assertThat(Files.exists(Path.of("build/test-uploads", saved.getThumbnailKey()))).isTrue();
		assertThat(passwordEncoder.matches("1234", saved.getPasswordHash())).isTrue();
	}

	@Test
	void 사진_없이_올리면_글쓰기_화면으로_돌아가고_사진_칸에_메시지가_나온다() throws Exception {
		mockMvc.perform(multipart("/posts")
				.param("nickname", "테스터")
				.param("password", "1234"))
				.andExpect(status().isOk())
				.andExpect(view().name("posts/new"))
				// "form" 객체의 image 칸에 검사 메시지가 있어야 한다.
				.andExpect(model().attributeHasFieldErrors("form", "image"));
	}

	@Test
	void 비밀번호가_4자보다_짧으면_글쓰기_화면으로_돌아간다() throws Exception {
		mockMvc.perform(multipart("/posts").file(photo)
				.param("nickname", "테스터")
				.param("password", "123"))
				.andExpect(view().name("posts/new"))
				.andExpect(model().attributeHasFieldErrors("form", "password"));
	}

	@Test
	void 상세_화면은_글을_보여준다() throws Exception {
		Post post = savePost("1234");

		mockMvc.perform(get("/posts/" + post.getId()))
				.andExpect(status().isOk())
				.andExpect(view().name("posts/detail"))
				.andExpect(model().attributeExists("post"));
	}

	@Test
	void 없는_글의_상세는_404_에러_화면을_보여준다() throws Exception {
		mockMvc.perform(get("/posts/999999999"))
				.andExpect(status().isNotFound())
				.andExpect(view().name("common/error"));
	}

	@Test
	void 비밀번호가_맞으면_상태만_바꿔_삭제하고_그_글은_더_볼_수_없다() throws Exception {
		Post post = savePost("1234");

		mockMvc.perform(post("/posts/" + post.getId() + "/delete").param("password", "1234"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/"));

		// DB에서 지워지지 않고, 상태와 삭제 시각만 바뀌어 있어야 한다.
		Post deleted = postRepository.findById(post.getId()).orElseThrow();
		assertThat(deleted.getStatus()).isEqualTo(PostStatus.DELETED_BY_AUTHOR);
		assertThat(deleted.getDeletedAt()).isNotNull();

		// 삭제된 글의 상세는 404
		mockMvc.perform(get("/posts/" + post.getId()))
				.andExpect(status().isNotFound());
	}

	@Test
	void 비밀번호가_틀리면_삭제하지_않고_상세_화면으로_돌아가_메시지를_보여준다() throws Exception {
		Post post = savePost("1234");

		mockMvc.perform(post("/posts/" + post.getId() + "/delete").param("password", "0000"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/posts/" + post.getId()))
				// flash: redirect한 다음 화면에 한 번만 넘어가는 값
				.andExpect(flash().attribute("deleteError", ErrorCode.INVALID_PASSWORD.getMessage()));

		assertThat(postRepository.findById(post.getId()).orElseThrow().getStatus()).isEqualTo(PostStatus.ACTIVE);
	}

	// 테스트용 글을 DB에 바로 저장한다. (사진 파일은 만들지 않고 이름만 넣는다)
	private Post savePost(String password) {
		return postRepository.save(new Post("테스터", passwordEncoder.encode(password), null, "test.jpg", "test-thumb.jpg", null));
	}

	@Test
	void 사진이_아닌_파일은_글쓰기_화면의_사진_칸에_메시지를_보여준다() throws Exception {
		// 이름과 형식은 JPEG처럼 꾸몄지만 내용은 사진이 아닌 파일 → 파일 내용 검사(매직 바이트)에서 걸린다.
		MockMultipartFile fake = new MockMultipartFile("image", "food.jpg", "image/jpeg", "not an image".getBytes());

		mockMvc.perform(multipart("/posts").file(fake)
				.param("nickname", "테스터")
				.param("password", "1234"))
				.andExpect(status().isOk())
				.andExpect(view().name("posts/new"))
				.andExpect(model().attributeHasFieldErrorCode("form", "image", ErrorCode.INVALID_IMAGE.name()));
	}
}
