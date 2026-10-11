import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import App from './App'

describe('App', () => {
  it('renders the Station Gate screen marked as demo only', () => {
    render(<App />)

    expect(screen.getByRole('heading', { name: 'Station Gate' })).toBeInTheDocument()
    expect(screen.getByText(/demo only/i)).toBeInTheDocument()
  })
})
