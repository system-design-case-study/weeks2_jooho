import { useState } from 'react';
import { useWebSocketContext } from './WebSocketContext';
import './UserListPanel.css';

const API_BASE = '/api';

function generateColor(userId: string): string {
  const hash = userId.split('').reduce((acc, char) => acc + char.charCodeAt(0), 0);
  const hue = hash % 360;
  return `hsl(${hue}, 70%, 50%)`;
}

export function UserListPanel() {
  const { userId, userList, canvasDataRef, friendVersion } =
    useWebSocketContext();

  const [loadingIds, setLoadingIds] = useState<Set<string>>(new Set());
  const [errorIds, setErrorIds] = useState<Map<string, string>>(new Map());

  function isFriend(targetId: string): boolean {
    return canvasDataRef.current?.friends.has(targetId) ?? false;
  }

  function setLoading(targetId: string, loading: boolean) {
    setLoadingIds(prev => {
      const next = new Set(prev);
      if (loading) next.add(targetId);
      else next.delete(targetId);
      return next;
    });
  }

  function setError(targetId: string, message: string | null) {
    setErrorIds(prev => {
      const next = new Map(prev);
      if (message) next.set(targetId, message);
      else next.delete(targetId);
      return next;
    });
  }

  async function handleAddFriend(targetId: string) {
    setLoading(targetId, true);
    setError(targetId, null);
    try {
      const response = await fetch(`${API_BASE}/friends?userId=${userId}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ friendId: targetId }),
      });
      if (!response.ok) {
        throw new Error(`친구 추가 실패 (${response.status})`);
      }
    } catch (err) {
      setError(targetId, err instanceof Error ? err.message : '친구 추가 중 오류가 발생했습니다');
    } finally {
      setLoading(targetId, false);
    }
  }

  async function handleRemoveFriend(targetId: string) {
    setLoading(targetId, true);
    setError(targetId, null);
    try {
      const response = await fetch(
        `${API_BASE}/friends/${targetId}?userId=${userId}`,
        { method: 'DELETE' }
      );
      if (!response.ok) {
        throw new Error(`친구 제거 실패 (${response.status})`);
      }
    } catch (err) {
      setError(targetId, err instanceof Error ? err.message : '친구 제거 중 오류가 발생했습니다');
    } finally {
      setLoading(targetId, false);
    }
  }

  return (
    <div className="user-list-panel">
      <div className="user-list-header">접속자 목록</div>
      <div className="user-list-content">
        {userList.length === 0 ? (
          <div className="user-list-empty">접속 중인 사용자가 없습니다</div>
        ) : (
          <ul className="user-list">
            {userList.map(user => {
              const isMe = user.id === userId;
              const friend = isFriend(user.id);
              const loading = loadingIds.has(user.id);
              const error = errorIds.get(user.id);

              return (
                <li key={user.id} className={`user-list-item${isMe ? ' user-list-item--me' : ''}`}>
                  <span
                    className="user-dot"
                    style={{ backgroundColor: generateColor(user.id) }}
                  />
                  <span className="user-id">
                    {user.id}
                    {isMe && <span className="user-me-badge">(나)</span>}
                  </span>
                  {!isMe && (
                    <button
                      className={`friend-btn${friend ? ' friend-btn--remove' : ' friend-btn--add'}`}
                      onClick={() =>
                        friend ? handleRemoveFriend(user.id) : handleAddFriend(user.id)
                      }
                      disabled={loading}
                    >
                      {loading ? '처리 중...' : friend ? '친구 제거' : '친구 추가'}
                    </button>
                  )}
                  {error && <div className="user-error">{error}</div>}
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </div>
  );
}
