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
			<%-- 조회수 (JM-72): 사람이 이 화면을 열 때마다 1 오른다. 댓글을 쓰고 돌아온 화면, 봇은 세지 않는다 (PostController) --%>
			<p class="meta"><strong><c:out value="${post.nickname}" /></strong><span><c:out value="${post.createdAt}" /></span><span>조회 ${post.viewCount}</span></p>
			<%-- id="memo-text": 메모 수정 칸이 펼쳐져 있는 동안 아래 script가 이 글자를 숨긴다 --%>
			<c:if test="${not empty post.memo}">
				<p class="memo" id="memo-text"><c:out value="${post.memo}" /></p>
			</c:if>

			<%-- 글쓴이가 쓰는 두 가지: 메모 수정, 글 삭제 (JM-53). 작은 글자로 한 줄에 놓고, 누르면 그 자리에 칸이 펼쳐진다.
			     details/summary: JavaScript 없이 되는 HTML 기본 기능이고, 댓글의 "답글", "삭제"와 같은 방식이다.
			     name이 같은 details는 하나를 펼치면 다른 하나가 접힌다 (이 기능이 없는 옛 브라우저에서는 둘 다 펼쳐질 뿐이다).
			     가입이 없어 누가 작성자인지 미리 알 수 없으므로 누구에게나 보인다. 그래서 펼치기 전에는 작은 글자만 둔다:
			     처음에는 삭제용 비밀번호 칸과 빨간 버튼이 늘 보였는데, 지울 일이 없는 사람도 계속 보게 돼서 접었다 (운영자 검토). --%>
			<div class="post-actions">
				<%-- 메모 수정 (피드백 7번): 글을 올릴 때 넣은 비밀번호가 맞아야 저장된다.
				     memoError, memoDraft: 저장하지 못했을 때 Controller가 redirect하면서 넘긴 메시지와 고쳐 쓰던 메모 (한 번만 보인다).
				     그때는 칸을 펼친 채로(open) 쓰던 메모를 다시 채워서 보여 준다. --%>
				<details class="memo-edit" id="memo-edit" name="post-action"<c:if test="${not empty memoError}"> open</c:if>>
					<summary>메모 수정</summary>
					<form class="memo-edit-form" action="/posts/${post.id}/memo" method="post">
						<textarea name="memo" maxlength="300" rows="3" placeholder="메모" aria-label="메모"><c:out value="${not empty memoError ? memoDraft : post.memo}" /></textarea>
						<input type="password" name="password" maxlength="20" placeholder="비밀번호" aria-label="비밀번호" required>
						<button type="submit">저장</button>
						<p class="hint">글을 올릴 때 넣은 비밀번호를 입력해 주세요. 메모를 비우고 저장하면 메모가 없어져요.</p>
						<c:if test="${not empty memoError}">
							<p class="error"><c:out value="${memoError}" /></p>
						</c:if>
					</form>
				</details>

				<%-- 글 삭제: 작성할 때 입력한 비밀번호가 맞아야 지워진다. 확인 창과 연속 누름 막기는 아래 script에서 처리한다.
				     deleteError: 비밀번호가 틀렸을 때 Controller가 redirect하면서 넘긴 메시지 (한 번만 보인다). 그때는 칸을 펼친 채로 보여 준다. --%>
				<details class="post-delete" id="post-delete" name="post-action"<c:if test="${not empty deleteError}"> open</c:if>>
					<summary>글 삭제</summary>
					<form class="post-delete-form" action="/posts/${post.id}/delete" method="post">
						<input type="password" name="password" maxlength="20" placeholder="비밀번호" aria-label="비밀번호" required>
						<button type="submit" class="danger">삭제</button>
						<c:if test="${not empty deleteError}">
							<p class="error"><c:out value="${deleteError}" /></p>
						</c:if>
					</form>
				</details>
			</div>

			<%-- AI 분석 결과. AI 분석을 넣기 전에 올린 글은 ${analysis}가 비어 있어서 이 칸이 나오지 않는다. --%>
			<%-- 음식 이름과 설명도 사용자 입력처럼 c:out으로 찍는다. 사진 속 글자가 AI를 거쳐 HTML로 끼어들 수 있기 때문이다. --%>
			<c:if test="${not empty analysis}">
				<section class="analysis">
					<c:choose>
						<c:when test="${analysis.status == 'DONE'}">
							<%-- 보여 주는 값은 사용자가 확정했으면 확정한 값, 아니면 AI의 첫 답이다 (JM-74).
							     "내가 보고 확정"으로 올린 글에는 칼로리 옆에 작은 표시가 붙는다: 확정 전 (AI 추정) → 직접 확정 --%>
							<p class="total-kcal"><fmt:formatNumber value="${analysis.totalKcal}" maxFractionDigits="0" /> kcal<c:if test="${analysis.userMode}"><span class="confirm-badge${analysis.confirmed ? ' confirmed' : ''}">${analysis.confirmed ? '직접 확정' : '확정 전 (AI 추정)'}</span></c:if></p>
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
							<c:if test="${analysis.confirmed}">
								<p class="hint">글쓴이가 고쳐서 확정한 값이에요. AI의 첫 답은 <fmt:formatNumber value="${analysis.aiTotalKcal}" maxFractionDigits="0" /> kcal였어요.</p>
							</c:if>
							<p class="hint">AI 추정치이며 실제와 다를 수 있습니다. 의료나 영양 상담을 대신하지 않습니다.</p>

							<%-- 고쳐서 확정하기 (JM-74, 고쳐 쓰는 AI): 후보가 있는 글("내가 보고 확정"으로 올린 글)에만 나온다.
							     메모 수정처럼 작은 글자를 누르면 그 자리에 펼쳐진다. 음식마다 후보를 고르고 양을 고친 뒤 확정한다.
							     confirmTrusted: 이 글을 방금 올린 브라우저인지 (서버가 세션으로 안다, PostController).
							       - 맞으면: 아직 확정하지 않은 글은 칸을 펼쳐서 보여 주고, 비밀번호를 묻지 않는다.
							       - 아니면(다른 사람, 다른 기기, 시간이 지난 뒤): 칸을 접어 두고, 확정하려면 글 비밀번호를 넣어야 한다.
							     저장하지 못해 돌아왔을 때(confirmError)도 펼친 채로 보여 준다.
							     confirmPicks, confirmAmounts: 저장하지 못했을 때 고르던 값 (한 번만 넘어온다). 있으면 그 값으로 다시 채운다.
							     data-kcal: 그 후보의 100g당 칼로리. script가 "지금 고른 대로면 몇 kcal"을 미리 보여 주는 데 쓴다. --%>
							<c:if test="${not empty analysis.confirmRows}">
								<details class="analysis-confirm" id="analysis-confirm"<c:if test="${not empty confirmError or (confirmTrusted and not analysis.confirmed)}"> open</c:if>>
									<summary>${analysis.confirmed ? '다시 고치기' : '후보에서 골라 확정하기'}</summary>
									<form class="analysis-confirm-form${confirmTrusted ? ' trusted' : ''}" action="/posts/${post.id}/confirm" method="post">
										<c:forEach var="row" items="${analysis.confirmRows}">
											<c:set var="pickNow" value="${not empty confirmPicks ? confirmPicks[row.index] : row.pick}" />
											<c:set var="amountNow" value="${not empty confirmAmounts ? confirmAmounts[row.index] : row.amountG}" />
											<div class="confirm-row">
												<select name="pick" aria-label="음식 ${row.index + 1}">
													<c:forEach var="option" items="${row.options}">
														<option value="${option.index}" data-kcal="${option.kcalPer100g}"<c:if test="${pickNow == option.index}"> selected</c:if>><c:out value="${option.name}" /><c:if test="${option.index == 0}"> (AI의 첫 답)</c:if></option>
													</c:forEach>
													<option value="-1" data-kcal="0"<c:if test="${pickNow == -1}"> selected</c:if>>이 음식 아님 (빼기)</option>
												</select>
												<input type="number" name="amount" value="<fmt:formatNumber value='${amountNow}' maxFractionDigits='0' groupingUsed='false' />" min="1" max="5000" step="1" inputmode="numeric" aria-label="음식 ${row.index + 1}의 양 (g)" required>
												<span class="confirm-unit">g</span>
												<span class="confirm-kcal"></span>
											</div>
										</c:forEach>
										<p class="confirm-total">합계 <strong id="confirm-total"></strong></p>
										<c:choose>
											<c:when test="${confirmTrusted}">
												<button type="submit">확정</button>
												<p class="hint">이 브라우저에서 방금 올린 글이라 비밀번호 없이 확정할 수 있어요. 합계는 확정할 때 서버가 다시 계산해요.</p>
											</c:when>
											<c:otherwise>
												<input type="password" name="password" maxlength="20" placeholder="비밀번호" aria-label="비밀번호" required>
												<button type="submit">확정</button>
												<p class="hint">글을 올릴 때 넣은 비밀번호를 입력해 주세요. 합계는 확정할 때 서버가 다시 계산해요.</p>
											</c:otherwise>
										</c:choose>
										<c:if test="${not empty confirmError}">
											<p class="error"><c:out value="${confirmError}" /></p>
										</c:if>
									</form>
								</details>
							</c:if>
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

			<%-- 좋아요 (JM-59, 피드백 9번): 누르면 숫자가 1 오른다. 화면을 새로 열지 않고, 아래 script가 서버에 알린 뒤 숫자만 바꾼다.
			     취소는 없다 (누가 눌렀는지 서버가 모른다). 누른 글은 이 브라우저가 기억해서, 다시 열어도 눌린 모양으로 보이고 또 누를 수 없다.
			     data-post-id: script가 읽어 갈 글 번호 / aria-pressed: 화면 읽기 프로그램에 "눌린 상태인지"를 알린다 --%>
			<p class="like-row">
				<button type="button" class="like-button" id="like-button" data-post-id="${post.id}" aria-pressed="false">
					<span class="like-icon" aria-hidden="true"></span>좋아요 <span id="like-count">${post.likeCount}</span>
				</button>
			</p>
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
		<%-- 칸 id에 comment-를 붙이는 이유: 한 화면에 비밀번호 칸이 여럿(메모 수정, 글 삭제, 댓글)이라 서로 헷갈리지 않게 --%>
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
	const deleteForm = document.querySelector('.post-delete-form');
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

	// 메모 수정 (JM-53): 칸이 펼쳐져 있는 동안에는 위의 메모 글자를 숨긴다 (같은 글이 두 번 보이지 않게). 접으면 다시 보인다.
	// 칸을 펼치는 것 자체는 HTML(details)이 하므로, 이 부분이 실행되지 않아도 메모는 고칠 수 있다.
	const memoEdit = document.getElementById('memo-edit');
	const memoText = document.getElementById('memo-text');
	const memoForm = memoEdit.querySelector('form');
	const memoButton = memoForm.querySelector('button[type="submit"]');

	function syncMemoText() {
		// 메모가 없는 글에는 memoText가 없다
		if (memoText) {
			memoText.hidden = memoEdit.open;
		}
	}

	// toggle: 펼치거나 접을 때마다 실행된다
	memoEdit.addEventListener('toggle', syncMemoText);
	// 저장하지 못해 돌아온 화면은 처음부터 펼쳐져 있으므로, 화면이 뜰 때도 한 번 맞춘다
	syncMemoText();

	// 연속으로 눌러 같은 요청이 여러 번 가지 않게, 첫 번째 누름에서 버튼을 막는다.
	memoForm.addEventListener('submit', function () {
		memoButton.disabled = true;
		memoButton.textContent = '저장하는 중…';
	});

	// 좋아요 (JM-59)
	const likeButton = document.getElementById('like-button');
	const likeCount = document.getElementById('like-count');
	const postId = likeButton.dataset.postId;
	const likedKey = 'liked:' + postId;

	// localStorage: 브라우저에 남는 저장소 (탭을 닫아도 남는다). 서버로 보내지 않는다.
	// 저장소를 막아 둔 브라우저에서는 여기서 오류가 난다 → 기억하지 못할 뿐 좋아요는 누를 수 있다
	let likedStore = null;
	try {
		likedStore = window.localStorage;
		likedStore.getItem(likedKey);
	} catch (error) {
		likedStore = null;
	}

	// 눌린 모양으로 바꾸고 더 누를 수 없게 한다
	function showLiked() {
		likeButton.classList.add('liked');
		likeButton.disabled = true;
		likeButton.setAttribute('aria-pressed', 'true');
	}

	// 전에 이 브라우저에서 누른 글이면 처음부터 눌린 모양으로
	if (likedStore && likedStore.getItem(likedKey)) {
		showLiked();
	}

	likeButton.addEventListener('click', async function () {
		// 응답을 기다리는 동안 또 누르지 못하게 먼저 막는다
		likeButton.disabled = true;
		try {
			const response = await fetch('/api/posts/' + postId + '/like', { method: 'POST' });
			if (response.ok) {
				// 화면의 숫자를 서버가 센 값으로 바꾼다 (그사이 다른 사람이 누른 것도 반영된다)
				const body = await response.json();
				likeCount.textContent = body.likeCount;
				if (likedStore) {
					likedStore.setItem(likedKey, '1');
				}
				showLiked();
				return;
			}
			if (response.status === 429) {
				// 이 글에 좋아요가 짧은 시간에 너무 많이 눌렸을 때 (숫자 부풀리기를 막는 서버의 제한)
				alert('지금은 이 글에 좋아요가 몰려 있어요. 잠시 후 다시 눌러 주세요.');
			}
		} catch (error) {
			// 휴대폰 연결이 잠깐 끊긴 경우 등: 아래에서 버튼을 풀어 다시 누를 수 있게 한다
		}
		likeButton.disabled = false;
	});

	// 고쳐서 확정하기 (JM-74, 고쳐 쓰는 AI)
	const confirmBox = document.getElementById('analysis-confirm');
	let confirmButton = null;
	// 후보가 없는 글에는 확정 칸이 없다
	if (confirmBox) {
		const confirmForm = confirmBox.querySelector('form');
		const confirmRows = confirmForm.querySelectorAll('.confirm-row');
		const confirmTotal = document.getElementById('confirm-total');
		confirmButton = confirmForm.querySelector('button[type="submit"]');

		// 지금 고른 대로면 몇 kcal인지 줄마다, 그리고 합계로 미리 보여 준다 (100g당 칼로리 × 양 ÷ 100).
		// 보여 주기만 하는 숫자다. 확정할 때는 서버가 저장해 둔 후보로 다시 계산한다.
		function showConfirmTotal() {
			let total = 0;
			confirmRows.forEach(function (row) {
				const select = row.querySelector('select');
				const amountInput = row.querySelector('input');
				const removed = select.value === '-1';
				const per100g = Number(select.selectedOptions[0].dataset.kcal) || 0;
				const amount = Number(amountInput.value) || 0;
				const kcal = removed ? 0 : Math.round(per100g * amount / 100);
				total += kcal;
				// 뺀 음식은 흐리게 보이고, 양을 적지 않아도 된다
				row.classList.toggle('removed', removed);
				amountInput.required = !removed;
				row.querySelector('.confirm-kcal').textContent = kcal.toLocaleString() + ' kcal';
			});
			confirmTotal.textContent = total.toLocaleString() + ' kcal';
		}

		// input: 양을 고칠 때마다, change: 후보를 바꿀 때
		confirmForm.addEventListener('input', showConfirmTotal);
		confirmForm.addEventListener('change', showConfirmTotal);
		showConfirmTotal();

		// 연속으로 눌러 같은 요청이 여러 번 가지 않게, 첫 번째 누름에서 버튼을 막는다.
		confirmForm.addEventListener('submit', function () {
			confirmButton.disabled = true;
			confirmButton.textContent = '확정하는 중…';
		});
	}

	// "뒤로 가기"로 이 화면이 되살아났을 때 버튼이 막힌 채로 남지 않게 원래대로 돌려놓는다.
	window.addEventListener('pageshow', function () {
		deleteButton.disabled = false;
		deleteButton.textContent = '삭제';
		memoButton.disabled = false;
		memoButton.textContent = '저장';
		if (confirmButton) {
			confirmButton.disabled = false;
			confirmButton.textContent = '확정';
		}
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
