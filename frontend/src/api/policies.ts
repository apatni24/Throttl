import { api } from './http'
import type { Policy } from '../types/models'

export const policiesApi = {
  list: () => api.get<Policy[]>('/v1/policies'),
  get: (id: string) => api.get<Policy>(`/v1/policies/${id}`),
  create: (policy: Policy) => api.post<Policy>('/v1/policies', policy),
  update: (id: string, policy: Policy) => api.put<Policy>(`/v1/policies/${id}`, policy),
  remove: (id: string) => api.delete(`/v1/policies/${id}`),
  setEnabled: (id: string, enabled: boolean) =>
    api.patch<Policy>(`/v1/policies/${id}/enabled`, { enabled }),
}
