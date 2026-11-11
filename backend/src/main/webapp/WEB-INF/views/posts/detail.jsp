<%-- 게시글 상세 화면 --%>
<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%-- fmt:formatNumber: 숫자를 보기 좋게 바꾼다 (1650.0 → 1,650) --%>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<%-- Spring의 form 태그: 댓글 입력칸을 commentForm과 연결하고 검사 메시지를 칸마다 보여 준다 (new.jsp와 같음) --%>
<%@ taglib prefix="form" uri="http://www.springframework.org/tags/form" %>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>사진 상세 - 찍먹</title>
<link rel="stylesheet" href="<c:url value='/css/app.css' />">
</head>
<body>
<main>
	<header class="top">
		<%-- data-back: "보고 있던 목록 쪽으로 돌아가는 링크"라는 표시. 주소는 첫 쪽으로 적어 두고,
		     common/footer.jspf의 스크립트가 방문자가 보던 쪽의 주소로 바꿔 준다 (JM-71, 피드백 10번) --%>
		<a href="/" data-back>← 목록</a>
	</header>

	<%-- PC에서는 왼쪽 사진 | 오른쪽 내용, 휴대폰에서는 사진 아래에 내용 (app.css의 .detail) --%>
	<div class="detail">
		<%-- ${post}: Controller가 Model에 넣은 글 하나 --%>
		<img src="${post.imageUrl}" alt="음식 사진">

		<div>
			<p class="meta"><strong><c:out value="${post.nickname}" /></strong><span><c:out value="${post.createdAt}" /></span></p>
			<c:if test="${not empty post.memo}">
				<p class="memo"><c:out value="${post.memo}" /></p>
			</c:if>

			<%-- AI 분석 결과. AI 분석을 넣기 전에 올린 글은 ${analysis}가 비어 있어서 이 칸이 나오지 않는다. --%>
			<%-- 음식 이름과 설명도 사용자 입력처럼 c:out으로 찍는다. 사진 속 글자가 AI를 거쳐 HTML로 끼어들 수 있기 때문이다. --%>
			<c:if test="${not empty analysis}">
				<section class="analysis">
					<c:choose>
						<c:when test="${analysis.status == 'DONE'}">
							<p class="total-kcal"><fmt:formatNumber value="${analysis.totalKcal}" maxFractionDigits="0" /> kcal</p>
							<p class="meta">
								탄수화물 <fmt:formatNumber value="${analysis.carbsG}" maxFractionDigits="0" />g,
								단백질 <fmt:formatNumber value="${analysis.proteinG}" maxFractionDigits="0" />g,
								지방 <fmt:formatNumber value="${analysis.fatG}" maxFractionDigits="0" />g
							</p>
							<table>
								<thead>
									<tr><th>음식</th><th>양</th><th>칼로리</th></tr>
								</thead>
								<tbody>
									<c:forEach var="item" items="${analysis.items}">
										<tr>
											<td><c:out value="${item.name}" /></td>
											<td><fmt:formatNumber value="${item.amountG}" maxFractionDigits="0" />g</td>
											<td><fmt:formatNumber value="${item.kcal}" maxFractionDigits="0" /> kcal</td>
										</tr>
									</c:forEach>
								</tbody>
							</table>
							<p class="hint"><c:out value="${analysis.comment}" /></p>
							<c:if test="${not empty analysis.confidenceLabel}">
								<p class="hint">AI의 확신 정도: <c:out value="${analysis.confidenceLabel}" /></p>
							</c:if>
							<p class="hint">AI 추정치이며 실제와 다를 수 있습니다. 의료나 영양 상담을 대신하지 않습니다.</p>
						</c:when>
						<c:when test="${analysis.status == 'NOT_FOOD'}">
							<p>사진에서 음식을 찾지 못했어요.</p>
						</c:when>
						<c:when test="${analysis.status == 'FAILED'}">
							<p>칼로리 분석에 실패했어요.</p>
						</c:when>
						<c:when test="${analysis.status == 'LIMIT_EXCEEDED'}">
							<p>오늘 분석 횟수가 다 차서 이 사진은 칼로리를 분석하지 못했어요.</p>
						</c:when>
						<c:otherwise>
							<p id="analysis-pending">칼로리를 분석하고 있어요…</p>
						</c:otherwise>
					</c:choose>
				</section>
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

	<%-- 댓글: 사진, 내용 아래에 전체 폭으로. id="comments"는 댓글을 달거나 지운 뒤 이 자리로 바로 내려오기 위한 표시 --%>
	<section class="comments" id="comments">
		<h2>댓글 ${comments.totalCount}개</h2>

		<c:if test="${empty comments.comments}">
			<p class="hint">첫 댓글을 남겨 보세요.</p>
		</c:if>

		<c:forEach var="comment" items="${comments.comments}">
			<%-- id="comment-번호": 비밀번호가 틀렸을 때 그 댓글 자리로 돌아오기 위한 표시 --%>
			<article class="comment" id="comment-${comment.id}">
				<c:choose>
					<%-- 지운 댓글에 답글이 남아 있으면 그 자리에 안내 글만 보여 준다 (닉네임, 내용, "답글" 칸 없음) --%>
					<c:when test="${not empty comment.deletedMessage}">
						<p class="comment-deleted"><c:out value="${comment.deletedMessage}" /></p>
					</c:when>
					<c:otherwise>
						<c:set var="shown" value="${comment}" />
						<%@ include file="/WEB-INF/views/posts/comment-body.jspf" %>
					</c:otherwise>
				</c:choose>

				<%-- 답글: 댓글 아래에 들여 써서 오래된 순으로 --%>
				<c:if test="${not empty comment.replies}">
					<div class="comment-replies">
						<c:forEach var="reply" items="${comment.replies}">
							<article class="comment" id="comment-${reply.id}">
								<c:set var="shown" value="${reply}" />
								<%@ include file="/WEB-INF/views/posts/comment-body.jspf" %>
							</article>
						</c:forEach>
					</div>
				</c:if>
			</article>
		</c:forEach>

		<%-- 더 볼 댓글이 있을 때만. 누르면 마지막 댓글 번호 다음부터 이어서 보여 준다. --%>
		<c:if test="${comments.nextCursor != null}">
			<a class="more" href="/posts/${post.id}?commentCursor=${comments.nextCursor}#comments">댓글 더 보기</a>
		</c:if>

		<%-- 댓글 쓰기. 검사에 걸리면 CommentController가 입력값과 메시지를 flash로 보내고, 여기서 칸을 채우고 메시지를 보여 준다. --%>
		<%-- 칸 id에 comment-를 붙이는 이유: 위의 글 삭제 form에도 password 칸이 있어서 id가 겹치지 않게 --%>
		<form:form modelAttribute="commentForm" action="/posts/${post.id}/comments" method="post" cssClass="comment-form">
			<label for="comment-nickname">닉네임</label>
			<form:input path="nickname" id="comment-nickname" maxlength="20" />
			<form:errors path="nickname" cssClass="error" element="p" />

			<label for="comment-password">비밀번호</label>
			<form:password path="password" id="comment-password" maxlength="20" />
			<p class="hint">댓글을 지울 때 필요해요. 잊으면 직접 지울 수 없어요.</p>
			<form:errors path="password" cssClass="error" element="p" />

			<label for="comment-content">댓글</label>
			<form:textarea path="content" id="comment-content" maxlength="500" rows="3" />
			<form:errors path="content" cssClass="error" element="p" />

			<button type="submit">댓글 남기기</button>
		</form:form>
	</section>

	<%-- 공통 바닥글 (개인정보처리방침 링크) --%>
	<%@ include file="/WEB-INF/views/common/footer.jspf" %>
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

	// 댓글 쓰기도 연속으로 누르면 같은 댓글이 여러 개 달리므로, 첫 번째 누름에서 버튼을 막는다.
	const commentForm = document.querySelector('.comment-form');
	const commentButton = commentForm.querySelector('button[type="submit"]');

	commentForm.addEventListener('submit', function () {
		commentButton.disabled = true;
		commentButton.textContent = '남기는 중…';
	});

	// 답글도 같은 이유로 첫 번째 누름에서 버튼을 막는다. 답글 칸은 댓글마다 있어서 전부 찾아서 건다.
	const replyForms = document.querySelectorAll('.reply-form');

	replyForms.forEach(function (form) {
		form.addEventListener('submit', function () {
			const button = form.querySelector('button[type="submit"]');
			button.disabled = true;
			button.textContent = '남기는 중…';
		});
	});

	// "뒤로 가기"로 이 화면이 되살아났을 때 버튼이 막힌 채로 남지 않게 원래대로 돌려놓는다.
	window.addEventListener('pageshow', function () {
		deleteButton.disabled = false;
		deleteButton.textContent = '삭제';
		commentButton.disabled = false;
		commentButton.textContent = '댓글 남기기';
		replyForms.forEach(function (form) {
			const button = form.querySelector('button[type="submit"]');
			button.disabled = false;
			button.textContent = '답글 남기기';
		});
	});
</script>

<%-- 분석 중일 때만 실행한다. 분석이 끝났는지 2초마다 서버에 물어보고(폴링), 끝나면 화면을 새로 열어 결과를 보여 준다. --%>
<%-- 결과를 JavaScript로 다시 그리지 않고 새로 여는 이유: 결과 화면을 위 JSP 한 곳에서만 관리하려고 --%>
<c:if test="${analysis.status == 'PENDING'}">
<script>
	const POLL_INTERVAL_MS = 2000;
	// 이만큼 기다려도 안 끝나면 그만 물어본다 (서버에 요청을 계속 보내지 않게)
	const MAX_WAIT_MS = 60000;
	const pollStartedAt = Date.now();

	async function checkAnalysis() {
		try {
			// fetch: 화면을 이동하지 않고 서버에 요청을 보낸다. await: 응답이 올 때까지 기다린다.
			const response = await fetch('/api/posts/${post.id}/analysis');
			if (response.ok) {
				const body = await response.json();
				if (body.status !== 'PENDING') {
					location.reload();
					return;
				}
			}
		} catch (error) {
			// 휴대폰 연결이 잠깐 끊긴 경우 등. 다음 차례에 다시 물어본다.
		}

		if (Date.now() - pollStartedAt >= MAX_WAIT_MS) {
			document.getElementById('analysis-pending').textContent = '분석이 오래 걸리고 있어요. 잠시 후 새로고침해 주세요.';
			return;
		}
		// setInterval 대신 응답을 받은 뒤에 다음 요청을 예약한다 → 응답이 느려도 요청이 겹쳐 쌓이지 않는다.
		setTimeout(checkAnalysis, POLL_INTERVAL_MS);
	}

	setTimeout(checkAnalysis, POLL_INTERVAL_MS);
</script>
</c:if>
</body>
</html>
