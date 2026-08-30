import type { AxiosResponse } from 'axios';
import api from './client';
import type {
  ApiResponse,
  AgentChatResponse,
  AgentSearchResponse,
} from '../types';

/**
 * Recommendation-agent client.
 *
 * Both endpoints are public. The funnel exists to collect a signal from someone
 * who has not committed to anything yet, so a login wall on the first message
 * would lose exactly the people it serves; identity is only needed later, at
 * checkout.
 *
 * Turns are slow by ordinary web standards — every one costs an LLM call plus
 * embeddings on a locally-hosted model, typically 3-5 seconds. Callers should
 * show progress rather than a spinner that looks stuck, and must not fire on
 * keystroke.
 */
export const agentApi = {
  /** One stateless turn. Useful for a single "surprise me" box. */
  search: (message: string, city?: string) =>
    api
      .post<ApiResponse<AgentSearchResponse>>('/api/agent/search', { message, city })
      .then((r: AxiosResponse<ApiResponse<AgentSearchResponse>>) => r.data.data),

  /**
   * One conversational turn.
   *
   * `sessionId` is supplied by the caller and is not tied to a login — memory
   * has to work before someone signs in. Server-side state lives in Redis with
   * a 45-minute TTL; losing it costs a re-typed sentence and nothing else.
   */
  chat: (sessionId: string, message: string, city?: string) =>
    api
      .post<ApiResponse<AgentChatResponse>>('/api/agent/chat', { sessionId, message, city })
      .then((r: AxiosResponse<ApiResponse<AgentChatResponse>>) => r.data.data),
};
