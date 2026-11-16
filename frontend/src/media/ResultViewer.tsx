import { useEffect, useRef, useState } from 'react'
import { SparkRenderer, SplatMesh } from '@sparkjsdev/spark'
import { Color, PerspectiveCamera, Scene, Vector3, WebGLRenderer } from 'three'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import { objectTransfer, type Transfer } from './api'
import './ResultViewer.css'

export default function ResultViewer({ transfer }: { transfer: Transfer }) {
  const hostRef = useRef<HTMLDivElement>(null)
  const [status, setStatus] = useState('PLY를 내려받고 있습니다…')

  useEffect(() => {
    if (!hostRef.current) return
    const host = hostRef.current
    const abort = new AbortController()
    let renderer: WebGLRenderer | undefined
    let spark: SparkRenderer | undefined
    let mesh: SplatMesh | undefined
    let controls: OrbitControls | undefined
    let resize: ResizeObserver | undefined
    function disposeMesh() {
      mesh?.dispose()
      mesh = undefined
    }

    async function load() {
      setStatus('PLY를 내려받고 있습니다…')
      try {
        renderer = new WebGLRenderer()
      } catch {
        setStatus('WebGL2를 사용할 수 없습니다. 브라우저의 하드웨어 가속을 확인하세요.')
        return
      }
      try {
        const response = await objectTransfer(transfer, 'GET', undefined, abort.signal)
        if (abort.signal.aborted) return
        if (!response.body || !Number.isSafeInteger(transfer.sizeBytes)
            || transfer.sizeBytes <= 0 || transfer.sizeBytes > 256 * 1024 * 1024) {
          throw new Error('PLY 크기가 올바르지 않습니다.')
        }
        // PLY 전체를 별도 버퍼에 담지 않고, 뷰어에 전달하면서 실제 바이트 수를 확인한다.
        let size = 0
        const stream = response.body.pipeThrough(new TransformStream<Uint8Array, Uint8Array>({
          transform(chunk, controller) {
            size += chunk.byteLength
            if (size > transfer.sizeBytes) throw new Error('Unexpected PLY size')
            controller.enqueue(chunk)
          },
          flush() {
            if (size !== transfer.sizeBytes) throw new Error('Unexpected PLY size')
          },
        }))
        const scene = new Scene()
        scene.background = new Color('#17232c')
        const camera = new PerspectiveCamera(60, 1, 0.01, 1000)
        renderer.setPixelRatio(Math.min(window.devicePixelRatio, 1.5))
        host.appendChild(renderer.domElement)
        spark = new SparkRenderer({ renderer })
        scene.add(spark)
        mesh = new SplatMesh({ stream, streamLength: transfer.sizeBytes, fileName: 'room.ply', lod: false })
        scene.add(mesh)
        try {
          await mesh.initialized
        } catch {
          throw new Error('PLY를 열 수 없습니다. 3DGS용 PLY 결과인지 확인하세요.')
        } finally {
          // 초기화 도중 화면을 닫으면 파싱이 끝난 시점에 메시를 해제한다.
          if (abort.signal.aborted) disposeMesh()
        }
        if (abort.signal.aborted || !mesh) return
        const bounds = mesh.getBoundingBox()
        const radius = bounds.getSize(new Vector3()).length()
        if (!mesh.numSplats || !Number.isFinite(radius) || radius <= 0) {
          throw new Error('표시할 수 있는 3DGS 데이터가 없습니다.')
        }
        // 모델 전체가 보이도록 미리보기 카메라를 잡는다. 투어의 시작 카메라와는 별개다.
        const center = bounds.getCenter(new Vector3())
        camera.position.copy(center).addScaledVector(new Vector3(0.55, 0.35, 1), radius)
        camera.near = Math.max(0.001, radius / 1000)
        camera.far = Math.max(100, radius * 25)
        controls = new OrbitControls(camera, renderer.domElement)
        controls.target.copy(center)
        controls.update()
        resize = new ResizeObserver(() => {
          if (!renderer || !host.clientWidth || !host.clientHeight) return
          camera.aspect = host.clientWidth / host.clientHeight
          camera.updateProjectionMatrix()
          renderer.setSize(host.clientWidth, host.clientHeight)
        })
        resize.observe(host)
        renderer.setAnimationLoop(() => renderer?.render(scene, camera))
        setStatus('')
      } catch (error) {
        if (!abort.signal.aborted) {
          disposeMesh()
          setStatus(error instanceof Error ? error.message : '결과를 불러오지 못했습니다.')
        }
      }
    }
    void load()
    return () => {
      abort.abort()
      resize?.disconnect()
      controls?.dispose()
      if (mesh?.isInitialized) disposeMesh()
      renderer?.setAnimationLoop(null)
      spark?.dispose()
      renderer?.dispose()
      renderer?.domElement.remove()
    }
  }, [transfer])

  return (
    <div className="model-preview">
      <div ref={hostRef} className="model-canvas" aria-label="PLY 결과 미리보기" />
      {status && <p role="status">{status}</p>}
    </div>
  )
}
