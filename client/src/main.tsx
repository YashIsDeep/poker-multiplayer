import React from 'react'
import ReactDOM from 'react-dom/client'
import '@fontsource/pixelify-sans/400.css'
import '@fontsource/pixelify-sans/700.css'
import './styles/tokens.css'
import './styles/global.css'
import './styles/crt.css'
import './styles/animations.css'
import App from './App'

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)
