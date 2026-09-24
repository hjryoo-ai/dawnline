package com.dawnline.ops.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * DLQ 재처리의 시간 상한 (DESIGN.md §4.6 「DLQ 재처리」, ADR-053).
 *
 * @param readTimeout DLQ 한 번 읽기(메타데이터·오프셋·레코드)의 상한. 넘으면 그 레코드는 {@code FAILED} — 읽지 못했으니
 *                    보내지도 않았다
 * @param sendTimeout ack 를 기다리는 상한. 넘으면 {@code UNKNOWN} — 다시 누르면 된다. 프로듀서의
 *                    {@code delivery.timeout.ms}·{@code max.block.ms} 도 이 값이라, 이 시간이 지나 프로듀서가 뒤늦게
 *                    보내는 일이 없다
 */
@ConfigurationProperties("dawnline.ops.dlq")
public record OpsDlqProperties(@DefaultValue("10s") Duration readTimeout, @DefaultValue("10s") Duration sendTimeout) {
}
