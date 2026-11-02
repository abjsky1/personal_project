<%-- 게시글 상세 화면 --%>
<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%-- fmt:formatNumber: 숫자를 보기 좋게 바꾼다 (1650.0 → 1,650) --%>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>음식 사진 상세</title>
<link rel="stylesheet" href="<c:url value='/css/app.css' />">
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

			<%-- AI 분석 결과. AI 분석을 넣기 전에 올린 글은 ${analysis}가 비어 있어서 이 칸이 나오지 않는다. --%>
			<%-- 음식 이름과 설명도 사용자 입력처럼 c:out으로 찍는다. 사진 속 글자가 AI를 거쳐 HTML로 끼어들 수 있기 때문이다. --%>
			<c:if test="${not empty analysis}">
				<section class="analysis">
					<c:choose>
						<c:when test="${analysis.status == 'DONE'}">
							<p class="total-kcal"><fmt:formatNumber value="${analysis.totalKcal}" maxFractionDigits="0" /> kcal</p>
							<p class="meta">
								탄수화물 <fmt:formatNumber value="${analysis.carbsG}" maxFractionDigits="0" />g ·
								단백질 <fmt:formatNumber value="${analysis.proteinG}" maxFractionDigits="0" />g ·
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
							<p class="hint">AI 추정치이며 실제와 다를 수 있습니다. 의료·영양 상담을 대신하지 않습니다.</p>
						</c:when>
						<c:when test="${analysis.status == 'NOT_FOOD'}">
							<p>사진에서 음식을 찾지 못했어요.</p>
						</c:when>
						<c:when test="${analysis.status == 'FAILED'}">
							<p>칼로리 분석에 실패했어요.</p>
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

	// "뒤로 가기"로 이 화면이 되살아났을 때 버튼이 막힌 채로 남지 않게 원래대로 돌려놓는다.
	window.addEventListener('pageshow', function () {
		deleteButton.disabled = false;
		deleteButton.textContent = '삭제';
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
