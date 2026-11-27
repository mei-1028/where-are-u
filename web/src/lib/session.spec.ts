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

  it('permits plaintext HTTP to any host in development', () => {
    const devLoopback = validateApiBase('http://localhost:3000/api', {
      dev: true,
      currentOrigin: 'http://localhost:5173',
    })
    const devLan = validateApiBase('http://192.168.0.10:3000/api', {
      dev: true,
      currentOrigin: 'http://localhost:5173',
    })
    const devPublic = validateApiBase('http://home.example.com:3000/api', {
      dev: true,
      currentOrigin: 'http://localhost:5173',
    })
    const devPublicIp = validateApiBase('http://203.0.113.7:3000/api', {
      dev: true,
      currentOrigin: 'http://localhost:5173',
    })
    expect(devLoopback.ok).toBe(true)
    expect(devLan.ok).toBe(true)
    expect(devPublic).toEqual({ ok: true, value: 'http://home.example.com:3000/api' })
    expect(devPublicIp.ok).toBe(true)
  })

  it('still rejects plaintext HTTP outside development', () => {
    expect(
      validateApiBase('http://192.168.0.10:3000/api', {
        dev: false,
        currentOrigin: 'https://app.example',
      }).ok,
    ).toBe(false)
    expect(
      validateApiBase('http://home.example.com/api', {
        dev: false,
        currentOrigin: 'https://app.example',
      }).ok,
    ).toBe(false)
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
  it('stores plaintext HTTP overrides in development', () => {
    const result = setApiBaseOverride('http://203.0.113.7:3000/api')
    expect(result).toEqual({ ok: true, value: 'http://203.0.113.7:3000/api' })
    expect(localStorage.getItem(API_BASE_KEY)).toBe('http://203.0.113.7:3000/api')
    clearApiBaseOverride()
  })

  it('stores trusted overrides and can clear them', () => {
    const result = setApiBaseOverride('https://api.example.test/api/')
    expect(result).toEqual({ ok: true, value: 'https://api.example.test/api' })
    expect(localStorage.getItem(API_BASE_KEY)).toBe('https://api.example.test/api')
    clearApiBaseOverride()
    expect(localStorage.getItem(API_BASE_KEY)).toBeNull()
  })

  it('drops a tampered value that is invalid in every mode', () => {
    localStorage.setItem(API_BASE_KEY, 'https://user:pass@api.example/api')
    const base = getApiBase()
    expect(base).not.toContain('user:pass')
    expect(localStorage.getItem(API_BASE_KEY)).toBeNull()
  })
})
