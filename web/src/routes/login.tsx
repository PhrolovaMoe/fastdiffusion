import { useEffect, useState } from 'react'
import { createFileRoute, useNavigate } from '@tanstack/react-router'
import { api, setToken } from '../lib/api'

export const Route = createFileRoute('/login')({ component: Login })
let exchange: Promise<{ token: string }> | undefined
function Login() {
  const [error, setError] = useState('')
  const [code] = useState(() =>
    typeof location !== 'undefined'
      ? new URLSearchParams(location.search).get('code')
      : null,
  )
  const navigate = useNavigate()
  useEffect(() => {
    if (!code) {
      setError('Open the login link from /fastdiffusion web.')
      return
    }
    // Remove the one-time secret from browser history immediately.
    history.replaceState(null, '', '/login')
    exchange ??= api<{ token: string }>('login', {
      method: 'POST',
      json: { code },
    })
    void exchange
      .then(({ token }) => {
        setToken(token)
        void navigate({ to: '/' })
      })
      .catch((e) => setError(e.message))
  }, [code, navigate])
  return (
    <main className="gate">
      <div>
        <h1>FastDiffusion</h1>
        <p>{error || 'Signing in…'}</p>
        {error && (
          <a className="btn" href="/">
            Back to map
          </a>
        )}
      </div>
    </main>
  )
}
