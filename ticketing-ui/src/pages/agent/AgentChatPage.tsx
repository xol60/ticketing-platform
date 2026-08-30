import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { agentApi } from '../../api/agent';
import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { Spinner } from '../../components/ui/Spinner';
import type { AgentChatResponse, AgentHit } from '../../types';

/**
 * Conversational event discovery.
 *
 * A funnel, not a chatbot: every turn ends with events on screen. There is no
 * branch that replies with a question and nothing else — asking "what kind of
 * thing are you after?" before showing anything is a form wearing a chat
 * interface, and avoiding forms is the reason to come here.
 */

/**
 * Session id, generated once and kept for the tab.
 *
 * Deliberately not tied to a login. Memory has to work before someone signs in,
 * or the first message either forces a login or is forgotten. `sessionStorage`
 * rather than `localStorage` so a new tab starts a fresh conversation, which is
 * what someone opening one usually wants.
 */
function useSessionId(): string {
  const [id] = useState(() => {
    const existing = sessionStorage.getItem('agentSessionId');
    if (existing) return existing;
    const fresh = crypto.randomUUID();
    sessionStorage.setItem('agentSessionId', fresh);
    return fresh;
  });
  return id;
}

interface Turn {
  role: 'user' | 'agent';
  text?: string;
  response?: AgentChatResponse;
}

const EXAMPLES = [
  'a musical in london',
  'something calm, nothing too crowded',
  'taylor swift',
  'tech conference in singapore under $200',
];

export function AgentChatPage() {
  const sessionId = useSessionId();
  const navigate = useNavigate();
  const [turns, setTurns] = useState<Turn[]>([]);
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [turns, busy]);

  async function send(message: string) {
    const text = message.trim();
    if (!text || busy) return;

    setTurns((t) => [...t, { role: 'user', text }]);
    setDraft('');
    setBusy(true);
    setError(null);

    try {
      const response = await agentApi.chat(sessionId, text);
      setTurns((t) => [...t, { role: 'agent', response }]);
    } catch {
      // The turn is lost, the conversation is not — server-side state only
      // advances on success, so retyping picks up exactly where this left off.
      setError('Không lấy được gợi ý. Thử lại nhé.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="max-w-3xl mx-auto px-4 py-6">
      <header className="mb-6">
        <h1 className="text-2xl font-semibold">Gợi ý sự kiện</h1>
        <p className="text-sm text-gray-500 mt-1">
          Mô tả thứ bạn muốn xem. Trả lời được nhiều lượt — có thể nói
          &ldquo;cái thứ 2&rdquo;, &ldquo;rẻ hơn&rdquo;, hay &ldquo;bỏ thành phố đi&rdquo;.
        </p>
      </header>

      {turns.length === 0 && (
        <div className="mb-6 flex flex-wrap gap-2">
          {EXAMPLES.map((e) => (
            <button
              key={e}
              onClick={() => send(e)}
              className="text-sm px-3 py-1.5 rounded-full border border-gray-300 text-gray-700 hover:bg-gray-50"
            >
              {e}
            </button>
          ))}
        </div>
      )}

      <div className="space-y-4">
        {turns.map((turn, i) =>
          turn.role === 'user' ? (
            <div key={i} className="flex justify-end">
              <p className="bg-indigo-600 text-white rounded-2xl rounded-br-sm px-4 py-2 text-sm max-w-[80%]">
                {turn.text}
              </p>
            </div>
          ) : (
            <AgentTurn key={i} response={turn.response!} onSelect={(h) => navigate(`/events/${h.eventId}`)} />
          )
        )}

        {busy && (
          <div className="flex items-center gap-2 text-sm text-gray-500">
            <Spinner />
            {/* Turns take seconds on a local model. Saying so beats a spinner
                that looks stuck. */}
            <span>Đang tìm… (chạy model cục bộ, mất vài giây)</span>
          </div>
        )}

        {error && <p className="text-sm text-red-600">{error}</p>}
        <div ref={endRef} />
      </div>

      <form
        className="mt-6 flex gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          send(draft);
        }}
      >
        <Input
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          placeholder="Bạn muốn xem gì?"
          disabled={busy}
          className="flex-1"
        />
        <Button type="submit" disabled={busy || !draft.trim()}>
          Gửi
        </Button>
      </form>
    </div>
  );
}

/** One agent reply: relaxations, results or the chosen event, then filters. */
function AgentTurn({
  response,
  onSelect,
}: {
  response: AgentChatResponse;
  onSelect: (hit: AgentHit) => void;
}) {
  const { hits, focused, handoff, relaxations, totalMatched, offerNarrowing } = response;

  return (
    <div className="space-y-3">
      {/* Shown before the results, never instead of them. A result set that
          silently ignored a stated budget is worse than an empty one. */}
      {relaxations?.length > 0 && (
        <p className="text-sm text-amber-700 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">
          Không có kết quả đúng yêu cầu, đã nới: {relaxations.join('; ')}.
        </p>
      )}

      {handoff && (
        <div className="rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3">
          {handoff.available ? (
            <>
              <p className="text-sm text-emerald-800 mb-2">Sẵn sàng đặt vé.</p>
              <Link
                to={`/events/${handoff.eventId}`}
                className="inline-block text-sm font-medium text-white bg-emerald-600 hover:bg-emerald-700 rounded-md px-3 py-1.5"
              >
                Chọn chỗ và đặt vé
              </Link>
            </>
          ) : (
            <p className="text-sm text-emerald-900">Không đặt được: {handoff.reason}</p>
          )}
        </div>
      )}

      {focused && <HitCard hit={focused} onSelect={onSelect} focused />}

      {hits?.map((hit, i) => (
        <HitCard key={hit.eventId + i} hit={hit} index={i + 1} onSelect={onSelect} />
      ))}

      {/* The question comes after the results, with a real number, and can be
          ignored without losing what is already on screen. */}
      {offerNarrowing && (
        <p className="text-sm text-gray-500">
          Còn {totalMatched} sự kiện khớp. Thu hẹp theo thành phố, thời gian hay giá?
        </p>
      )}

      {hits?.length === 0 && !focused && !handoff && (
        <p className="text-sm text-gray-500">Không tìm thấy gì phù hợp.</p>
      )}
    </div>
  );
}

function HitCard({
  hit,
  index,
  onSelect,
  focused,
}: {
  hit: AgentHit;
  index?: number;
  onSelect: (hit: AgentHit) => void;
  focused?: boolean;
}) {
  const meta = [hit.primaryArtist, hit.venueName, hit.venueCity].filter(Boolean).join(' · ');

  return (
    <button
      onClick={() => onSelect(hit)}
      className={`w-full text-left rounded-lg border px-4 py-3 hover:bg-gray-50 ${
        focused ? 'border-indigo-300 bg-indigo-50/40' : 'border-gray-200'
      }`}
    >
      <div className="flex items-start gap-3">
        {/* The number is what "the second one" refers to, so it has to be on
            screen — otherwise the person is counting rows in their head. */}
        {index && <span className="text-xs text-gray-400 mt-0.5 tabular-nums">{index}</span>}
        <div className="min-w-0 flex-1">
          <p className="font-medium text-sm truncate">{hit.name}</p>
          {meta && <p className="text-xs text-gray-500 mt-0.5 truncate">{meta}</p>}
          <p className="text-xs text-gray-500 mt-1">
            {hit.startAt && new Date(hit.startAt).toLocaleDateString('vi-VN')}
            {hit.priceMin != null && ` · từ ${hit.priceMin.toLocaleString('vi-VN')}₫`}
          </p>
          {/* Only the facets that differ across this result set. A dim every
              row agrees on distinguishes nothing. */}
          {hit.differentiators && hit.differentiators.length > 0 && (
            <p className="text-xs text-gray-600 mt-1.5 line-clamp-2">
              {hit.differentiators.join(' · ')}
            </p>
          )}
        </div>
      </div>
    </button>
  );
}
