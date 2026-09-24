package com.dawnline.ops.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * outbox 경로의 {@code {service}} 칸 — 어느 코어의 격리 행인가 (DESIGN.md §5.5 「커맨드 위임」).
 *
 * <p>같은 공유 코드가 코어 넷에 같은 경로를 만들었으므로(§4.6) ops-api 에서는 경로가 코어를 말해야 한다. 값은
 * 이 넷이고, 그 밖의 값은 없는 경로다 — 404 이며 감사 행을 남기지 않는다. ops-api 는 넷에 들지 않는다: 그 outbox
 * 관리 경로는 꺼져 있다(ADR-015 후속 정정 결정 4).
 */
public enum CoreService {

    ORDER("order"),
    FULFILLMENT("fulfillment"),
    DISPATCH("dispatch"),
    TRACKING("tracking");

    private final String path;

    CoreService(String path) {
        this.path = path;
    }

    /** @return 경로와 감사 행의 {@code request.service} 에 쓰는 값 */
    public String path() {
        return path;
    }

    /**
     * @param path 경로의 {@code {service}}
     * @return 그 코어. 넷에 없으면 비어 있다 — 대소문자를 고쳐 주지 않는다(경로는 한 가지 모양만 있다)
     */
    public static Optional<CoreService> fromPath(String path) {
        return Arrays.stream(values()).filter(service -> service.path.equals(path)).findFirst();
    }
}
