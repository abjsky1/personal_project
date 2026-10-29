import { useEffect, useState } from 'react'

// TypeScript의 type: 서버가 보내 주는 JSON의 모양을 미리 적어 둔다.
// 이렇게 해 두면 ping.dbVerson처럼 이름을 잘못 쓰면 실행 전에 편집기가 빨간 줄로 알려 준다.
type Ping = {
  message: string
  dbVersion: string
}

// 백엔드에 연결 확인을 요청하고, 받은 데이터를 돌려주는 함수
// 상태(useState)는 건드리지 않고 데이터만 돌려준다. 화면 상태를 바꾸는 일은 아래 App 안에서 한다.
//
// ": Promise<Ping>"은 이 함수가 돌려주는 값의 타입이다.
// async 함수는 결과를 Promise(나중에 값을 주겠다는 약속)로 감싸서 돌려주므로,
// Promise<Ping> = "요청이 끝나면 Ping 모양의 데이터를 주겠다"는 뜻
async function fetchPing(): Promise<Ping> {
  // 전체 주소(http://localhost:8080/...) 대신 '/api/ping'만 쓴다.
  // 브라우저는 지금 화면 주소(5173)로 요청하고, vite.config.ts의 프록시가 Spring(8080)으로 넘겨 준다.
  const res = await fetch('/api/ping')

  // res.ok: 응답 상태 코드가 200번대(성공)이면 true
  // 실패(404, 500 등)면 직접 오류를 던진다. 이 오류는 함수를 부른 쪽의 .catch로 전달된다.
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}`)
  }

  // 응답 본문(JSON)을 객체로 바꿔서 돌려준다.
  return res.json()
}

// React → Spring → MySQL 연결 확인 화면
function App() {
  // useState<Ping | null>: < > 안은 이 상태에 들어갈 수 있는 값의 타입. |는 "또는"
  // → Ping 모양의 객체 또는 null. 처음에는 아직 응답이 없으므로 null로 시작한다.
  const [ping, setPing] = useState<Ping | null>(null)
  // 연결에 실패하면 오류 메시지(문자열)를 담는다. 실패 전에는 null
  const [error, setError] = useState<string | null>(null)

  // 의존성 배열이 빈 배열([])이면 화면이 처음 나타날 때 1번만 실행된다.
  useEffect(() => {
    // .then: 요청이 성공해서 데이터가 오면 실행 / .catch: 요청이 실패하면 실행
    // setState를 effect 안에서 바로 부르지 않고, 응답이 온 "뒤에" 실행될 함수 안에 넣는다.
    // (effect 안에서 setState를 바로 부르면 화면이 불필요하게 한 번 더 그려져서 React 규칙 검사가 오류로 알려 준다)
    fetchPing()
      .then((data) => setPing(data))
      // TypeScript에서 catch로 받은 err는 타입이 정해져 있지 않다(무엇이든 던질 수 있으므로).
      // 그래서 Error 객체인지 확인한 뒤에 message를 꺼낸다.
      .catch((err) => setError(err instanceof Error ? err.message : String(err)))
  }, [])

  // 상태에 따라 다른 화면을 보여 준다: 실패 → 결과 대기 중 → 성공
  if (error) {
    return <p>연결 실패: {error}</p>
  }
  if (!ping) {
    return <p>연결 확인 중…</p>
  }
  return (
    <p>
      {ping.message} · MySQL {ping.dbVersion}
    </p>
  )
}

export default App
