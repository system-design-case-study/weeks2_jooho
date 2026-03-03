import { useCallback, useEffect, useRef, useState } from 'react';
import type {
  CanvasData,
  FriendPosition,
  PropagationLog,
  ServerMessage,
  SystemState,
  UserInfo,
  WebSocketActions,
  WebSocketState,
} from './types';
import { ConnectionStatus } from './types';

const INITIAL_RECONNECT_DELAY = 1000;
const MAX_RECONNECT_DELAY = 30000;
const RECONNECT_MULTIPLIER = 2;
const MAX_PROPAGATION_LOGS = 100;

interface UseWebSocketReturn extends WebSocketState, WebSocketActions {}

export function useWebSocket(inputUserId: string): UseWebSocketReturn {
  const [userId, setUserId] = useState('');
  const [connectionStatus, setConnectionStatus] = useState<ConnectionStatus>(
    ConnectionStatus.DISCONNECTED
  );
  const [userList, setUserList] = useState<UserInfo[]>([]);
  const [propagationLogs, setPropagationLogs] = useState<PropagationLog[]>([]);
  const [searchRadius, setSearchRadius] = useState(200);
  const [systemState, setSystemState] = useState<SystemState | null>(null);
  const [friendVersion, setFriendVersion] = useState(0);

  const canvasDataRef = useRef<CanvasData>({
    myPosition: { x: 0, y: 0 },
    friends: new Map<string, FriendPosition>(),
    allUsers: new Map(),
  });

  const wsRef = useRef<WebSocket | null>(null);
  const reconnectDelayRef = useRef(INITIAL_RECONNECT_DELAY);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const unmountedRef = useRef(false);
  const connectionIdRef = useRef(0);

  const clearReconnectTimer = useCallback(() => {
    if (reconnectTimerRef.current !== null) {
      clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
    }
  }, []);

  const handleMessage = useCallback((event: MessageEvent) => {
    let message: ServerMessage;
    try {
      message = JSON.parse(event.data) as ServerMessage;
    } catch {
      console.error('WebSocket 메시지 파싱 실패:', event.data);
      return;
    }

    switch (message.type) {
      case 'INIT': {
        setUserId(message.userId);
        setSearchRadius(message.searchRadius);

        canvasDataRef.current.myPosition = { x: message.x, y: message.y };

        const friendsMap = new Map<string, FriendPosition>();
        for (const f of message.friends) {
          friendsMap.set(f.id, {
            x: f.x,
            y: f.y,
            inRange: false,
            distance: 0,
          });
        }
        canvasDataRef.current.friends = friendsMap;
        break;
      }

      case 'FRIEND_LOCATION': {
        canvasDataRef.current.friends.set(message.friendId, {
          x: message.x,
          y: message.y,
          inRange: message.inRange,
          distance: message.distance,
        });
        break;
      }

      case 'USER_LIST': {
        setUserList(message.users);

        const allUsersMap = canvasDataRef.current.allUsers;
        const currentIds = new Set(message.users.map((u) => u.id));
        for (const id of allUsersMap.keys()) {
          if (!currentIds.has(id)) {
            allUsersMap.delete(id);
          }
        }
        break;
      }

      case 'PROPAGATION_LOG': {
        const log: PropagationLog = {
          id: crypto.randomUUID(),
          sourceUser: message.sourceUser,
          wsServer: message.wsServer,
          redisNode: message.redisNode,
          channel: message.channel,
          subscribers: message.subscribers,
          timestamp: Date.now(),
        };
        setPropagationLogs((prev) => {
          const next = [log, ...prev];
          if (next.length > MAX_PROPAGATION_LOGS) {
            return next.slice(0, MAX_PROPAGATION_LOGS);
          }
          return next;
        });
        break;
      }

      case 'SYSTEM_STATE': {
        setSystemState({
          myWsServer: message.myWsServer,
          hashRing: message.hashRing,
        });
        break;
      }

      case 'FRIEND_ADDED': {
        canvasDataRef.current.friends.set(message.friendId, {
          x: message.x,
          y: message.y,
          inRange: message.inRange,
          distance: message.distance,
        });
        setFriendVersion((v) => v + 1);
        break;
      }

      case 'FRIEND_REMOVED': {
        canvasDataRef.current.friends.delete(message.friendId);
        setFriendVersion((v) => v + 1);
        break;
      }
    }
  }, []);

  const connect = useCallback(() => {
    if (unmountedRef.current) return;

    clearReconnectTimer();
    setConnectionStatus(ConnectionStatus.CONNECTING);

    const myConnectionId = connectionIdRef.current;
    const wsUrl = `ws://${window.location.host}/ws/location?userId=${encodeURIComponent(inputUserId)}`;
    const ws = new WebSocket(wsUrl);
    wsRef.current = ws;

    ws.onopen = () => {
      if (unmountedRef.current || myConnectionId !== connectionIdRef.current) {
        ws.close();
        return;
      }
      setConnectionStatus(ConnectionStatus.CONNECTED);
      reconnectDelayRef.current = INITIAL_RECONNECT_DELAY;
    };

    ws.onmessage = handleMessage;

    ws.onclose = () => {
      if (unmountedRef.current || myConnectionId !== connectionIdRef.current) return;

      wsRef.current = null;
      setConnectionStatus(ConnectionStatus.RECONNECTING);

      const delay = reconnectDelayRef.current;
      reconnectDelayRef.current = Math.min(
        delay * RECONNECT_MULTIPLIER,
        MAX_RECONNECT_DELAY
      );

      reconnectTimerRef.current = setTimeout(() => {
        if (!unmountedRef.current && myConnectionId === connectionIdRef.current) {
          connect();
        }
      }, delay);
    };

    ws.onerror = () => {
      console.error('WebSocket 연결 오류 발생');
    };
  }, [clearReconnectTimer, handleMessage]);

  useEffect(() => {
    unmountedRef.current = false;
    connectionIdRef.current += 1;
    connect();

    return () => {
      unmountedRef.current = true;
      connectionIdRef.current += 1;
      clearReconnectTimer();
      if (wsRef.current) {
        wsRef.current.close();
        wsRef.current = null;
      }
      setConnectionStatus(ConnectionStatus.DISCONNECTED);
    };
  }, [connect, clearReconnectTimer]);

  const sendMessage = useCallback((data: unknown) => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify(data));
    }
  }, []);

  const sendLocationUpdate = useCallback(
    (x: number, y: number) => {
      sendMessage({ type: 'LOCATION_UPDATE', x, y });
    },
    [sendMessage]
  );

  const sendAddFriend = useCallback(
    (friendId: string) => {
      sendMessage({ type: 'ADD_FRIEND', friendId });
    },
    [sendMessage]
  );

  const sendRemoveFriend = useCallback(
    (friendId: string) => {
      sendMessage({ type: 'REMOVE_FRIEND', friendId });
    },
    [sendMessage]
  );

  return {
    userId,
    connectionStatus,
    userList,
    propagationLogs,
    searchRadius,
    systemState,
    friendVersion,
    canvasDataRef,
    sendLocationUpdate,
    sendAddFriend,
    sendRemoveFriend,
  };
}
