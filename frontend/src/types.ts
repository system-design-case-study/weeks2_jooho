export const ConnectionStatus = {
  CONNECTING: 'CONNECTING',
  CONNECTED: 'CONNECTED',
  DISCONNECTED: 'DISCONNECTED',
  RECONNECTING: 'RECONNECTING',
} as const;

export type ConnectionStatus =
  (typeof ConnectionStatus)[keyof typeof ConnectionStatus];

export interface Position {
  x: number;
  y: number;
}

export interface FriendPosition extends Position {
  inRange: boolean;
  distance: number;
}

export interface UserInfo {
  id: string;
  online: boolean;
}

export interface FriendInfo extends Position {
  id: string;
  online: boolean;
}

export interface PropagationSubscriber {
  userId: string;
  wsServer: string;
  distance: number;
  inRange: boolean;
  sent: boolean;
}

export interface PropagationLog {
  id: string;
  sourceUser: string;
  wsServer: string;
  redisNode: string;
  channel: string;
  subscribers: PropagationSubscriber[];
  timestamp: number;
}

export interface HashRingNode {
  position: number;
  server: string;
  virtual: boolean;
}

export interface SystemState {
  myWsServer: string;
  hashRing: {
    nodes: HashRingNode[];
    channelMapping: Record<string, string>;
  };
}

export interface InitMessage {
  type: 'INIT';
  userId: string;
  x: number;
  y: number;
  friends: FriendInfo[];
  searchRadius: number;
}

export interface FriendLocationMessage {
  type: 'FRIEND_LOCATION';
  friendId: string;
  x: number;
  y: number;
  timestamp: number;
  distance: number;
  inRange: boolean;
}

export interface UserListMessage {
  type: 'USER_LIST';
  users: UserInfo[];
}

export interface PropagationLogMessage {
  type: 'PROPAGATION_LOG';
  sourceUser: string;
  wsServer: string;
  redisNode: string;
  channel: string;
  subscribers: PropagationSubscriber[];
}

export interface SystemStateMessage {
  type: 'SYSTEM_STATE';
  myWsServer: string;
  hashRing: {
    nodes: HashRingNode[];
    channelMapping: Record<string, string>;
  };
}

export interface FriendAddedMessage {
  type: 'FRIEND_ADDED';
  friendId: string;
  x: number;
  y: number;
  distance: number;
  inRange: boolean;
}

export interface FriendRemovedMessage {
  type: 'FRIEND_REMOVED';
  friendId: string;
}

export type ServerMessage =
  | InitMessage
  | FriendLocationMessage
  | UserListMessage
  | PropagationLogMessage
  | SystemStateMessage
  | FriendAddedMessage
  | FriendRemovedMessage;

export interface LocationUpdateMessage {
  type: 'LOCATION_UPDATE';
  x: number;
  y: number;
}

export interface AddFriendMessage {
  type: 'ADD_FRIEND';
  friendId: string;
}

export interface RemoveFriendMessage {
  type: 'REMOVE_FRIEND';
  friendId: string;
}

export type ClientMessage =
  | LocationUpdateMessage
  | AddFriendMessage
  | RemoveFriendMessage;

export interface CanvasData {
  myPosition: Position;
  friends: Map<string, FriendPosition>;
  allUsers: Map<string, Position>;
}

export interface WebSocketState {
  userId: string;
  connectionStatus: ConnectionStatus;
  userList: UserInfo[];
  propagationLogs: PropagationLog[];
  searchRadius: number;
  systemState: SystemState | null;
  friendVersion: number;
}

export interface WebSocketActions {
  sendLocationUpdate: (x: number, y: number) => void;
  sendAddFriend: (friendId: string) => void;
  sendRemoveFriend: (friendId: string) => void;
  canvasDataRef: React.RefObject<CanvasData>;
}

export type WebSocketContextValue = WebSocketState & WebSocketActions;
