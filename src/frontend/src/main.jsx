import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { L10nProvider } from "@/l10n";

import './index.css'
import App from './App.jsx'

createRoot(document.getElementById('root')).render(
    <L10nProvider>
        <App />
    </L10nProvider>
)
