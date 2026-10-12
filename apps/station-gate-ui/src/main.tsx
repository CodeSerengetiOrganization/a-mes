import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { createFakeStationGateClient } from './api/fakeClient'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App client={createFakeStationGateClient()} />
  </StrictMode>,
)
