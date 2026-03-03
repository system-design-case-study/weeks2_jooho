import { useState, useEffect, useCallback } from 'react';
import type { HashRingNode } from './types';
import './HashRingDiagram.css';

// SVG 레이아웃 상수
const SVG_WIDTH = 400;
const SVG_HEIGHT = 400;
const CENTER_X = 200;
const CENTER_Y = 200;
const RING_RADIUS = 160;
const PHYSICAL_NODE_RADIUS = 12;
const VIRTUAL_NODE_RADIUS = 3;
const CHANNEL_NODE_RADIUS = 5;
const LABEL_OFFSET = 20;
const CHANNEL_LABEL_OFFSET = 14;

// 색상 상수
const NODE_COLORS: Record<string, string> = {
  'redis-pubsub-1:6380': '#4299e1',
  'redis-pubsub-2:6381': '#ed8936',
  'redis-pubsub-3:6382': '#48bb78',
};
const DEFAULT_NODE_COLOR = '#a0aec0';
const MY_CHANNEL_COLOR = '#64ffda';
const OTHER_CHANNEL_COLOR = '#9f7aea';
const RING_COLOR = '#2d3748';
const RING_BG_COLOR = '#1a1a2e';
const CONNECTION_LINE_COLOR = '#4a5568';
const CONNECTION_LINE_HIGHLIGHT = '#64ffda';

// 최대 표시 채널 수 (너무 많으면 지저분)
const MAX_VISIBLE_CHANNELS = 30;

interface HashRingDiagramProps {
  hashRingData?: {
    nodes: Array<{ position: number; server: string; virtual: boolean }>;
    channelMapping: Record<string, string>;
  } | null;
  myChannel?: string;
}

interface RingPoint {
  x: number;
  y: number;
}

function positionToAngle(position: number): number {
  return (position / 0xffffffff) * 2 * Math.PI - Math.PI / 2;
}

function ringPoint(position: number, radius: number = RING_RADIUS): RingPoint {
  const angle = positionToAngle(position);
  return {
    x: CENTER_X + radius * Math.cos(angle),
    y: CENTER_Y + radius * Math.sin(angle),
  };
}

function getNodeColor(server: string): string {
  return NODE_COLORS[server] ?? DEFAULT_NODE_COLOR;
}

function getLabelPosition(position: number): RingPoint {
  const angle = positionToAngle(position);
  return {
    x: CENTER_X + (RING_RADIUS + LABEL_OFFSET) * Math.cos(angle),
    y: CENTER_Y + (RING_RADIUS + LABEL_OFFSET) * Math.sin(angle),
  };
}

function getChannelLabelPosition(position: number): RingPoint {
  const angle = positionToAngle(position);
  return {
    x: CENTER_X + (RING_RADIUS - CHANNEL_LABEL_OFFSET - CHANNEL_NODE_RADIUS) * Math.cos(angle),
    y: CENTER_Y + (RING_RADIUS - CHANNEL_LABEL_OFFSET - CHANNEL_NODE_RADIUS) * Math.sin(angle),
  };
}

interface FetchedHashRingData {
  nodes: HashRingNode[];
  channelMapping: Record<string, string>;
}

export default function HashRingDiagram({ hashRingData, myChannel }: HashRingDiagramProps) {
  const [fetchedData, setFetchedData] = useState<FetchedHashRingData | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hoveredNode, setHoveredNode] = useState<string | null>(null);
  const [hoveredChannel, setHoveredChannel] = useState<string | null>(null);

  const fetchHashRingData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const response = await fetch('/api/system/hash-ring');
      if (!response.ok) {
        throw new Error(`API 오류: ${response.status}`);
      }
      const data = await response.json() as FetchedHashRingData;
      setFetchedData(data);
    } catch (err) {
      console.error('Hash Ring 데이터 조회 실패:', err);
      setError('데이터를 불러올 수 없습니다');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!hashRingData) {
      fetchHashRingData();
    }
  }, [hashRingData, fetchHashRingData]);

  const data = hashRingData ?? fetchedData;

  if (loading) {
    return <div className="hash-ring-loading">Hash Ring 데이터 로딩 중...</div>;
  }

  if (error && !data) {
    return (
      <div className="hash-ring-error">
        <span>{error}</span>
        <button onClick={fetchHashRingData} className="hash-ring-retry-btn">
          다시 시도
        </button>
      </div>
    );
  }

  if (!data) {
    return <div className="hash-ring-empty">Hash Ring 데이터 없음</div>;
  }

  const physicalNodes = data.nodes.filter((n) => !n.virtual);
  const virtualNodes = data.nodes.filter((n) => n.virtual);

  // 채널 표시는 최대 MAX_VISIBLE_CHANNELS 개로 제한
  const channelEntries = Object.entries(data.channelMapping);
  const visibleChannels = channelEntries.slice(0, MAX_VISIBLE_CHANNELS);
  const hiddenChannelCount = channelEntries.length - visibleChannels.length;

  // 호버된 노드에 매핑된 채널들
  const nodeChannels = hoveredNode
    ? new Set(
        Object.entries(data.channelMapping)
          .filter(([, server]) => server === hoveredNode)
          .map(([channel]) => channel)
      )
    : null;

  // 물리 노드별 가상 노드 위치 계산 (호버 시 표시용)
  const virtualNodesByServer = hoveredNode
    ? virtualNodes.filter((n) => n.server === hoveredNode)
    : [];

  return (
    <div className="hash-ring-container">
      <svg
        viewBox={`0 0 ${SVG_WIDTH} ${SVG_HEIGHT}`}
        className="hash-ring-svg"
        aria-label="Hash Ring 다이어그램"
      >
        {/* 배경 */}
        <rect width={SVG_WIDTH} height={SVG_HEIGHT} fill={RING_BG_COLOR} />

        {/* 링 원 */}
        <circle
          cx={CENTER_X}
          cy={CENTER_Y}
          r={RING_RADIUS}
          fill="none"
          stroke={RING_COLOR}
          strokeWidth={2}
        />

        {/* 가상 노드 (기본: 숨김, 호버 시 표시) */}
        {virtualNodesByServer.map((node) => {
          const pt = ringPoint(node.position);
          return (
            <circle
              key={`virtual-${node.server}-${node.position}`}
              cx={pt.x}
              cy={pt.y}
              r={VIRTUAL_NODE_RADIUS}
              fill={getNodeColor(node.server)}
              opacity={0.6}
            />
          );
        })}

        {/* 채널 → 노드 연결선 */}
        {visibleChannels.map(([channel, server]) => {
          const channelHash = simpleChannelHash(channel);
          const channelPt = ringPoint(channelHash, RING_RADIUS);
          const physicalNode = physicalNodes.find((n) => n.server === server);
          if (!physicalNode) return null;

          const nodePt = ringPoint(physicalNode.position);
          const isMyChannel = channel === myChannel;
          const isHoveredChannelLine = channel === hoveredChannel;
          const isNodeHovered = nodeChannels?.has(channel) ?? false;
          const isHighlighted = isMyChannel || isHoveredChannelLine || isNodeHovered;

          return (
            <line
              key={`line-${channel}`}
              x1={channelPt.x}
              y1={channelPt.y}
              x2={nodePt.x}
              y2={nodePt.y}
              stroke={isHighlighted ? CONNECTION_LINE_HIGHLIGHT : CONNECTION_LINE_COLOR}
              strokeWidth={isHighlighted ? 1.5 : 0.5}
              opacity={isHighlighted ? 0.8 : 0.3}
            />
          );
        })}

        {/* 채널 점 */}
        {visibleChannels.map(([channel, server]) => {
          const channelHash = simpleChannelHash(channel);
          const pt = ringPoint(channelHash, RING_RADIUS);
          const isMyChannel = channel === myChannel;
          const isHovered = channel === hoveredChannel;
          const isNodeHovered = nodeChannels?.has(channel) ?? false;
          const isHighlighted = isMyChannel || isHovered || isNodeHovered;
          const labelPt = getChannelLabelPosition(channelHash);
          const color = isMyChannel ? MY_CHANNEL_COLOR : OTHER_CHANNEL_COLOR;

          return (
            <g
              key={`channel-${channel}`}
              onMouseEnter={() => setHoveredChannel(channel)}
              onMouseLeave={() => setHoveredChannel(null)}
              style={{ cursor: 'pointer' }}
            >
              <circle
                cx={pt.x}
                cy={pt.y}
                r={isHighlighted ? CHANNEL_NODE_RADIUS + 2 : CHANNEL_NODE_RADIUS}
                fill={color}
                stroke={isHighlighted ? '#fff' : 'none'}
                strokeWidth={1}
                opacity={0.85}
              />
              {(isHighlighted) && (
                <text
                  x={labelPt.x}
                  y={labelPt.y}
                  textAnchor="middle"
                  dominantBaseline="middle"
                  fill={color}
                  fontSize={8}
                  fontWeight="bold"
                >
                  {channel}
                </text>
              )}
            </g>
          );
        })}

        {/* 물리 노드 */}
        {physicalNodes.map((node) => {
          const pt = ringPoint(node.position);
          const labelPt = getLabelPosition(node.position);
          const color = getNodeColor(node.server);
          const isHovered = hoveredNode === node.server;

          return (
            <g
              key={`physical-${node.server}`}
              onMouseEnter={() => setHoveredNode(node.server)}
              onMouseLeave={() => setHoveredNode(null)}
              style={{ cursor: 'pointer' }}
            >
              <circle
                cx={pt.x}
                cy={pt.y}
                r={isHovered ? PHYSICAL_NODE_RADIUS + 3 : PHYSICAL_NODE_RADIUS}
                fill={color}
                stroke="#fff"
                strokeWidth={isHovered ? 2.5 : 1.5}
              />
              <text
                x={labelPt.x}
                y={labelPt.y}
                textAnchor="middle"
                dominantBaseline="middle"
                fill={color}
                fontSize={10}
                fontWeight="bold"
              >
                {node.server}
              </text>
            </g>
          );
        })}

        {/* 중앙 정보 텍스트 */}
        <text
          x={CENTER_X}
          y={CENTER_Y - 12}
          textAnchor="middle"
          dominantBaseline="middle"
          fill="#718096"
          fontSize={11}
        >
          {hoveredNode
            ? `${hoveredNode}`
            : hoveredChannel
              ? `${hoveredChannel}`
              : 'Hash Ring'}
        </text>
        <text
          x={CENTER_X}
          y={CENTER_Y + 8}
          textAnchor="middle"
          dominantBaseline="middle"
          fill="#4a5568"
          fontSize={9}
        >
          {hoveredNode
            ? `가상노드 ${virtualNodes.filter((n) => n.server === hoveredNode).length}개`
            : hoveredChannel
              ? `→ ${data.channelMapping[hoveredChannel] ?? ''}`
              : `물리 ${physicalNodes.length}개 / 가상 ${virtualNodes.length}개`}
        </text>
      </svg>

      {/* 범례 */}
      <div className="hash-ring-legend">
        {physicalNodes.map((node) => (
          <div key={node.server} className="hash-ring-legend-item">
            <span
              className="hash-ring-legend-dot"
              style={{ backgroundColor: getNodeColor(node.server) }}
            />
            <span className="hash-ring-legend-label">{node.server}</span>
          </div>
        ))}
        <div className="hash-ring-legend-item">
          <span
            className="hash-ring-legend-dot"
            style={{ backgroundColor: MY_CHANNEL_COLOR }}
          />
          <span className="hash-ring-legend-label">내 채널</span>
        </div>
        <div className="hash-ring-legend-item">
          <span
            className="hash-ring-legend-dot"
            style={{ backgroundColor: OTHER_CHANNEL_COLOR }}
          />
          <span className="hash-ring-legend-label">
            채널 ({visibleChannels.length}
            {hiddenChannelCount > 0 ? `/${channelEntries.length}` : ''}개)
          </span>
        </div>
      </div>
    </div>
  );
}

/**
 * 채널 문자열을 0~0xFFFFFFFF 범위의 해시값으로 변환 (시각화용 단순 해시)
 * 실제 Hash Ring 위치는 백엔드에서 계산하고, 이 함수는 시각화 위치 추정용
 */
function simpleChannelHash(channel: string): number {
  let hash = 0;
  for (let i = 0; i < channel.length; i++) {
    const char = channel.charCodeAt(i);
    hash = ((hash << 5) - hash + char) >>> 0;
  }
  return hash;
}
