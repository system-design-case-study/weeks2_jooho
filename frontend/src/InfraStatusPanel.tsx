import { useCallback, useEffect, useRef, useState } from 'react';
import { useWebSocketContext } from './WebSocketContext';
import './InfraStatusPanel.css';

const POLL_INTERVAL_MS = 5000;
const CALL_COUNT = 3;

// /api/system/connections 응답 형식
interface ConnectionsResponse {
  serverId: string;
  users: Array<{ userId: string; x: number; y: number }>;
  totalConnections: number;
}

// /api/system/channels 응답 형식
// { [nodeName]: { channels: string[], subscriberCount: number } }
type ChannelsResponse = Record<string, { channels: string[]; subscriberCount: number; inHashRing: boolean }>;

// /api/system/cache 응답 형식
interface CacheEntry {
  userId: string;
  x: number;
  y: number;
  timestamp: number;
  ttl: number;
}

interface SystemData {
  connections: ConnectionsResponse[];
  channels: ChannelsResponse;
  cache: CacheEntry[];
}

// WS서버별 색상 (최대 2개)
const WS_SERVER_COLORS: Record<string, string> = {
  'ws-1': '#4299e1',
  'ws-2': '#ed8936',
};
const DEFAULT_WS_COLOR = '#a0aec0';

// Redis 노드별 색상 (HashRingDiagram과 동일)
const REDIS_NODE_COLORS: Record<string, string> = {
  'redis-pubsub-1:6380': '#4299e1',
  'redis-pubsub-2:6381': '#ed8936',
  'redis-pubsub-3:6382': '#48bb78',
};
const DEFAULT_REDIS_COLOR = '#a0aec0';

function getWsColor(serverId: string): string {
  return WS_SERVER_COLORS[serverId] ?? DEFAULT_WS_COLOR;
}

function getRedisColor(nodeName: string): string {
  return REDIS_NODE_COLORS[nodeName] ?? DEFAULT_REDIS_COLOR;
}

async function fetchSystemData(): Promise<{
  connections: ConnectionsResponse[];
  channels: ChannelsResponse;
  cache: CacheEntry[];
}> {
  // round-robin으로 여러 WS서버 상태를 수집하기 위해 connections를 CALL_COUNT번 호출
  const connectionCalls = Array.from({ length: CALL_COUNT }, () =>
    fetch('/api/system/connections').then((r) => {
      if (!r.ok) throw new Error(`연결 상태 조회 실패 (${r.status})`);
      return r.json() as Promise<ConnectionsResponse>;
    })
  );

  const [connectionsRaw, channelsRaw, cacheRaw] = await Promise.all([
    Promise.all(connectionCalls),
    fetch('/api/system/channels').then((r) => {
      if (!r.ok) throw new Error(`채널 목록 조회 실패 (${r.status})`);
      return r.json() as Promise<ChannelsResponse>;
    }),
    fetch('/api/system/cache').then((r) => {
      if (!r.ok) throw new Error(`캐시 목록 조회 실패 (${r.status})`);
      return r.json() as Promise<CacheEntry[]>;
    }),
  ]);

  // serverId 기준으로 중복 제거
  const seenServerIds = new Set<string>();
  const connections: ConnectionsResponse[] = [];
  for (const conn of connectionsRaw) {
    if (!seenServerIds.has(conn.serverId)) {
      seenServerIds.add(conn.serverId);
      connections.push(conn);
    }
  }

  return { connections, channels: channelsRaw, cache: cacheRaw };
}

async function removeNodeFromHashRing(node: string): Promise<void> {
  const res = await fetch(`/api/system/remove-node?node=${encodeURIComponent(node)}`, { method: 'POST' });
  if (!res.ok) throw new Error(`노드 제거 실패 (${res.status})`);
}

async function addNodeToHashRing(node: string): Promise<void> {
  const res = await fetch(`/api/system/add-node?node=${encodeURIComponent(node)}`, { method: 'POST' });
  if (!res.ok) throw new Error(`노드 추가 실패 (${res.status})`);
}

export default function InfraStatusPanel() {
  const { userList } = useWebSocketContext();
  const [data, setData] = useState<SystemData | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const prevUserListLengthRef = useRef(userList.length);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await fetchSystemData();
      setData(result);
    } catch (err) {
      setError(err instanceof Error ? err.message : '시스템 상태 조회 실패');
    } finally {
      setLoading(false);
    }
  }, []);

  // 5초 주기 polling
  useEffect(() => {
    refresh();
    const timer = setInterval(refresh, POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [refresh]);

  // USER_LIST 메시지 수신 트리거 (접속/해제 이벤트)
  useEffect(() => {
    if (prevUserListLengthRef.current !== userList.length) {
      prevUserListLengthRef.current = userList.length;
      refresh();
    }
  }, [userList, refresh]);

  return (
    <div className="infra-panel">
      <div className="infra-panel-header">
        <span>인프라 상태</span>
        {loading && <span className="infra-panel-loading-dot" />}
      </div>

      {error && <div className="infra-panel-error">{error}</div>}

      <div className="infra-panel-body">
        {/* 섹션 1: WS서버 연결 상태 */}
        <div className="infra-section">
          <div className="infra-section-title">WS서버 연결 상태</div>
          {!data ? (
            <div className="infra-empty">데이터 로딩 중...</div>
          ) : data.connections.length === 0 ? (
            <div className="infra-empty">연결된 서버 없음</div>
          ) : (
            <div className="infra-server-grid">
              {data.connections.map((conn) => {
                const color = getWsColor(conn.serverId);
                return (
                  <div key={conn.serverId} className="infra-server-card">
                    <div className="infra-server-card-header">
                      <span
                        className="infra-color-dot"
                        style={{ backgroundColor: color }}
                      />
                      <span className="infra-server-name" style={{ color }}>
                        {conn.serverId}
                      </span>
                      <span className="infra-badge">{conn.totalConnections}명</span>
                    </div>
                    {conn.users.length === 0 ? (
                      <div className="infra-user-empty">접속자 없음</div>
                    ) : (
                      <ul className="infra-user-list">
                        {conn.users.map((u) => (
                          <li key={u.userId} className="infra-user-item">
                            <span className="infra-user-id">{u.userId}</span>
                            <span className="infra-user-pos">
                              ({Math.round(u.x)}, {Math.round(u.y)})
                            </span>
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* 섹션 2: Redis Pub/Sub 채널 */}
        <div className="infra-section">
          <div className="infra-section-title">Redis Pub/Sub 채널</div>
          {!data ? (
            <div className="infra-empty">데이터 로딩 중...</div>
          ) : Object.keys(data.channels).length === 0 ? (
            <div className="infra-empty">구독 채널 없음</div>
          ) : (
            <div className="infra-server-grid">
              {Object.entries(data.channels).map(([nodeName, nodeInfo]) => {
                const color = getRedisColor(nodeName);
                const inRing = nodeInfo.inHashRing;
                return (
                  <div key={nodeName} className="infra-server-card" style={!inRing ? { opacity: 0.5, borderColor: '#e53e3e' } : undefined}>
                    <div className="infra-server-card-header">
                      <span
                        className="infra-color-dot"
                        style={{ backgroundColor: inRing ? color : '#e53e3e' }}
                      />
                      <span className="infra-server-name" style={{ color: inRing ? color : '#e53e3e' }}>
                        {nodeName}
                      </span>
                      <span className="infra-badge">{nodeInfo.subscriberCount}채널</span>
                      {!inRing && <span className="infra-badge" style={{ backgroundColor: '#fed7d7', color: '#c53030' }}>제거됨</span>}
                    </div>
                    {nodeInfo.channels.length === 0 ? (
                      <div className="infra-user-empty">채널 없음</div>
                    ) : (
                      <ul className="infra-user-list">
                        {nodeInfo.channels.map((ch) => (
                          <li key={ch} className="infra-user-item">
                            <span className="infra-channel-name">{ch}</span>
                          </li>
                        ))}
                      </ul>
                    )}
                    <button
                      className={`infra-node-btn ${inRing ? 'infra-node-btn-remove' : 'infra-node-btn-add'}`}
                      onClick={async () => {
                        try {
                          if (inRing) {
                            await removeNodeFromHashRing(nodeName);
                          } else {
                            await addNodeToHashRing(nodeName);
                          }
                          refresh();
                        } catch (err) {
                          setError(err instanceof Error ? err.message : '노드 변경 실패');
                        }
                      }}
                    >
                      {inRing ? '노드 제거' : '노드 추가'}
                    </button>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* 섹션 3: Redis 캐시 */}
        <div className="infra-section">
          <div className="infra-section-title">
            Redis 캐시
            {data && (
              <span className="infra-section-count">{data.cache.length}개</span>
            )}
          </div>
          {!data ? (
            <div className="infra-empty">데이터 로딩 중...</div>
          ) : data.cache.length === 0 ? (
            <div className="infra-empty">캐시된 위치 없음</div>
          ) : (
            <table className="infra-cache-table">
              <thead>
                <tr>
                  <th>userId</th>
                  <th>x</th>
                  <th>y</th>
                  <th>TTL(s)</th>
                </tr>
              </thead>
              <tbody>
                {data.cache.map((entry) => (
                  <tr key={entry.userId}>
                    <td className="infra-cache-userid">{entry.userId}</td>
                    <td>{Math.round(entry.x)}</td>
                    <td>{Math.round(entry.y)}</td>
                    <td
                      className={
                        entry.ttl > 0 && entry.ttl < 10
                          ? 'infra-ttl-warn'
                          : 'infra-ttl-ok'
                      }
                    >
                      {entry.ttl < 0 ? '∞' : entry.ttl}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  );
}
