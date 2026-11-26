import { describe, expect, it } from 'vitest'
import {
  API_BASE_KEY,
  clearApiBaseOverride,
  getApiBase,
  parseMockFlag,
  setApiBaseOverride,
  validateApiBase,
} from './session'

describe('mock flag', () => {
  it('is off unless the value is exactly true', () => {
    expect(parseMockFlag(undefined)).toBe(false)
    expect(parseMockFlag(null)).toBe(false)
    expect(parseMockFlag('')).toBe(false)
    expect(parseMockFlag('false')).toBe(false)
    expect(parseMockFlag('1')).toBe(false)
    expect(parseMockFlag('true')).toBe(true)
    expect(parseMockFlag(' TRUE ')).toBe(true)
  })
})

describe('validateApiBase', () => {
  it('accepts same-origin relative paths', () => {
    expect(validateApiBase('/api', { currentOrigin: 'https://app.example' })).toEqual({
      ok: true,
      value: '/api',
    })
  })

  it('accepts absolute HTTPS bases and normalises trailing slashes', () => {
    expect(validateApiBase('https://api.example.test/v1/api/', { dev: false })).toEqual({
      ok: true,
      value: 'https://api.example.test/v1/api',
    })
  })

  it('rejects arbitrary plaintext HTTP in production', () => {
    const result = validateApiBase('http://evil.example/api', {
      dev: false,
      currentOrigin: 'https://app.example',
    })
    expect(result.ok).toBe(false)
  })

  it('permits loopback/private HTTP only in development', () => {
    const devLoopback = validateApiBase('http://localhost:3000/api', {
      dev: true,
      currentOrigin: 'http://localhost:5173',
    })
    const devLan = validateApiBase('http://192.168.0.10:3000/api', {
      dev: true,
      currentOrigin: 'http://localhost:5173',
    })
    const prodLan = validateApiBase('http://192.168.0.10:3000/api', {
      dev: false,
      currentOrigin: 'https://app.example',
    })
    expect(devLoopback.ok).toBe(true)
    expect(devLan.ok).toBe(true)
    expect(prodLan.ok).toBe(false)
  })

  it('rejects embedded credentials and query strings', () => {
    expect(
      validateApiBase('https://user:pass@api.example.test/api', { dev: false }).ok,
    ).toBe(false)
    expect(
      validateApiBase('https://api.example.test/api?token=1', { dev: false }).ok,
    ).toBe(false)
  })
})

describe('API base override lifecycle', () => {
  it('does not store an insecure override', () => {
    const result = setApiBaseOverride('http://evil.example/api')
    expect(result.ok).toBe(false)
    expect(localStorage.getItem(API_BASE_KEY)).toBeNull()
  })

  it('stores trusted overrides and can clear them', () => {
    const result = setApiBaseOverride('https://api.example.test/api/')
    expect(result).toEqual({ ok: true, value: 'https://api.example.test/api' })
    expect(localStorage.getItem(API_BASE_KEY)).toBe('https://api.example.test/api')
    clearApiBaseOverride()
    expect(localStorage.getItem(API_BASE_KEY)).toBeNull()
  })

  it('drops a tampered insecure value already in localStorage', () => {
    localStorage.setItem(API_BASE_KEY, 'http://evil.example/api')
    const base = getApiBase()
    expect(base).not.toContain('evil.example')
    expect(localStorage.getItem(API_BASE_KEY)).toBeNull()
  })
})
