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

	<%-- PC에서는 왼쪽 사진 | 오른쪽 내용, 휴대폰에서는 사진 아래에 내용 (app.css의 .detail) --%>
	<div class="detail">
		<%-- ${post}: Controller가 Model에 넣은 글 하나 --%>
		<img src="${post.imageUrl}" alt="음식 사진">

		<div>
			<p class="meta"><strong><c:out value="${post.nickname}" /></strong> · <c:out value="${post.createdAt}" /></p>
			<c:if test="${not empty post.memo}">
				<p class="memo"><c:out value="${post.memo}" /></p>
			</c:if>

			<%-- 삭제: 작성할 때 입력한 비밀번호가 맞아야 지워진다. 확인 창과 연속 누름 막기는 아래 script에서 처리한다. --%>
			<form class="delete-form" action="/posts/${post.id}/delete" method="post">
				<label for="password">비밀번호</label>
				<input type="password" id="password" name="password" maxlength="20" required>
				<%-- deleteError: 비밀번호가 틀렸을 때 Controller가 redirect하면서 넘긴 메시지 (한 번만 보인다) --%>
				<c:if test="${not empty deleteError}">
					<p class="error"><c:out value="${deleteError}" /></p>
				</c:if>
				<button type="submit" class="danger">삭제</button>
			</form>
		</div>
	</div>
</main>

<script>
	const deleteForm = document.querySelector('.delete-form');
	const deleteButton = deleteForm.querySelector('button[type="submit"]');

	deleteForm.addEventListener('submit', function (event) {
		// confirm(): "정말 삭제할까요?" 창. 취소를 누르면 false → 전송을 멈춘다.
		if (!confirm('정말 삭제할까요?')) {
			event.preventDefault();
			return;
		}
		// 연속으로 누르면 두 번째 요청은 이미 지워진 글이라 "게시글을 찾을 수 없습니다" 에러 화면이 나온다. → 첫 번째만 보낸다.
		deleteButton.disabled = true;
		deleteButton.textContent = '삭제하는 중…';
	});

	// "뒤로 가기"로 이 화면이 되살아났을 때 버튼이 막힌 채로 남지 않게 원래대로 돌려놓는다.
	window.addEventListener('pageshow', function () {
		deleteButton.disabled = false;
		deleteButton.textContent = '삭제';
	});
</script>
</body>
</html>
