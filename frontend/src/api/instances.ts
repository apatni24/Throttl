import { api } from './http'
import type { InstanceSnapshot } from '../types/models'

export const instancesApi = {
  list: () => api.get<InstanceSnapshot[]>('/v1/instances'),
  get: (id: string) => api.get<InstanceSnapshot>(`/v1/instances/${id}`),
}
