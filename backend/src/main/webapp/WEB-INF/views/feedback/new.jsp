<%-- 피드백 작성 화면 --%>
<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="form" uri="http://www.springframework.org/tags/form" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>피드백 남기기 - 찍먹</title>
<link rel="stylesheet" href="<c:url value='/css/app.css' />">
</head>
<body>
<main>
	<header class="top">
		<a href="/feedback">← 피드백 목록</a>
		<h1>피드백 남기기</h1>
	</header>

	<%-- 글쓰기 화면(posts/new.jsp)과 같은 방식: form 태그로 입력칸을 form 객체와 연결하고 검사 메시지를 칸마다 보여 준다 --%>
	<form:form modelAttribute="form" action="/feedback" method="post" cssClass="narrow">
		<%-- 공개 게시판이라 개인정보를 적지 말라고 먼저 알린다 (기획서 5절) --%>
		<p class="hint">누구나 볼 수 있는 게시판이에요. 연락처 같은 개인정보는 적지 말아 주세요.</p>
		<p class="hint">글이나 댓글의 비밀번호를 잊었다면 여기에 삭제를 요청해 주세요. 운영자가 확인하고 처리해요.</p>

		<label for="title">제목</label>
		<form:input path="title" maxlength="100" />
		<form:errors path="title" cssClass="error" element="p" />

		<label for="content">내용</label>
		<form:textarea path="content" maxlength="2000" rows="8" />
		<form:errors path="content" cssClass="error" element="p" />

		<label for="nickname">닉네임</label>
		<form:input path="nickname" maxlength="20" />
		<form:errors path="nickname" cssClass="error" element="p" />

		<label for="password">비밀번호</label>
		<form:password path="password" maxlength="20" />
		<p class="hint">글을 지울 때 필요해요. 잊으면 직접 지울 수 없어요.</p>
		<form:errors path="password" cssClass="error" element="p" />

		<button type="submit">남기기</button>
	</form:form>

	<%@ include file="/WEB-INF/views/common/footer.jspf" %>
</main>

<script>
	// 연속으로 누르면 같은 글이 여러 개 올라가므로, 첫 번째 누름에서 버튼을 막는다 (posts/new.jsp와 같음)
	const form = document.querySelector('form');
	const button = form.querySelector('button[type="submit"]');

	form.addEventListener('submit', function () {
		button.disabled = true;
		button.textContent = '남기는 중…';
	});

	window.addEventListener('pageshow', function () {
		button.disabled = false;
		button.textContent = '남기기';
	});
</script>
</body>
</html>
