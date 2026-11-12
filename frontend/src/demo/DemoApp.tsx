import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react'
import DemoMap from './DemoMap'
import { demoListings, type DemoListing } from './data'
import './demo.css'

const RoomViewer = lazy(() => import('./RoomViewer'))

export default function DemoApp() {
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [tour, setTour] = useState<DemoListing | null>(null)
  const selected = demoListings.find((item) => item.id === selectedId)
  const dialog = useRef<HTMLDivElement>(null)
  const closeButton = useRef<HTMLButtonElement>(null)
  const closeTour = useCallback(() => setTour(null), [])

  useEffect(() => {
    if (!tour) return
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    closeButton.current?.focus()
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') closeTour()
      if (event.key !== 'Tab') return
      const elements = dialog.current?.querySelectorAll<HTMLElement>('button:not(:disabled), [tabindex="0"]')
      if (!elements?.length) return
      const first = elements[0]
      const last = elements[elements.length - 1]
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => {
      window.removeEventListener('keydown', onKeyDown)
      document.body.style.overflow = previousOverflow
      previousFocus?.focus()
    }
  }, [tour, closeTour])

  return (
    <main className="demo-shell">
      <header className="demo-header">
        <a className="demo-brand" href={import.meta.env.BASE_URL} aria-label="bangpick 처음으로">
          <svg width="28" height="30" viewBox="0 0 28 30" fill="none" aria-hidden="true"><path d="M3 13 14 3l11 10v13H3V13Z" stroke="currentColor" strokeWidth="2.6" strokeLinejoin="round" /><path d="m9 17 4 4 7-8" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" /></svg>
          bangpick<span>방픽</span>
        </a>
        <nav className="demo-steps" aria-label="시연 흐름">
          <span className={!selected ? 'active' : ''}><b>01</b> 지도 탐색</span><i aria-hidden="true">/</i>
          <span className={selected && !tour ? 'active' : ''}><b>02</b> 매물 선택</span><i aria-hidden="true">/</i>
          <span className={tour ? 'active' : ''}><b>03</b> 방 둘러보기</span>
        </nav>
        <span className="demo-badge">서비스 흐름 데모</span>
      </header>

      <div className="demo-workspace" inert={tour ? true : undefined}>
        <aside className="demo-sidebar" aria-label="매물 목록 및 상세 정보">
          {selected ? (
            <>
              <button className="demo-back" onClick={() => setSelectedId(null)}>← 매물 목록</button>
              <div className="demo-detail-intro">
                <span className="demo-kicker">원룸 · 예시 매물</span>
                <h1>{selected.name}</h1>
                <p>{selected.address}</p>
                <div className="demo-price"><span>월세</span> {selected.price}<small>만원</small></div>
              </div>
              <p className="demo-station"><span aria-hidden="true">◇</span>{selected.station}</p>
              <dl className="demo-facts">
                <div><dt>전용면적</dt><dd>{selected.size}</dd></div>
                <div><dt>해당 층 / 전체 층</dt><dd>{selected.floor}</dd></div>
                <div><dt>월 관리비</dt><dd>{selected.maintenance}</dd></div>
              </dl>
              <section className="demo-detail-section"><h2>이 방의 이야기</h2><p>{selected.description}</p></section>
              <section className="demo-detail-section"><h2>기본 옵션</h2><div className="demo-options">{selected.options.map((option) => <span key={option}>{option}</span>)}</div></section>
              <div className="demo-tour-card">
                <span className="demo-kicker">3D GAUSSIAN SPLATTING</span>
                <h2>사진 너머, 공간 안으로.</h2>
                <p>직접 움직이며 방의 배치와 공간감을 살펴보세요.</p>
                <button className="demo-primary" onClick={() => setTour(selected)}>방 둘러보기 <span aria-hidden="true">↗</span></button>
              </div>
              <p className="demo-disclaimer">실제 매물이 아닌 시연 데이터입니다. 세 매물은 모두 동일한 데모 방 모델을 엽니다.</p>
            </>
          ) : (
            <>
              <span className="demo-kicker">FIND YOUR SPACE</span>
              <h1>지도에서 찾고,<br />공간으로 확인하세요.</h1>
              <p className="demo-lead">마음에 드는 방을 선택하고<br />직접 안으로 들어가 보세요.</p>
              <div className="demo-list-heading"><h2>마포구의 방</h2><span>예시 매물 3개</span></div>
              <div className="demo-listings">
                {demoListings.map((listing, index) => (
                  <button className="demo-listing" key={listing.id} onClick={() => setSelectedId(listing.id)}>
                    <span className="demo-listing-number">0{index + 1}</span>
                    <span><small>{listing.area} · 원룸</small><strong>월세 {listing.price}</strong><span>{listing.name}</span><small>{listing.size} · {listing.station}</small></span>
                    <span className="demo-listing-arrow" aria-hidden="true">→</span>
                  </button>
                ))}
              </div>
              <div className="demo-how"><span>이렇게 둘러보세요</span><p>지도 위 가격 선택 → 매물 정보 확인<br />→ <strong>방 둘러보기</strong>로 3D 투어 시작</p></div>
              <p className="demo-disclaimer">흐름 설명을 위한 프론트엔드 전용 시연입니다. 매물 정보는 모두 가상 데이터입니다.</p>
            </>
          )}
        </aside>
        <DemoMap selectedId={selectedId} onSelect={setSelectedId} />
      </div>

      {tour && (
        <div className="demo-modal" ref={dialog} role="dialog" aria-modal="true" aria-labelledby="tour-title">
          <header className="demo-viewer-header"><div><span className="demo-kicker">방 둘러보기</span><h2 id="tour-title">{tour.name}</h2></div><button className="demo-close" ref={closeButton} onClick={closeTour} aria-label="뷰어 닫기">닫기 <span aria-hidden="true">×</span></button></header>
          <Suspense fallback={<div className="demo-viewer-status" role="status"><span className="demo-spinner" /><h3>뷰어 준비 중</h3></div>}>
            <RoomViewer />
          </Suspense>
        </div>
      )}
    </main>
  )
}
