<%-- 메모 수정 화면 (JM-53, 지인 피드백 7번): 글을 올린 사람이 메모만 고친다. 사진, 닉네임, 분석 결과는 그대로다. --%>
<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%-- Spring의 form 태그: 입력칸을 Controller가 넘긴 form 객체와 연결하고, 검사 메시지를 칸마다 보여 준다. (new.jsp와 같음) --%>
<%@ taglib prefix="form" uri="http://www.springframework.org/tags/form" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>메모 수정 - 찍먹</title>
<link rel="stylesheet" href="<c:url value='/css/app.css' />">
</head>
<body>
<main>
	<header class="top">
		<%-- 목록이 아니라 고치던 글로 돌아간다. 그래서 목록용 표시(data-back)는 붙이지 않는다. --%>
		<a href="/posts/${post.id}">← 글로 돌아가기</a>
		<h1>메모 수정</h1>
	</header>

	<%-- modelAttribute="form": Controller가 Model에 넣은 "form"(PostMemoForm)과 연결한다. 메모 칸에는 지금 메모가 채워져 있다. --%>
	<form:form modelAttribute="form" action="/posts/${post.id}/edit" method="post" cssClass="narrow">

		<%-- 어느 글을 고치는지 알 수 있게 작은 사진과 닉네임, 올린 시각을 보여 준다. --%>
		<div class="edit-post">
			<img src="${post.thumbnailUrl}" alt="음식 사진">
			<p class="meta"><strong><c:out value="${post.nickname}" /></strong><span><c:out value="${post.createdAt}" /></span></p>
		</div>

		<%-- 검사에 걸려 화면이 다시 나올 때는 고쳐 쓰던 메모가 그대로 채워진다. --%>
		<label for="memo">메모 (선택)</label>
		<form:textarea path="memo" maxlength="300" rows="4" />
		<p class="hint">사진, 닉네임, 분석 결과는 바뀌지 않아요. 메모를 비우고 저장하면 메모가 없어져요.</p>
		<form:errors path="memo" cssClass="error" element="p" />

		<%-- form:password: 보안을 위해 화면이 다시 나올 때 값을 채우지 않는다. 틀리면 이 칸 아래에 메시지가 나온다. --%>
		<label for="password">비밀번호</label>
		<form:password path="password" maxlength="20" />
		<p class="hint">글을 올릴 때 넣은 비밀번호를 입력해 주세요.</p>
		<form:errors path="password" cssClass="error" element="p" />

		<button type="submit">저장</button>
	</form:form>

	<%-- 공통 바닥글 (개인정보처리방침 링크) --%>
	<%@ include file="/WEB-INF/views/common/footer.jspf" %>
</main>

<script>
	const form = document.querySelector('form');
	const button = form.querySelector('button[type="submit"]');

	// 연속으로 눌러 같은 요청이 여러 번 가지 않게, 첫 번째 누름에서 버튼을 막는다. (new.jsp와 같음)
	form.addEventListener('submit', function () {
		button.disabled = true;
		button.textContent = '저장하는 중…';
	});

	// "뒤로 가기"로 이 화면이 되살아났을 때 버튼이 막힌 채로 남지 않게 원래대로 돌려놓는다.
	window.addEventListener('pageshow', function () {
		button.disabled = false;
		button.textContent = '저장';
	});
</script>
</body>
</html>
