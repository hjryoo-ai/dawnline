/** 표시 형식. 시각은 캠프가 있는 곳의 시각(KST)으로 — 테스트가 기계의 시간대에 기대지 않게 고정한다. */
const time = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
});

export const DASH = '—';

export function formatTime(iso: string | null | undefined): string {
  return iso ? time.format(new Date(iso)) : DASH;
}

/** 정시율. `null` 은 「창 안에 결과가 없다」이지 0% 가 아니다(§5.5). */
export function formatRatio(ratio: number | null | undefined): string {
  return ratio === null || ratio === undefined ? DASH : `${(ratio * 100).toFixed(1)}%`;
}

export function formatKrw(value: number | null | undefined): string {
  return value === null || value === undefined ? DASH : `${value.toLocaleString('ko-KR')}원`;
}

export function formatCount(value: number | null | undefined): string {
  return value === null || value === undefined ? DASH : value.toLocaleString('ko-KR');
}

/**
 * UUID 를 **뒤** 8자로. 캠프 코드가 없을 때(옛 이벤트)의 이름이기도 하다.
 *
 * 앞이 아니라 뒤인 이유: UUIDv7(불변규칙 10)의 앞 48비트는 밀리초 시각이다. 함께 만들어진 id —
 * 한 계획의 라우트, 시드의 캠프 — 는 앞 8자가 같고, 앞을 보이면 재배정 대상 목록의 두 라우트가 같은
 * 이름이 된다(2026-09-24 Phase 6 데모에서 관측: 두 라우트가 둘 다 `01a0d373`). 뒤는 난수 부분이다.
 */
export function shortId(id: string | null | undefined): string {
  return id ? `…${id.slice(-8)}` : DASH;
}
