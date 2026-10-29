<%-- 글쓰기 화면 --%>
<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%-- Spring의 form 태그: 입력칸을 Controller가 넘긴 form 객체와 연결하고, 검사 메시지를 칸마다 보여 준다. --%>
<%@ taglib prefix="form" uri="http://www.springframework.org/tags/form" %>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>사진 올리기</title>
<link rel="stylesheet" href="/css/app.css">
</head>
<body>
<main>
	<header class="top">
		<a href="/">← 목록</a>
		<h1>사진 올리기</h1>
	</header>

	<%-- modelAttribute="form": Controller가 Model에 넣은 "form" 객체와 이 form을 연결한다. --%>
	<%-- enctype="multipart/form-data": 파일을 보낼 때 꼭 필요한 전송 방식 (없으면 파일 이름만 가고 내용은 안 간다) --%>
	<form:form modelAttribute="form" action="/posts" method="post" enctype="multipart/form-data">

		<%-- 휴대폰에서 누르면 "사진 찍기 / 앨범에서 고르기"를 고를 수 있다. --%>
		<%-- accept: 고를 수 있는 파일 형식. 아이폰은 이 설정을 보고 HEIC 사진을 JPEG로 바꿔서 올린다. --%>
		<label for="image">사진</label>
		<input type="file" id="image" name="image" accept="image/jpeg,image/png">
		<%-- form:errors: 이 칸에 검사 메시지가 있을 때만 <p class="error">메시지</p>를 만든다. --%>
		<form:errors path="image" cssClass="error" element="p" />

		<%-- form:input path="nickname": name="nickname"인 입력칸. 검사에 걸려 화면이 다시 나올 때 입력했던 값이 그대로 채워진다. --%>
		<label for="nickname">닉네임</label>
		<form:input path="nickname" maxlength="20" />
		<form:errors path="nickname" cssClass="error" element="p" />

		<%-- form:password: 보안을 위해 화면이 다시 나올 때 값을 채우지 않는다. --%>
		<label for="password">비밀번호</label>
		<form:password path="password" maxlength="20" />
		<p class="hint">글을 지울 때 필요해요. 잊으면 직접 지울 수 없어요.</p>
		<form:errors path="password" cssClass="error" element="p" />

		<label for="memo">메모 (선택)</label>
		<form:textarea path="memo" maxlength="300" rows="3" />
		<form:errors path="memo" cssClass="error" element="p" />

		<button type="submit">올리기</button>
	</form:form>
</main>

<script>
	// 10MB가 넘는 사진은 보내기 전에 막는다.
	// 서버도 10MB에서 거절하지만, 큰 파일을 끝까지 다 보낸 뒤에 거절당하면 그동안 기다려야 하므로 먼저 확인한다.
	document.querySelector('form').addEventListener('submit', function (event) {
		const file = document.getElementById('image').files[0];
		if (file && file.size > 10 * 1024 * 1024) {
			// preventDefault(): form 제출을 멈춘다.
			event.preventDefault();
			alert('사진은 10MB까지 올릴 수 있어요.');
		}
	});
</script>
</body>
</html>
