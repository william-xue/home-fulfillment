import { shallowRef } from 'vue'

export interface Profile { username: string; role: 'OPS' | 'WAREHOUSE' | 'SUPPORT'; warehouseCodes: string[] }
export interface Page<T> { items: T[]; total: number; page: number; size: number }
export interface Entry { code: string; name: string }
export interface Bin extends Entry { type: 'RECEIVING' | 'PICKABLE' }
export const profile = shallowRef<Profile | null>(null)

export class ApiError extends Error {
  constructor(readonly status: number, message: string, readonly requestId?: string) { super(message) }
}

export function message(error: unknown) {
  return error instanceof ApiError && error.requestId
    ? `${error.message}（请求编号：${error.requestId}）`
    : error instanceof Error ? error.message : '请求失败，请刷新重试'
}

export async function get<T>(path: string, params?: Record<string, string | number>) {
  const query = params ? `?${new URLSearchParams(Object.entries(params).map(([key, value]) => [key, String(value)]))}` : ''
  return request<T>(path + query)
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api/v1${path}`, { ...init, signal: AbortSignal.timeout(8000), cache: 'no-store' })
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    if (response.status === 401) profile.value = null
    throw new ApiError(response.status, body.message || '请求未完成', body.requestId)
  }
  return response.status === 204 ? undefined as T : response.json()
}

async function post<T>(path: string, body?: string, key?: string) {
  const csrf = await get<{ headerName: string; token: string }>('/auth/csrf')
  const headers: Record<string, string> = { [csrf.headerName]: csrf.token }
  if (body) headers['Content-Type'] = 'application/json'
  if (key) headers['Idempotency-Key'] = key
  return request<T>(path, { method: 'POST', headers, body })
}

export async function login(username: string, password: string) {
  profile.value = await post<Profile>('/auth/login', JSON.stringify({ username, password }))
  return profile.value
}

export async function logout() {
  await post<void>('/auth/logout')
  profile.value = null
}

function pendingSlot(path: string) { return `hf:pending:${profile.value?.username}:${path}` }
type Pending = { body: string | null; key: string }
export function pendingCommand(path: string): Pending | null {
  const saved = sessionStorage.getItem(pendingSlot(path))
  return saved ? JSON.parse(saved) as Pending : null
}

export async function command<T>(path: string, payload?: object | null) {
  const slot = pendingSlot(path)
  const previous = pendingCommand(path)
  const body = payload === undefined ? previous?.body : payload === null ? null : JSON.stringify(payload)
  if (body === undefined) throw new Error('没有待重试的请求')
  if (previous && previous.body !== body) throw new Error('上次操作结果未知，请先重试原请求，确认后再提交新操作')
  const key = previous?.key || crypto.randomUUID()
  sessionStorage.setItem(slot, JSON.stringify({ body, key }))
  try {
    const result = await post<T>(path, body ?? undefined, key)
    sessionStorage.removeItem(slot)
    return result
  } catch (error) {
    if (error instanceof ApiError && error.status < 500 && error.status !== 401 && error.status !== 403) {
      sessionStorage.removeItem(slot)
      throw error
    }
    if (error instanceof ApiError && error.status < 500) throw error
    throw new Error('结果尚未确认。刷新查看单据，或用「重试原请求」补查；不要另开一笔。')
  }
}
