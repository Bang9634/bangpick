import { useEffect, useRef, useState } from 'react'
import * as THREE from 'three'
import { SparkRenderer, SplatMesh } from '@sparkjsdev/spark'
import { demoModel } from './data'

type ViewerActions = { reset: () => void; flip: () => void }
const movementKeys = new Set(['KeyW', 'KeyA', 'KeyS', 'KeyD', 'KeyQ', 'KeyE', 'ShiftLeft', 'ShiftRight', 'KeyR'])

export default function RoomViewer() {
  const container = useRef<HTMLDivElement>(null)
  const actions = useRef<ViewerActions | null>(null)
  const [attempt, setAttempt] = useState(0)
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading')
  const [progress, setProgress] = useState<number | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    if (!container.current) return
    const host = container.current
    const controller = new AbortController()
    const { signal } = controller
    let disposed = false
    let ready = false
    let renderer: THREE.WebGLRenderer | undefined
    let spark: SparkRenderer | undefined
    let splats: SplatMesh | undefined
    let resize: ResizeObserver | undefined
    let meshDisposed = false
    const keys = new Set<string>()
    setState('loading')
    setProgress(null)
    setError('')

    function disposeMesh() {
      if (splats && !meshDisposed) { meshDisposed = true; splats.dispose() }
    }

    async function load() {
      try {
        // Fetch once: streaming avoids a second model download and allows cancellation on close.
        const response = await fetch(demoModel.url, { signal })
        if (!response.ok || response.headers.get('content-type')?.includes('text/html')) {
          throw new Error('모델 파일이 없습니다. frontend/public/demo/room.ply에 결과 파일을 넣어 주세요.')
        }
        if (!response.body) throw new Error('모델 데이터를 읽을 수 없습니다. 브라우저를 확인해 주세요.')
        if (disposed) return

        try { renderer = new THREE.WebGLRenderer({ antialias: false, powerPreference: 'high-performance' }) }
        catch { throw new Error('WebGL2를 시작할 수 없습니다. 브라우저의 하드웨어 가속을 켜고 다시 시도해 주세요.') }
        renderer.setPixelRatio(Math.min(window.devicePixelRatio, 1.75))
        renderer.setClearColor('#151c20')
        const canvas = renderer.domElement
        canvas.tabIndex = 0
        canvas.setAttribute('aria-label', '3D 방 뷰어. 드래그로 시선 변경, WASD 카메라 방향으로 비행, Q E 화면 기준 상하 이동, R 초기화')
        host.appendChild(canvas)
        const scene = new THREE.Scene()
        const camera = new THREE.PerspectiveCamera(65, 1, 0.01, 1000)
        camera.rotation.order = 'YXZ'
        spark = new SparkRenderer({ renderer })
        scene.add(spark)
        resize = new ResizeObserver(() => {
          if (!renderer || !host.clientWidth || !host.clientHeight) return
          camera.aspect = host.clientWidth / host.clientHeight
          camera.updateProjectionMatrix()
          renderer.setSize(host.clientWidth, host.clientHeight)
        })
        resize.observe(host)

        const clearKeys = () => keys.clear()
        window.addEventListener('blur', clearKeys, { signal })
        canvas.addEventListener('blur', clearKeys, { signal })
        window.addEventListener('keydown', (event) => {
          if (document.activeElement !== canvas || !movementKeys.has(event.code)) return
          event.preventDefault()
          if (event.code === 'KeyR') actions.current?.reset()
          else keys.add(event.code)
        }, { signal })
        window.addEventListener('keyup', (event) => keys.delete(event.code), { signal })

        let pointer: { id: number; x: number; y: number } | null = null
        canvas.addEventListener('pointerdown', (event) => {
          if (event.button !== 0 || !ready) return
          canvas.focus()
          canvas.setPointerCapture(event.pointerId)
          pointer = { id: event.pointerId, x: event.clientX, y: event.clientY }
        }, { signal })
        canvas.addEventListener('pointermove', (event) => {
          if (!pointer || pointer.id !== event.pointerId) return
          // 자유 비행: 월드의 수직축 대신 현재 카메라의 위·오른쪽 축으로 시선을 돌린다.
          camera.rotateY(-(event.clientX - pointer.x) * 0.003)
          camera.rotateX(-(event.clientY - pointer.y) * 0.003)
          pointer.x = event.clientX
          pointer.y = event.clientY
        }, { signal })
        const endPointer = () => { pointer = null }
        canvas.addEventListener('pointerup', endPointer, { signal })
        canvas.addEventListener('pointercancel', endPointer, { signal })
        canvas.addEventListener('lostpointercapture', endPointer, { signal })
        canvas.addEventListener('wheel', (event) => {
          event.preventDefault()
          if (!ready) return
          camera.translateZ(Math.sign(event.deltaY) * demoModel.moveSpeed * 0.15)
        }, { passive: false, signal })
        canvas.addEventListener('webglcontextlost', (event) => {
          event.preventDefault()
          renderer?.setAnimationLoop(null)
          setError('그래픽 연결이 끊겼습니다. 다른 GPU 작업을 줄이고 다시 불러와 주세요.')
          setState('error')
        }, { signal })

        splats = new SplatMesh({
          stream: response.body,
          streamLength: Number(response.headers.get('content-length')) || undefined,
          fileName: 'room.ply',
          lod: false,
          onProgress: (event) => {
            if (!disposed && event.total > 0) setProgress(Math.min(99, Math.round(event.loaded / event.total * 100)))
          },
        })
        splats.rotation.set(...demoModel.rotation)
        scene.add(splats)
        await splats.initialized
        if (disposed) { disposeMesh(); return }
        if (!splats.numSplats) throw new Error('표시할 가우시안이 없습니다. 3DGS용 PLY 결과 파일인지 확인해 주세요.')

        function resetCamera() {
          if (!splats) return
          splats.updateMatrixWorld(true)
          const bounds = splats.getBoundingBox().applyMatrix4(splats.matrixWorld)
          if (bounds.isEmpty()) throw new Error('모델의 공간 범위를 확인할 수 없습니다.')
          const center = bounds.getCenter(new THREE.Vector3())
          const size = bounds.getSize(new THREE.Vector3())
          camera.position.copy(center)
          if (demoModel.initialPosition) camera.position.set(...demoModel.initialPosition)
          camera.up.set(...demoModel.initialUp).normalize()
          if (demoModel.initialTarget) camera.lookAt(new THREE.Vector3(...demoModel.initialTarget))
          else camera.lookAt(camera.position.clone().add(new THREE.Vector3(...demoModel.initialDirection)))
          camera.far = Math.max(1000, size.length() * 3)
          camera.updateProjectionMatrix()
          keys.clear()
          canvas.focus()
        }
        resetCamera()
        actions.current = {
          reset: resetCamera,
          flip: () => { if (splats) { splats.rotation.x += Math.PI; resetCamera() } },
        }
        let lastTime = performance.now()
        const movement = new THREE.Vector3()
        renderer.setAnimationLoop((time) => {
          if (!renderer || disposed) return
          const delta = Math.min((time - lastTime) / 1000, 0.05)
          lastTime = time
          movement.set(Number(keys.has('KeyD')) - Number(keys.has('KeyA')), Number(keys.has('KeyE')) - Number(keys.has('KeyQ')), Number(keys.has('KeyS')) - Number(keys.has('KeyW')))
          if (movement.lengthSq()) {
            // 전후·좌우·상하는 모두 카메라 로컬 좌표. pitch·roll도 이동 방향에 반영한다.
            movement.normalize().applyQuaternion(camera.quaternion)
            const speed = demoModel.moveSpeed * (keys.has('ShiftLeft') || keys.has('ShiftRight') ? 2 : 1)
            camera.position.addScaledVector(movement, delta * speed)
          }
          renderer.render(scene, camera)
        })
        ready = true
        setState('ready')
        canvas.focus()
      } catch (reason) {
        disposeMesh()
        if (disposed || signal.aborted) return
        console.error('Demo model loading failed:', reason)
        setError(reason instanceof Error && !splats ? reason.message : '모델을 열지 못했습니다. 3DGS PLY 형식과 파일 손상 여부를 확인해 주세요.')
        setState('error')
        renderer?.setAnimationLoop(null)
      }
    }
    void load()
    return () => {
      disposed = true
      ready = false
      actions.current = null
      keys.clear()
      controller.abort()
      resize?.disconnect()
      renderer?.setAnimationLoop(null)
      if (splats?.isInitialized) disposeMesh()
      spark?.dispose()
      renderer?.dispose()
      renderer?.forceContextLoss()
      renderer?.domElement.remove()
    }
  }, [attempt])

  return (
    <div className="demo-viewer-body">
      <div ref={container} className="demo-viewer-canvas" />
      {state === 'loading' && <div className="demo-viewer-status" role="status"><span className="demo-spinner" /><h3>방을 불러오고 있어요{progress !== null ? ` · ${progress}%` : ''}</h3><p>파일 크기에 따라 시간이 걸릴 수 있습니다.</p></div>}
      {state === 'error' && <div className="demo-viewer-status" role="alert"><span className="demo-error-symbol" aria-hidden="true">!</span><h3>방을 열 수 없습니다</h3><p>{error}</p><button className="demo-primary" onClick={() => setAttempt((value) => value + 1)}>다시 불러오기</button></div>}
      {state === 'ready' && <>
        <div className="demo-viewer-actions"><button onClick={() => actions.current?.reset()}>처음 위치 <kbd>R</kbd></button><button onClick={() => actions.current?.flip()}>위아래 반전</button></div>
        <div className="demo-viewer-help"><span>드래그 <b>시선 변경</b></span><span><kbd>W</kbd><kbd>A</kbd><kbd>S</kbd><kbd>D</kbd> 시선 기준 비행</span><span><kbd>Q</kbd><kbd>E</kbd> 화면 기준 상하</span><span>휠 앞뒤 이동</span><small>자유 탐색 데모 · 벽 충돌·실내 이동 제한 없음</small></div>
      </>}
    </div>
  )
}
