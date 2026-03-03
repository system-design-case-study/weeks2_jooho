import { useEffect, useRef } from 'react';
import { useWebSocketContext } from './WebSocketContext';
import type { PropagationLog, PropagationSubscriber } from './types';
import './PropagationLogPanel.css';

function formatTime(timestamp: number): string {
  const d = new Date(timestamp);
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  const ss = String(d.getSeconds()).padStart(2, '0');
  const ms = String(d.getMilliseconds()).padStart(3, '0');
  return `${hh}:${mm}:${ss}.${ms}`;
}

function getNodeType(nodeId: string): 'user' | 'ws' | 'redis' {
  if (nodeId.startsWith('ws-')) return 'ws';
  if (nodeId.startsWith('redis')) return 'redis';
  return 'user';
}

function LogNode({ nodeId }: { nodeId: string }) {
  const type = getNodeType(nodeId);
  return (
    <span className={`propagation-log-node propagation-log-node--${type}`}>
      {nodeId}
    </span>
  );
}

function Arrow() {
  return <span className="propagation-log-arrow">→</span>;
}

function SubscriberRow({ sub, targetWsServer }: { sub: PropagationSubscriber; targetWsServer: string }) {
  return (
    <div className="propagation-log-subscriber">
      <div className="propagation-log-subscriber-path">
        <LogNode nodeId={targetWsServer} />
        <Arrow />
        <LogNode nodeId={sub.userId} />
      </div>
      <span className="propagation-log-distance">dist:{sub.distance}</span>
      <span className={`propagation-log-badge ${sub.inRange ? 'propagation-log-badge--inrange' : 'propagation-log-badge--outrange'}`}>
        {sub.inRange ? 'inRange' : 'outRange'}
      </span>
      <span className={`propagation-log-badge ${sub.sent ? 'propagation-log-badge--sent' : 'propagation-log-badge--notsent'}`}>
        {sub.sent ? 'sent' : 'skip'}
      </span>
    </div>
  );
}

function LogItem({ log }: { log: PropagationLog }) {
  return (
    <div className="propagation-log-item">
      <div className="propagation-log-time">{formatTime(log.timestamp)}</div>
      <div className="propagation-log-path">
        <LogNode nodeId={log.sourceUser} />
        <Arrow />
        <LogNode nodeId={log.wsServer} />
        <Arrow />
        <LogNode nodeId={log.redisNode} />
      </div>
      {log.subscribers.length > 0 && (
        <div className="propagation-log-subscribers">
          {log.subscribers.map((sub, i) => (
            <SubscriberRow key={i} sub={sub} targetWsServer={sub.wsServer} />
          ))}
        </div>
      )}
    </div>
  );
}

export function PropagationLogPanel() {
  const { propagationLogs } = useWebSocketContext();
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = listRef.current;
    if (!el) return;
    el.scrollTop = 0;
  }, [propagationLogs]);

  return (
    <div className="propagation-log-panel">
      <div ref={listRef} className="propagation-log-list">
        {propagationLogs.length === 0 ? (
          <div className="propagation-log-empty">전파 로그 없음</div>
        ) : (
          propagationLogs.map((log) => <LogItem key={log.id} log={log} />)
        )}
      </div>
    </div>
  );
}
