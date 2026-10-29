<%-- 게시글 상세 화면 --%>
<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>음식 사진 상세</title>
<link rel="stylesheet" href="/css/app.css">
</head>
<body>
<main>
	<header class="top">
		<a href="/">← 목록</a>
	</header>

	<%-- ${post}: Controller가 Model에 넣은 글 하나 --%>
	<article class="post">
		<img src="${post.imageUrl}" alt="음식 사진">
		<p class="meta"><strong><c:out value="${post.nickname}" /></strong> · <c:out value="${post.createdAt}" /></p>
		<c:if test="${not empty post.memo}">
			<p class="memo"><c:out value="${post.memo}" /></p>
		</c:if>
	</article>

	<%-- 삭제: 작성할 때 입력한 비밀번호가 맞아야 지워진다. --%>
	<%-- onsubmit의 confirm(): "정말 삭제할까요?" 창에서 취소를 누르면 false가 되어 전송하지 않는다. --%>
	<form class="delete-form" action="/posts/${post.id}/delete" method="post" onsubmit="return confirm('정말 삭제할까요?');">
		<label for="password">비밀번호</label>
		<input type="password" id="password" name="password" maxlength="20" required>
		<%-- deleteError: 비밀번호가 틀렸을 때 Controller가 redirect하면서 넘긴 메시지 (한 번만 보인다) --%>
		<c:if test="${not empty deleteError}">
			<p class="error"><c:out value="${deleteError}" /></p>
		</c:if>
		<button type="submit" class="danger">삭제</button>
	</form>
</main>
</body>
</html>
