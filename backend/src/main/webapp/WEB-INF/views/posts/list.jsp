<%-- 게시글 목록 (첫 화면) --%>
<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>찍먹</title>
<%-- 모든 화면이 함께 쓰는 스타일 (src/main/webapp/css/app.css) --%>
<%-- c:url: 배포 서버에서는 주소 뒤에 파일 내용의 지문이 붙는다 (/css/app-3f2a….css). 개발 PC에서는 그대로 /css/app.css --%>
<link rel="stylesheet" href="<c:url value='/css/app.css' />">
</head>
<body>
<main>
	<%-- 머리 (JM-60): PC는 첫 줄에 제목 | 한 줄 소개(가운데) | 사진 올리기, 둘째 줄 왼쪽에 메뉴 링크 (사진 바로 위)
	     휴대폰은 첫 줄에 제목과 버튼, 둘째 줄에 한 줄 소개, 셋째 줄에 메뉴 링크 (자리는 app.css의 .top-menu) --%>
	<header class="top top-menu">
		<h1>찍먹</h1>
		<%-- 한 줄 소개: 처음 온 사람이 뭘 하는 곳인지 바로 알게 (11-05). "알려줘요"는 운영자 요청으로 붙여 쓴다 (11-10) --%>
		<p class="slogan">찍고 먹자 !<small>먹은 사진을 올리면 AI가 칼로리를 알려줘요</small></p>
		<a class="button" href="/posts/new">사진 올리기</a>
		<%-- 메뉴 링크: 피드(이 화면), 공지(JM-65), 피드백 게시판. 피드백은 무엇을 하는 곳인지 바로 알게 "개발자에게 피드백 보내기"로 적는다.
		     지금 화면 링크에는 active(초록 굵은 글자, app.css)와 aria-current="page"(화면 읽기 프로그램이 "지금 화면"이라고 읽는다)를 단다 (JM-63) --%>
		<nav class="site-nav">
			<a href="/" class="active" aria-current="page">피드</a>
			<a href="/notice">공지</a>
			<a href="/feedback">개발자에게 피드백 보내기</a>
		</nav>
	</header>

	<%-- empty: 목록이 비어 있으면 true --%>
	<c:if test="${empty feed.posts}">
		<p class="empty">아직 올라온 사진이 없어요.</p>
	</c:if>

	<%-- 카드 격자: PC에서는 한 줄에 여러 장, 휴대폰에서는 한 줄에 세 장 (칸 수는 app.css의 .feed가 화면 폭을 보고 정한다. 세 장은 지인 피드백 3번, JM-50) --%>
	<div class="feed">
		<%-- c:forEach: feed.posts의 글을 하나씩 post라는 이름으로 꺼내 반복한다. --%>
		<c:forEach var="post" items="${feed.posts}">
			<%-- 카드 전체가 링크라서 사진이나 글씨 아무 곳을 눌러도 상세 화면(/posts/글번호)으로 간다. --%>
			<%-- 글 번호와 사진 주소는 서버가 만든 값이라 그대로 출력해도 안전하다. --%>
			<a class="card" href="/posts/${post.id}">
				<%-- 목록에는 큰 사진 대신 600 × 600 썸네일을 쓴다 (보통 수십 KB라 목록이 빨리 뜬다) --%>
				<%-- loading="lazy": 화면에 가까워졌을 때 사진을 불러온다 (휴대폰 데이터 절약) --%>
				<img src="${post.thumbnailUrl}" alt="음식 사진" loading="lazy">
				<%-- 칼로리 줄: 분석이 끝난 글은 칼로리, 분석 중인 글은 안내. 음식이 아니거나 실패한 글, 분석 기록이 없는 글은 빈 줄이다.
				     빈 줄(&nbsp;)이라도 자리를 차지하게 해서, 카드마다 그 아래 줄들의 높이가 같게 한다 (JM-72) --%>
				<p class="card-kcal">
					<c:choose>
						<c:when test="${post.analysisStatus == 'DONE'}"><fmt:formatNumber value="${post.totalKcal}" maxFractionDigits="0" /> kcal</c:when>
						<c:when test="${post.analysisStatus == 'PENDING'}"><span class="card-pending">칼로리 분석 중</span></c:when>
						<c:otherwise>&nbsp;</c:otherwise>
					</c:choose>
				</p>
				<%-- 닉네임 줄 (JM-72): 왼쪽에 닉네임(길면 …으로 줄인다), 오른쪽 끝에 날짜. 날짜가 닉네임 길이와 상관없이 늘 같은 자리에 온다.
				     (그 전에는 닉네임 바로 뒤에 날짜를 붙여서, 닉네임 길이에 따라 날짜 자리가 카드마다 달랐다)
				     날짜는 둘 다 넣어 두고 app.css가 하나만 보여 준다: PC는 "11월 11일 18:43", 휴대폰은 짧게 "18:43"(오늘) 또는 "11.10"
				     닉네임, 메모는 사용자가 쓴 값이라 반드시 c:out으로 출력한다 (XSS 방지) --%>
				<p class="card-who"><strong><c:out value="${post.nickname}" /></strong><span class="card-when"><span class="when-full"><c:out value="${post.createdAt}" /></span><span class="when-short"><c:out value="${post.createdShort}" /></span></span></p>
				<c:if test="${not empty post.memo}">
					<p class="memo"><c:out value="${post.memo}" /></p>
				</c:if>
				<%-- 숫자 줄 (JM-72): 좋아요, 댓글, 조회수. 세 칸의 폭이 같아서 숫자가 카드마다 같은 자리에 오고, 0이어도 자리를 지킨다.
				     카드 맨 아래에 붙어서, 메모가 있든 없든 같은 줄의 카드끼리 높이가 맞는다 (app.css의 .card-stats)
				     아이콘은 app.css가 그린다. sr-only: 화면에는 안 보이고 화면 읽기 프로그램만 읽는 글자 ("좋아요 3") --%>
				<p class="card-stats">
					<span class="stat stat-like"><span class="sr-only">좋아요 </span>${post.likeCount}</span>
					<span class="stat stat-comment"><span class="sr-only">댓글 </span>${post.commentCount}</span>
					<span class="stat stat-view"><span class="sr-only">조회 </span>${post.viewCount}</span>
				</p>
			</a>
		</c:forEach>
	</div>

	<%-- 쪽 번호 줄: 이전 1 2 3 다음 (JM-57). 2쪽 이상일 때만 보인다. 그리는 법은 common/pager.jspf --%>
	<c:set var="nav" value="${feed.nav}" />
	<c:set var="pagerBase" value="/?page=" />
	<%@ include file="/WEB-INF/views/common/pager.jspf" %>

	<%-- 공통 바닥글 (개인정보처리방침 링크) --%>
	<%@ include file="/WEB-INF/views/common/footer.jspf" %>
</main>
</body>
</html>
