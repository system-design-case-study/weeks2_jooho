import { useState } from 'react';
import { WebSocketProvider, useWebSocketContext } from './WebSocketContext';
import { ConnectionStatus } from './types';
import LocationCanvas from './LocationCanvas';
import { UserListPanel } from './UserListPanel';
import HashRingDiagram from './HashRingDiagram';
import { PropagationLogPanel } from './PropagationLogPanel';
import InfraStatusPanel from './InfraStatusPanel';
import './App.css';

const SEARCH_RADIUS_MIN = 50;
const SEARCH_RADIUS_MAX = 500;

const STATUS_LABELS: Record<ConnectionStatus, string> = {
  [ConnectionStatus.CONNECTING]: '연결 중...',
  [ConnectionStatus.CONNECTED]: '연결됨',
  [ConnectionStatus.DISCONNECTED]: '연결 끊김',
  [ConnectionStatus.RECONNECTING]: '재연결 중...',
};

const STATUS_COLORS: Record<ConnectionStatus, string> = {
  [ConnectionStatus.CONNECTING]: '#f6ad55',
  [ConnectionStatus.CONNECTED]: '#48bb78',
  [ConnectionStatus.DISCONNECTED]: '#fc8181',
  [ConnectionStatus.RECONNECTING]: '#f6ad55',
};

function AppContent() {
  const { connectionStatus, userId, canvasDataRef, systemState, searchRadius: initRadius } = useWebSocketContext();
  const [searchRadius, setSearchRadius] = useState(initRadius);

  return (
    <div className="app-layout">
      <div className="canvas-area">
        <div className="canvas-area-header">
          위치 맵
          <span style={{ marginLeft: 16, fontSize: 12, color: '#8892b0', fontWeight: 400 }}>
            검색 반경
            <input
              type="range"
              min={SEARCH_RADIUS_MIN}
              max={SEARCH_RADIUS_MAX}
              value={searchRadius}
              onChange={(e) => setSearchRadius(Number(e.target.value))}
              style={{ marginLeft: 8, marginRight: 6, verticalAlign: 'middle' }}
            />
            {searchRadius}
          </span>
        </div>
        <div className="canvas-container">
          <LocationCanvas canvasDataRef={canvasDataRef} userId={userId} searchRadius={searchRadius} />
        </div>
      </div>

      <UserListPanel />

      <div className="system-panel">
        <div className="system-panel-header">시스템 패널</div>
        <div className="system-panel-content">
          <div className="panel-section">
            <div className="panel-section-title">Hash Ring</div>
            <HashRingDiagram
              hashRingData={systemState?.hashRing ?? null}
              myChannel={userId ? `user:${userId}` : undefined}
            />
          </div>

          <div className="panel-section">
            <div className="panel-section-title">연결 상태</div>
            <div className="panel-section-placeholder">
              <span
                style={{
                  display: 'inline-block',
                  width: 8,
                  height: 8,
                  borderRadius: '50%',
                  backgroundColor: STATUS_COLORS[connectionStatus],
                  marginRight: 6,
                }}
              />
              {STATUS_LABELS[connectionStatus]}
              {userId && (
                <span style={{ marginLeft: 12, color: '#718096' }}>
                  {userId}
                </span>
              )}
            </div>
          </div>

          <div className="panel-section" style={{ minHeight: 200, maxHeight: 300, display: 'flex', flexDirection: 'column' }}>
            <div className="panel-section-title">전파 로그</div>
            <PropagationLogPanel />
          </div>

          <div className="panel-section" style={{ padding: 0 }}>
            <InfraStatusPanel />
          </div>
        </div>
      </div>
    </div>
  );
}

function LoginScreen({ onLogin }: { onLogin: (userId: string) => void }) {
  const [inputValue, setInputValue] = useState('');

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = inputValue.trim();
    if (trimmed) {
      onLogin(trimmed);
    }
  };

  return (
    <div style={{
      display: 'flex',
      justifyContent: 'center',
      alignItems: 'center',
      height: '100vh',
      backgroundColor: '#0a0a1a',
    }}>
      <form onSubmit={handleSubmit} style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 16,
        padding: 32,
        borderRadius: 12,
        backgroundColor: '#1a1a2e',
        border: '1px solid #2d3748',
        minWidth: 320,
      }}>
        <h2 style={{ color: '#e2e8f0', margin: 0, fontSize: 20 }}>Nearby Friends</h2>
        <p style={{ color: '#718096', margin: 0, fontSize: 13 }}>
          유저 ID를 입력하세요. 같은 ID로 재접속하면 친구 관계가 유지됩니다.
        </p>
        <input
          type="text"
          value={inputValue}
          onChange={(e) => setInputValue(e.target.value)}
          placeholder="예: alice, bob, charlie"
          autoFocus
          style={{
            padding: '10px 14px',
            borderRadius: 8,
            border: '1px solid #4a5568',
            backgroundColor: '#2d3748',
            color: '#e2e8f0',
            fontSize: 15,
            outline: 'none',
          }}
        />
        <button
          type="submit"
          disabled={!inputValue.trim()}
          style={{
            padding: '10px 14px',
            borderRadius: 8,
            border: 'none',
            backgroundColor: inputValue.trim() ? '#4299e1' : '#2d3748',
            color: inputValue.trim() ? '#fff' : '#718096',
            fontSize: 15,
            fontWeight: 600,
            cursor: inputValue.trim() ? 'pointer' : 'not-allowed',
          }}
        >
          접속
        </button>
      </form>
    </div>
  );
}

function App() {
  const [loggedInUserId, setLoggedInUserId] = useState<string | null>(null);

  if (!loggedInUserId) {
    return <LoginScreen onLogin={setLoggedInUserId} />;
  }

  return (
    <WebSocketProvider userId={loggedInUserId}>
      <AppContent />
    </WebSocketProvider>
  );
}

export default App;
