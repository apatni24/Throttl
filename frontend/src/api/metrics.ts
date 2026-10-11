import { api } from './http'
import type { AnalyticsMetrics, OverviewMetrics } from '../types/models'

export const metricsApi = {
  overview: () => api.get<OverviewMetrics>('/v1/metrics/overview'),
  analytics: (range: string) =>
    api.get<AnalyticsMetrics>(`/v1/metrics/analytics?range=${encodeURIComponent(range)}`),
}
