<%-- 개인정보처리방침 --%>
<%-- 실제로 하고 있는 일과 똑같이 적어야 한다. 기능이 바뀌면(예: AI 분석 추가) 이 페이지도 같이 고치고 시행일을 바꾼다. --%>
<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>개인정보처리방침</title>
<link rel="stylesheet" href="/css/app.css">
</head>
<body>
<main>
	<header class="top">
		<a href="/">← 목록</a>
	</header>

	<article class="policy">
		<h1>개인정보처리방침</h1>
		<p class="hint">시행일: 2026년 10월 31일</p>

		<p>음식 칼로리 게시판(이하 "서비스")은 「개인정보 보호법」에 따라 이용자의 개인정보를 아래와 같이 처리합니다.
			이 서비스는 회원가입 없이 이용하며, 만 14세 이상을 대상으로 합니다.</p>

		<h2>1. 수집하는 항목과 목적</h2>
		<table>
			<thead>
				<tr><th>항목</th><th>목적</th><th>구분</th></tr>
			</thead>
			<tbody>
				<tr><td>닉네임, 메모</td><td>게시글에 표시</td><td>닉네임 필수, 메모 선택</td></tr>
				<tr><td>비밀번호</td><td>작성자 본인 확인(게시글 삭제). 원문은 저장하지 않고 되돌릴 수 없는 형태(BCrypt 해시)로만 저장</td><td>필수</td></tr>
				<tr><td>사진</td><td>게시글에 표시. 사진에 담긴 위치·기기 정보(EXIF)는 지운 뒤 저장</td><td>필수</td></tr>
				<tr><td>사진의 위치(GPS)와 촬영 시각</td><td>지역·시간대별 음식 통계. 개인을 알아볼 수 없는 집계 형태로만 공개하며, 위치 좌표는 다른 이용자에게 보여 주지 않음</td><td>선택 (동의한 경우에만)</td></tr>
				<tr><td>접속 IP</td><td>도배·남용 방지(업로드 횟수 제한). 서버 메모리에만 최대 1일 보관하며, 제한에 걸린 경우 원래 IP를 알 수 없는 형태(해시)로만 기록</td><td>자동 수집</td></tr>
				<tr><td>서비스 이용 기록</td><td>오류 확인과 서비스 운영 (요청 시각, 요청 주소, 처리 결과)</td><td>자동 수집</td></tr>
			</tbody>
		</table>

		<h2>2. 보관 기간과 파기</h2>
		<ul>
			<li><strong>게시글</strong>: 작성자가 삭제하면 화면에서 바로 숨기고, 운영과 분쟁 대응을 위해 서비스를 운영하는 동안 보관합니다. 서비스를 종료하면 지체 없이 파기합니다.</li>
			<li><strong>사진의 위치</strong>: 게시글을 삭제하면 즉시 완전히 파기합니다.</li>
			<li><strong>서비스 이용 기록</strong>: 3개월 동안 보관한 뒤 삭제합니다.</li>
		</ul>

		<h2>3. 제3자 제공</h2>
		<p>이용자의 개인정보를 제3자에게 제공하지 않습니다. 다만 법령에 따라 요구되는 경우는 예외로 합니다.</p>

		<h2>4. 처리 위탁</h2>
		<table>
			<thead>
				<tr><th>맡는 곳</th><th>맡기는 일</th><th>위치</th></tr>
			</thead>
			<tbody>
				<tr><td>Amazon Web Services(AWS)</td><td>서버 운영, 사진과 데이터 저장</td><td>대한민국 (서울 리전)</td></tr>
			</tbody>
		</table>

		<h2>5. 이용자의 권리</h2>
		<ul>
			<li>작성할 때 입력한 비밀번호로 게시글을 직접 삭제할 수 있습니다.</li>
			<li>비밀번호를 잊었거나 개인정보의 열람·삭제를 요청하려면 아래 이메일로 연락해 주세요.</li>
			<li>위치·촬영 시각 제공은 선택이며, 동의하지 않아도 서비스를 이용할 수 있습니다.</li>
		</ul>

		<h2>6. 쿠키</h2>
		<p>비밀번호 오류 같은 한 번만 보여 주는 안내를 위해 세션 쿠키를 사용하며, 브라우저를 닫으면 사라집니다. 광고나 추적을 위한 쿠키는 사용하지 않습니다.</p>

		<h2>7. 개인정보 보호책임자</h2>
		<%-- 이메일은 설정(app.contact-email)에서 받는다. mailto: 링크를 누르면 메일 앱이 열린다. --%>
		<p>이메일: <a href="mailto:<c:out value='${contactEmail}' />"><c:out value="${contactEmail}" /></a></p>

		<h2>8. 방침의 변경</h2>
		<p>이 방침이 바뀌면 이 페이지에 시행일과 함께 알립니다.</p>
	</article>

	<%@ include file="/WEB-INF/views/common/footer.jspf" %>
</main>
</body>
</html>
