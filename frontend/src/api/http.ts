const API_BASE = (import.meta.env.VITE_API_BASE_URL ?? 'http://127.0.0.1:8080').replace(/\/$/, '')
const ADMIN_TOKEN_KEY = 'throttl.adminToken'

export const adminToken = {
  get: () => localStorage.getItem(ADMIN_TOKEN_KEY) ?? '',
  set: (value: string) => {
    const trimmed = value.trim()
    if (trimmed) localStorage.setItem(ADMIN_TOKEN_KEY, trimmed)
    else localStorage.removeItem(ADMIN_TOKEN_KEY)
  },
}

export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const token = adminToken.get()
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(init?.headers ?? {}),
    },
  })
  if (response.status === 204) {
    return undefined as T
  }
  const text = await response.text()
  const body = text ? JSON.parse(text) : null
  if (!response.ok) {
    throw new ApiError(response.status, body?.message ?? response.statusText)
  }
  return body as T
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body: unknown) =>
    request<T>(path, { method: 'POST', body: JSON.stringify(body) }),
  put: <T>(path: string, body: unknown) =>
    request<T>(path, { method: 'PUT', body: JSON.stringify(body) }),
  patch: <T>(path: string, body: unknown) =>
    request<T>(path, { method: 'PATCH', body: JSON.stringify(body) }),
  delete: (path: string) => request<void>(path, { method: 'DELETE' }),
}
