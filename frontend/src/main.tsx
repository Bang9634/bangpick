import { type ComponentType, StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'

const root = createRoot(document.getElementById('root')!)

function render(Page: ComponentType) {
  root.render(
    <StrictMode>
      <Page />
    </StrictMode>,
  )
}

if (import.meta.env.DEV) {
  void Promise.all([import('./dev/navigation'), import('./dev/DevPages')]).then(
    ([{ currentDevPath, DEV_HOME, DEV_AUTH }, { DevHome, DevAuth }]) => {
      const path = currentDevPath()
      if (path === DEV_HOME) render(DevHome)
      else if (path === DEV_AUTH) render(DevAuth)
      else render(App)
    },
  )
} else {
  render(App)
}
