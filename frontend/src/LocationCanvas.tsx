import { useEffect, useRef, useCallback } from 'react';
import type { CanvasData, FriendPosition } from './types';
import { useWebSocketContext } from './WebSocketContext';

const COORD_MIN = 0;
const COORD_MAX = 1000;
const GRID_INTERVAL = 100;

const SEARCH_RADIUS_FILL = 'rgba(0, 120, 255, 0.08)';
const SEARCH_RADIUS_STROKE = 'rgba(0, 120, 255, 0.3)';
const SEARCH_RADIUS_LINE_WIDTH = 1;

const DISTANCE_LABEL_FONT = '9px sans-serif';
const DISTANCE_LABEL_OFFSET_Y = 4;

const BACKGROUND_COLOR = '#f8f9fa';
const GRID_LINE_COLOR = '#dee2e6';
const GRID_LINE_WIDTH = 0.5;
const LABEL_COLOR = '#868e96';
const LABEL_FONT = '11px sans-serif';
const LABEL_OFFSET = 4;

const CHARACTER_SIZE = 16;
const CHARACTER_SELF_SCALE = 1.5;
const CHARACTER_SATURATION = 70;
const CHARACTER_LIGHTNESS = 50;
const NON_FRIEND_ALPHA = 0.3;

const NAME_LABEL_FONT = '10px sans-serif';
const NAME_LABEL_PADDING_X = 4;
const NAME_LABEL_PADDING_Y = 2;
const NAME_LABEL_OFFSET_Y = 6;
const NAME_LABEL_BG_COLOR = 'rgba(0, 0, 0, 0.5)';
const NAME_LABEL_TEXT_COLOR = '#ffffff';

const DRAG_THROTTLE_MS = 75;

interface LocationCanvasProps {
  canvasDataRef: React.RefObject<CanvasData>;
  userId: string;
  searchRadius: number;
}

function throttle<T extends unknown[]>(fn: (...args: T) => void, delay: number) {
  let lastCall = 0;
  return (...args: T) => {
    const now = Date.now();
    if (now - lastCall >= delay) {
      lastCall = now;
      fn(...args);
    }
  };
}

function toCanvasCoord(
  logicalValue: number,
  canvasSize: number
): number {
  return (logicalValue / COORD_MAX) * canvasSize;
}

export function logicalToCanvas(
  logicalX: number,
  logicalY: number,
  canvasEl: HTMLCanvasElement
): { x: number; y: number } {
  const rect = canvasEl.getBoundingClientRect();
  return {
    x: (logicalX / COORD_MAX) * rect.width,
    y: (logicalY / COORD_MAX) * rect.height,
  };
}

export function canvasToLogical(
  clientX: number,
  clientY: number,
  canvasEl: HTMLCanvasElement
): { x: number; y: number } {
  const rect = canvasEl.getBoundingClientRect();
  const x = ((clientX - rect.left) / rect.width) * COORD_MAX;
  const y = ((clientY - rect.top) / rect.height) * COORD_MAX;
  return {
    x: Math.max(COORD_MIN, Math.min(COORD_MAX, x)),
    y: Math.max(COORD_MIN, Math.min(COORD_MAX, y)),
  };
}

function generateColor(userId: string): string {
  const hash = userId.split('').reduce((acc, char) => acc + char.charCodeAt(0), 0);
  const hue = hash % 360;
  return `hsl(${hue}, ${CHARACTER_SATURATION}%, ${CHARACTER_LIGHTNESS}%)`;
}

function drawCharacter(
  ctx: CanvasRenderingContext2D,
  canvasX: number,
  canvasY: number,
  color: string,
  size: number,
  alpha: number
) {
  const savedAlpha = ctx.globalAlpha;
  ctx.globalAlpha = alpha;

  const half = size / 2;
  const pixel = size / 4;

  ctx.fillStyle = color;
  ctx.fillRect(canvasX - pixel, canvasY - half, pixel * 2, pixel);
  ctx.fillRect(canvasX - half, canvasY - half + pixel, size, pixel);
  ctx.fillRect(canvasX - half, canvasY - half + pixel * 2, size, pixel);
  ctx.fillRect(canvasX - pixel, canvasY - half + pixel * 3, pixel * 2, pixel);

  ctx.globalAlpha = savedAlpha;
}

function drawNameLabel(
  ctx: CanvasRenderingContext2D,
  canvasX: number,
  canvasY: number,
  name: string,
  characterSize: number,
  alpha: number
) {
  const savedAlpha = ctx.globalAlpha;
  ctx.globalAlpha = alpha;

  ctx.font = NAME_LABEL_FONT;
  const metrics = ctx.measureText(name);
  const textWidth = metrics.width;
  const textHeight = 10;

  const bgX = canvasX - textWidth / 2 - NAME_LABEL_PADDING_X;
  const bgY = canvasY - characterSize / 2 - textHeight - NAME_LABEL_OFFSET_Y - NAME_LABEL_PADDING_Y;
  const bgWidth = textWidth + NAME_LABEL_PADDING_X * 2;
  const bgHeight = textHeight + NAME_LABEL_PADDING_Y * 2;

  ctx.fillStyle = NAME_LABEL_BG_COLOR;
  ctx.fillRect(bgX, bgY, bgWidth, bgHeight);

  ctx.fillStyle = NAME_LABEL_TEXT_COLOR;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText(name, canvasX, bgY + bgHeight / 2);

  ctx.globalAlpha = savedAlpha;
}

function drawDotCharacter(
  ctx: CanvasRenderingContext2D,
  canvasX: number,
  canvasY: number,
  name: string,
  color: string,
  size: number,
  alpha: number
) {
  drawCharacter(ctx, canvasX, canvasY, color, size, alpha);
  drawNameLabel(ctx, canvasX, canvasY, name, size, alpha);
}

function drawSearchRadius(
  ctx: CanvasRenderingContext2D,
  canvasX: number,
  canvasY: number,
  logicalRadius: number,
  logicalSize: number
) {
  const canvasRadius = (logicalRadius / COORD_MAX) * logicalSize;

  ctx.beginPath();
  ctx.arc(canvasX, canvasY, canvasRadius, 0, Math.PI * 2);
  ctx.fillStyle = SEARCH_RADIUS_FILL;
  ctx.fill();
  ctx.strokeStyle = SEARCH_RADIUS_STROKE;
  ctx.lineWidth = SEARCH_RADIUS_LINE_WIDTH;
  ctx.stroke();
}

function drawDistanceLabel(
  ctx: CanvasRenderingContext2D,
  canvasX: number,
  canvasY: number,
  distance: number,
  characterSize: number
) {
  const label = `${Math.round(distance)}m`;
  ctx.font = DISTANCE_LABEL_FONT;
  ctx.fillStyle = NAME_LABEL_TEXT_COLOR;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'top';
  ctx.fillText(label, canvasX, canvasY + characterSize / 2 + DISTANCE_LABEL_OFFSET_Y);
}

function setupCanvas(canvas: HTMLCanvasElement): CanvasRenderingContext2D | null {
  const parent = canvas.parentElement;
  if (!parent) return null;

  const dpr = window.devicePixelRatio || 1;
  const size = Math.min(parent.clientWidth, parent.clientHeight);
  const logicalSize = size;

  canvas.width = logicalSize * dpr;
  canvas.height = logicalSize * dpr;
  canvas.style.width = `${logicalSize}px`;
  canvas.style.height = `${logicalSize}px`;

  const ctx = canvas.getContext('2d');
  if (!ctx) return null;

  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.scale(dpr, dpr);

  return ctx;
}

function drawGrid(ctx: CanvasRenderingContext2D, canvasSize: number) {
  ctx.fillStyle = BACKGROUND_COLOR;
  ctx.fillRect(0, 0, canvasSize, canvasSize);

  ctx.strokeStyle = GRID_LINE_COLOR;
  ctx.lineWidth = GRID_LINE_WIDTH;

  for (let i = COORD_MIN; i <= COORD_MAX; i += GRID_INTERVAL) {
    const pos = toCanvasCoord(i, canvasSize);

    ctx.beginPath();
    ctx.moveTo(pos, 0);
    ctx.lineTo(pos, canvasSize);
    ctx.stroke();

    ctx.beginPath();
    ctx.moveTo(0, pos);
    ctx.lineTo(canvasSize, pos);
    ctx.stroke();
  }

  ctx.fillStyle = LABEL_COLOR;
  ctx.font = LABEL_FONT;
  ctx.textBaseline = 'top';
  ctx.textAlign = 'left';

  for (let i = COORD_MIN; i <= COORD_MAX; i += GRID_INTERVAL) {
    const pos = toCanvasCoord(i, canvasSize);
    const label = String(i);

    if (i === COORD_MIN) {
      ctx.textAlign = 'left';
      ctx.textBaseline = 'top';
      ctx.fillText(label, pos + LABEL_OFFSET, LABEL_OFFSET);
    } else {
      ctx.textAlign = 'center';
      ctx.textBaseline = 'top';
      ctx.fillText(label, pos, LABEL_OFFSET);

      ctx.textAlign = 'left';
      ctx.textBaseline = 'middle';
      ctx.fillText(label, LABEL_OFFSET, pos);
    }
  }
}

/**
 * 클릭 위치가 자기 캐릭터 영역 내인지 판별한다.
 * @param clientX DOM 이벤트 X 좌표
 * @param clientY DOM 이벤트 Y 좌표
 * @param myLogicalX 자기 캐릭터의 논리 좌표 X
 * @param myLogicalY 자기 캐릭터의 논리 좌표 Y
 * @param canvas Canvas 엘리먼트
 */
function hitTestSelf(
  clientX: number,
  clientY: number,
  myLogicalX: number,
  myLogicalY: number,
  canvas: HTMLCanvasElement
): boolean {
  const rect = canvas.getBoundingClientRect();
  const clickLogicalX = ((clientX - rect.left) / rect.width) * COORD_MAX;
  const clickLogicalY = ((clientY - rect.top) / rect.height) * COORD_MAX;

  const selfSize = CHARACTER_SIZE * CHARACTER_SELF_SCALE;
  const halfSizeLogical = (selfSize / 2) * (COORD_MAX / rect.width);

  return (
    Math.abs(clickLogicalX - myLogicalX) <= halfSizeLogical &&
    Math.abs(clickLogicalY - myLogicalY) <= halfSizeLogical
  );
}

interface CanvasCache {
  ctx: CanvasRenderingContext2D;
  logicalSize: number;
}

export default function LocationCanvas({ canvasDataRef, userId, searchRadius }: LocationCanvasProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const animFrameRef = useRef<number>(0);
  const isDraggingRef = useRef(false);
  const searchRadiusRef = useRef(searchRadius);
  const canvasCacheRef = useRef<CanvasCache | null>(null);

  const { sendLocationUpdate } = useWebSocketContext();

  const throttledSendRef = useRef(
    throttle((x: number, y: number) => {
      sendLocationUpdate(x, y);
    }, DRAG_THROTTLE_MS)
  );

  useEffect(() => {
    searchRadiusRef.current = searchRadius;
  }, [searchRadius]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const applySetup = () => {
      const result = setupCanvas(canvas);
      if (!result) return;
      const dpr = window.devicePixelRatio || 1;
      canvasCacheRef.current = {
        ctx: result,
        logicalSize: canvas.width / dpr,
      };
    };

    applySetup();

    const observer = new ResizeObserver(() => {
      applySetup();
    });

    const parent = canvas.parentElement;
    if (parent) {
      observer.observe(parent);
    }

    return () => {
      observer.disconnect();
    };
  }, []);

  const render = useCallback(() => {
    const cache = canvasCacheRef.current;
    if (!cache) {
      animFrameRef.current = requestAnimationFrame(render);
      return;
    }

    const { ctx, logicalSize } = cache;

    ctx.clearRect(0, 0, logicalSize, logicalSize);
    drawGrid(ctx, logicalSize);

    const data = canvasDataRef.current;
    if (!data || !userId) {
      animFrameRef.current = requestAnimationFrame(render);
      return;
    }

    const myCx = toCanvasCoord(data.myPosition.x, logicalSize);
    const myCy = toCanvasCoord(data.myPosition.y, logicalSize);

    drawSearchRadius(ctx, myCx, myCy, searchRadiusRef.current, logicalSize);

    const friendIds = new Set(data.friends.keys());

    const nonFriends: Array<{ id: string; x: number; y: number }> = [];
    const inRangeFriends: Array<{ id: string; friend: FriendPosition }> = [];

    for (const [id, pos] of data.allUsers) {
      if (id === userId) continue;
      if (friendIds.has(id)) continue;
      nonFriends.push({ id, x: pos.x, y: pos.y });
    }

    for (const [id, friend] of data.friends) {
      const dx = data.myPosition.x - friend.x;
      const dy = data.myPosition.y - friend.y;
      const localDistance = Math.sqrt(dx * dx + dy * dy);
      if (localDistance <= searchRadiusRef.current) {
        inRangeFriends.push({ id, friend: { ...friend, distance: localDistance } });
      }
    }

    for (const { id, x, y } of nonFriends) {
      const cx = toCanvasCoord(x, logicalSize);
      const cy = toCanvasCoord(y, logicalSize);
      const color = generateColor(id);
      drawDotCharacter(ctx, cx, cy, id, color, CHARACTER_SIZE, NON_FRIEND_ALPHA);
    }

    for (const { id, friend } of inRangeFriends) {
      const cx = toCanvasCoord(friend.x, logicalSize);
      const cy = toCanvasCoord(friend.y, logicalSize);
      const color = generateColor(id);
      drawDotCharacter(ctx, cx, cy, id, color, CHARACTER_SIZE, 1.0);
      drawDistanceLabel(ctx, cx, cy, friend.distance, CHARACTER_SIZE);
    }

    {
      const color = generateColor(userId);
      const size = CHARACTER_SIZE * CHARACTER_SELF_SCALE;
      drawDotCharacter(ctx, myCx, myCy, userId, color, size, 1.0);
    }

    animFrameRef.current = requestAnimationFrame(render);
  }, [canvasDataRef, userId]);

  const handleMouseDown = useCallback((e: MouseEvent) => {
    const canvas = canvasRef.current;
    const data = canvasDataRef.current;
    if (!canvas || !data) return;

    if (hitTestSelf(e.clientX, e.clientY, data.myPosition.x, data.myPosition.y, canvas)) {
      isDraggingRef.current = true;
    }
  }, [canvasDataRef]);

  const handleMouseMove = useCallback((e: MouseEvent) => {
    if (!isDraggingRef.current) return;

    const canvas = canvasRef.current;
    const data = canvasDataRef.current;
    if (!canvas || !data) return;

    const { x, y } = canvasToLogical(e.clientX, e.clientY, canvas);
    data.myPosition.x = x;
    data.myPosition.y = y;
    throttledSendRef.current(x, y);
  }, [canvasDataRef]);

  const handleMouseUp = useCallback((e: MouseEvent) => {
    if (!isDraggingRef.current) return;

    isDraggingRef.current = false;

    const canvas = canvasRef.current;
    const data = canvasDataRef.current;
    if (!canvas || !data) return;

    const { x, y } = canvasToLogical(e.clientX, e.clientY, canvas);
    data.myPosition.x = x;
    data.myPosition.y = y;
    sendLocationUpdate(x, y);
  }, [canvasDataRef, sendLocationUpdate]);

  const handleTouchStart = useCallback((e: TouchEvent) => {
    const canvas = canvasRef.current;
    const data = canvasDataRef.current;
    if (!canvas || !data) return;

    const touch = e.touches[0];
    if (!touch) return;

    if (hitTestSelf(touch.clientX, touch.clientY, data.myPosition.x, data.myPosition.y, canvas)) {
      isDraggingRef.current = true;
      e.preventDefault();
    }
  }, [canvasDataRef]);

  const handleTouchMove = useCallback((e: TouchEvent) => {
    if (!isDraggingRef.current) return;

    const canvas = canvasRef.current;
    const data = canvasDataRef.current;
    if (!canvas || !data) return;

    const touch = e.touches[0];
    if (!touch) return;

    e.preventDefault();

    const { x, y } = canvasToLogical(touch.clientX, touch.clientY, canvas);
    data.myPosition.x = x;
    data.myPosition.y = y;
    throttledSendRef.current(x, y);
  }, [canvasDataRef]);

  const handleTouchEnd = useCallback((e: TouchEvent) => {
    if (!isDraggingRef.current) return;

    isDraggingRef.current = false;

    const canvas = canvasRef.current;
    const data = canvasDataRef.current;
    if (!canvas || !data) return;

    const touch = e.changedTouches[0];
    if (!touch) return;

    const { x, y } = canvasToLogical(touch.clientX, touch.clientY, canvas);
    data.myPosition.x = x;
    data.myPosition.y = y;
    sendLocationUpdate(x, y);
  }, [canvasDataRef, sendLocationUpdate]);

  useEffect(() => {
    animFrameRef.current = requestAnimationFrame(render);

    return () => {
      cancelAnimationFrame(animFrameRef.current);
    };
  }, [render]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    canvas.addEventListener('mousedown', handleMouseDown);
    window.addEventListener('mousemove', handleMouseMove);
    window.addEventListener('mouseup', handleMouseUp);
    canvas.addEventListener('touchstart', handleTouchStart, { passive: false });
    canvas.addEventListener('touchmove', handleTouchMove, { passive: false });
    canvas.addEventListener('touchend', handleTouchEnd);

    return () => {
      canvas.removeEventListener('mousedown', handleMouseDown);
      window.removeEventListener('mousemove', handleMouseMove);
      window.removeEventListener('mouseup', handleMouseUp);
      canvas.removeEventListener('touchstart', handleTouchStart);
      canvas.removeEventListener('touchmove', handleTouchMove);
      canvas.removeEventListener('touchend', handleTouchEnd);
    };
  }, [handleMouseDown, handleMouseMove, handleMouseUp, handleTouchStart, handleTouchMove, handleTouchEnd]);

  return (
    <canvas
      ref={canvasRef}
      style={{ display: 'block', cursor: 'default' }}
    />
  );
}
