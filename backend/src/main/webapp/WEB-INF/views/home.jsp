<%-- JSP 주석: 서버에서만 보이고, 브라우저로 보내는 HTML에는 남지 않는다. --%>
<%-- 이 페이지를 UTF-8로 만들고 UTF-8로 보낸다 (한글이 깨지지 않게) --%>
<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%-- JSTL의 기본 태그(c:out, c:forEach, c:if 등)를 c: 라는 이름으로 쓰겠다는 선언 --%>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<%-- 휴대폰에서 화면을 축소하지 않고 기기 폭에 맞춰 보여 준다 --%>
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>음식 칼로리 게시판</title>
</head>
<body>
	<%-- ${dbVersion}: Controller가 Model에 넣은 값을 꺼낸다. --%>
	<%-- c:out으로 감싸는 이유: <, > 같은 글자를 HTML 태그가 아닌 글자로 바꿔서 출력한다. --%>
	<%-- 나중에 사용자가 쓴 닉네임·메모를 출력할 때 이렇게 하지 않으면, 누군가 넣은 스크립트가 다른 사람 화면에서 실행된다(XSS). --%>
	<p>pong · MySQL <c:out value="${dbVersion}" /></p>
</body>
</html>
