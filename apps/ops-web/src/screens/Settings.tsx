import { useState } from 'react';
import { describeToken, session, type Tiles } from '../session';
import { formatTime } from '../format';

/**
 * 설정은 토큰 붙여 넣기 하나다 — 「이 화면에는 인증이 있지만 사용자 관리는 범위 밖」의 UI 판(ADR-052 결정 2).
 * 토큰은 `make token ROLE=OPS_OPERATOR` 가 찍는다. 지도 타일 선택도 여기 있다(ADR-057 결정 1).
 */
export function Settings() {
  const [draft, setDraft] = useState(session.token() ?? '');
  const [saved, setSaved] = useState(session.token());
  const [tiles, setTiles] = useState<Tiles>(session.tiles());
  const described = saved ? describeToken(saved) : null;

  return (
    <section>
      <h2>설정</h2>
      <label className="field">
        운영자 토큰 (<code>make token ROLE=OPS_OPERATOR</code>)
        <textarea value={draft} onChange={(event) => setDraft(event.target.value)} rows={4} spellCheck={false} />
      </label>
      <div className="actions">
        <button
          type="button"
          onClick={() => {
            session.setToken(draft);
            setSaved(session.token());
          }}
        >
          저장
        </button>
        <button
          type="button"
          onClick={() => {
            session.setToken(null);
            setDraft('');
            setSaved(null);
          }}
        >
          지우기
        </button>
      </div>
      <p>
        {described
          ? `역할 ${described.role ?? '?'} · 사용자 ${described.actor ?? '?'} · 만료 ${formatTime(described.expiresAt?.toISOString())}`
          : '토큰이 없습니다 — 조회도 401 입니다.'}
      </p>
      <fieldset>
        <legend>지도 바탕</legend>
        <label>
          <input
            type="radio"
            name="tiles"
            checked={tiles === 'none'}
            onChange={() => {
              session.setTiles('none');
              setTiles('none');
            }}
          />
          타일 없음 (기본 — 인터넷 없이 선다)
        </label>
        <label>
          <input
            type="radio"
            name="tiles"
            checked={tiles === 'osm'}
            onChange={() => {
              session.setTiles('osm');
              setTiles('osm');
            }}
          />
          OpenStreetMap 타일 (데모용 — 인터넷이 필요하고, 공개 서버의 이용 정책을 따른다)
        </label>
      </fieldset>
    </section>
  );
}
