import { api } from './http'
import type { SystemHealth, SystemInfo } from '../types/models'

export const systemApi = {
  health: () => api.get<SystemHealth>('/v1/system/health'),
  info: () => api.get<SystemInfo>('/v1/system/info'),
}
