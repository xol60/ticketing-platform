import { useEffect, useRef, useState } from 'react';
import type { Order } from '../types';

type StreamEvent = { type: string; data: Partial<Order> };

export function useOrderStream(orderId: string | null, onUpdate: (event: StreamEvent) => void) {
  const esRef = useRef<EventSource | null>(null);
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    if (!orderId) return;

    const token = localStorage.getItem('accessToken');
    const url = `/api/orders/${orderId}/stream${token ? `?token=${token}` : ''}`;
    const es = new EventSource(url);
    esRef.current = es;

    es.onopen = () => setConnected(true);
    es.onmessage = (e) => {
      try { onUpdate(JSON.parse(e.data)); } catch { /* ignore */ }
    };
    es.onerror = () => { setConnected(false); es.close(); };

    return () => { es.close(); setConnected(false); };
  }, [orderId]); // eslint-disable-line react-hooks/exhaustive-deps

  return { connected };
}
