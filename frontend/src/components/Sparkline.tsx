import { Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { MetricPoint } from '../types/models'

export function Sparkline({
  data,
  color = '#e6a23c',
}: {
  data?: MetricPoint[]
  color?: string
}) {
  const points = (data ?? []).map((point) => ({
    t: typeof point.t === 'number' ? point.t : Number(point.t),
    v: point.v,
  }))
  if (points.length === 0) {
    return <div className="flex h-48 items-center justify-center text-sm text-mute">No series yet</div>
  }
  return (
    <ResponsiveContainer width="100%" height={192}>
      <LineChart data={points}>
        <XAxis dataKey="t" hide />
        <YAxis hide domain={['auto', 'auto']} />
        <Tooltip
          contentStyle={{ background: '#12161d', border: '1px solid #2a3140', fontSize: 12 }}
          labelFormatter={(value) => new Date(Number(value) * 1000).toLocaleTimeString()}
        />
        <Line type="monotone" dataKey="v" stroke={color} dot={false} strokeWidth={1.6} />
      </LineChart>
    </ResponsiveContainer>
  )
}
