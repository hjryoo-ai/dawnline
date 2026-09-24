import { useId, useState, type ReactNode } from 'react';

export interface ReasonField {
  label: string;
  /** 계약의 상한 — 조기 마감의 `reason` 은 200자다. */
  maxLength: number;
}

/**
 * 커맨드 확인 창. 이유 칸은 **계약에 있을 때만** 그린다(ADR-056 결정 2) — 계약에 없는 칸은 감사 행에도 코어에도
 * 가지 않는 버려지는 입력이다. 이유가 있으면 공백만으로는 보낼 수 없다(코어가 400 으로 돌려보내기 전에).
 */
export function ConfirmDialog(props: {
  title: string;
  children: ReactNode;
  confirmLabel: string;
  reason?: ReasonField;
  pending: boolean;
  onConfirm: (reason: string | null) => void;
  onCancel: () => void;
}) {
  const [reason, setReason] = useState('');
  const reasonId = useId();
  const reasonMissing = props.reason !== undefined && reason.trim() === '';
  return (
    <div role="dialog" aria-modal="true" aria-label={props.title} className="dialog">
      <h3>{props.title}</h3>
      <div>{props.children}</div>
      {props.reason && (
        <label htmlFor={reasonId} className="field">
          {props.reason.label} (필수)
          <textarea
            id={reasonId}
            value={reason}
            maxLength={props.reason.maxLength}
            onChange={(event) => setReason(event.target.value)}
          />
        </label>
      )}
      <div className="actions">
        <button type="button" onClick={props.onCancel} disabled={props.pending}>
          취소
        </button>
        <button
          type="button"
          className="danger"
          disabled={props.pending || reasonMissing}
          onClick={() => props.onConfirm(props.reason ? reason.trim() : null)}
        >
          {props.pending ? '보내는 중…' : props.confirmLabel}
        </button>
      </div>
    </div>
  );
}
