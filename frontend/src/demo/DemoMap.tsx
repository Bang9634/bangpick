import { useEffect, useRef, useState } from 'react'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { demoListings } from './data'

type Props = {
  selectedId: string | null
  onSelect: (id: string) => void
}

export default function DemoMap({ selectedId, onSelect }: Props) {
  const container = useRef<HTMLDivElement>(null)
  const mapRef = useRef<L.Map | null>(null)
  const markers = useRef(new Map<string, L.Marker>())
  const selectRef = useRef(onSelect)
  const [tileError, setTileError] = useState(false)

  useEffect(() => { selectRef.current = onSelect }, [onSelect])

  useEffect(() => {
    if (!container.current) return
    const map = L.map(container.current, { zoomControl: false, scrollWheelZoom: true })
      .setView([37.5568, 126.9208], 15)
    const currentMarkers = markers.current
    mapRef.current = map
    const tiles = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
      maxZoom: 19,
    }).addTo(map)
    tiles.on('tileerror', () => setTileError(true))
    tiles.on('tileload', () => setTileError(false))
    L.control.zoom({ position: 'bottomright' }).addTo(map)

    for (const listing of demoListings) {
      const label = document.createElement('span')
      label.className = 'demo-price-pin'
      label.textContent = listing.price
      const marker = L.marker(listing.position, {
        icon: L.divIcon({ html: label, className: 'demo-map-marker', iconSize: [114, 42], iconAnchor: [57, 42] }),
        title: `${listing.name} 선택`,
        alt: `${listing.name} 선택`,
        keyboard: true,
      }).addTo(map)
      marker.on('click', () => selectRef.current(listing.id))
      currentMarkers.set(listing.id, marker)
    }
    const resize = new ResizeObserver(() => map.invalidateSize())
    resize.observe(container.current)
    return () => {
      resize.disconnect()
      currentMarkers.clear()
      map.remove()
      mapRef.current = null
    }
  }, [])

  useEffect(() => {
    for (const [id, marker] of markers.current) {
      const element = marker.getElement()
      element?.classList.toggle('is-selected', id === selectedId)
      element?.setAttribute('aria-pressed', String(id === selectedId))
      marker.setZIndexOffset(id === selectedId ? 1000 : 0)
    }
    const listing = demoListings.find((item) => item.id === selectedId)
    if (listing) mapRef.current?.panTo(listing.position, { animate: true, duration: 0.35 })
  }, [selectedId])

  return (
    <section className="demo-map-region" aria-label="예시 매물 지도">
      <div ref={container} className="demo-map" />
      <div className="demo-map-label"><span className="demo-live-dot" />서울 마포구 <span>예시 매물 3곳</span></div>
      <div className="demo-map-guide">지도 위 가격을 눌러 매물을 선택하세요.</div>
      {tileError && <p className="demo-map-error" role="status">지도 타일을 불러오지 못했습니다. 인터넷 연결을 확인해 주세요. 매물 목록으로도 시연할 수 있습니다.</p>}
    </section>
  )
}
