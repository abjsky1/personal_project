package backend.post.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

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

	// 가짜 사진 파일: (form의 칸 이름, 파일 이름, 형식, 내용)
	// 1번 조각에서는 사진 내용을 검사하지 않아서 아무 바이트나 넣어도 된다. (3번 조각에서 실제 사진 검사가 추가된다)
	private final MockMultipartFile photo = new MockMultipartFile("image", "food.jpg", "image/jpeg", new byte[] {1, 2, 3});

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
	void JPEG나_PNG가_아닌_파일은_거절한다() throws Exception {
		MockMultipartFile gif = new MockMultipartFile("image", "food.gif", "image/gif", new byte[] {1, 2, 3});

		mockMvc.perform(multipart("/posts").file(gif)
				.param("nickname", "테스터")
				.param("password", "1234"))
				.andExpect(view().name("posts/new"))
				.andExpect(model().attributeHasFieldErrors("form", "image"));
	}
}
