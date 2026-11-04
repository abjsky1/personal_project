<%-- 피드백 상세 화면 --%>
<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>피드백</title>
<link rel="stylesheet" href="<c:url value='/css/app.css' />">
</head>
<body>
<main>
	<header class="top">
		<a href="/feedback">← 피드백 목록</a>
	</header>

	<article class="feedback-detail">
		<%-- 처리 상태 배지 + 제목 --%>
		<span class="badge ${feedback.progress.cssClass}"><c:out value="${feedback.progress.label}" /></span>
		<h1 class="feedback-title"><c:out value="${feedback.title}" /></h1>
		<p class="meta"><strong><c:out value="${feedback.nickname}" /></strong> · <c:out value="${feedback.createdAt}" /></p>

		<%-- 내용: 사용자 입력이라 c:out, 줄바꿈은 CSS로 --%>
		<p class="feedback-content"><c:out value="${feedback.content}" /></p>

		<%-- 운영자 답변: 있을 때만 (S3-3의 운영자 API로 단다) --%>
		<c:if test="${not empty feedback.adminReply}">
			<section class="admin-reply">
				<p class="meta"><strong>운영자 답변</strong> · <c:out value="${feedback.repliedAt}" /></p>
				<p class="feedback-content"><c:out value="${feedback.adminReply}" /></p>
			</section>
		</c:if>

		<%-- 작성자 삭제 (게시글 상세와 같은 방식) --%>
		<form class="delete-form" action="/feedback/${feedback.id}/delete" method="post">
			<label for="password">비밀번호</label>
			<input type="password" id="password" name="password" maxlength="20" required>
			<c:if test="${not empty deleteError}">
				<p class="error"><c:out value="${deleteError}" /></p>
			</c:if>
			<button type="submit" class="danger">삭제</button>
		</form>
	</article>

	<%@ include file="/WEB-INF/views/common/footer.jspf" %>
</main>

<script>
	const deleteForm = document.querySelector('.delete-form');
	const deleteButton = deleteForm.querySelector('button[type="submit"]');

	deleteForm.addEventListener('submit', function (event) {
		if (!confirm('정말 삭제할까요?')) {
			event.preventDefault();
			return;
		}
		deleteButton.disabled = true;
		deleteButton.textContent = '삭제하는 중…';
	});

	window.addEventListener('pageshow', function () {
		deleteButton.disabled = false;
		deleteButton.textContent = '삭제';
	});
</script>
</body>
</html>
