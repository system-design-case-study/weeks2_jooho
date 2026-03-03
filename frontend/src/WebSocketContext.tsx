import { createContext, useContext, type ReactNode } from 'react';
import { ConnectionStatus, type WebSocketContextValue } from './types';
import { useWebSocket } from './useWebSocket';

const WebSocketContext = createContext<WebSocketContextValue | null>(null);

interface WebSocketProviderProps {
  userId: string;
  children: ReactNode;
}

export function WebSocketProvider({ userId, children }: WebSocketProviderProps) {
  const ws = useWebSocket(userId);

  return (
    <WebSocketContext.Provider value={ws}>{children}</WebSocketContext.Provider>
  );
}

export function useWebSocketContext(): WebSocketContextValue {
  const context = useContext(WebSocketContext);
  if (!context) {
    throw new Error(
      'useWebSocketContext는 WebSocketProvider 내부에서 사용해야 합니다'
    );
  }
  return context;
}

export { ConnectionStatus };
