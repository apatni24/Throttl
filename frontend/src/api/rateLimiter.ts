import { api } from './http'
import type { BurstResult, RateLimitContext, RateLimitDecision } from '../types/models'

export const rateLimiterApi = {
  check: (context: RateLimitContext) =>
    api.post<RateLimitDecision>('/v1/check', context),
  burst: (context: RateLimitContext, count: number) =>
    api.post<BurstResult>('/v1/playground/burst', { context, count }),
}
