package com.dawnline.ops.adapter.in.messaging;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * {@link ProjectionListener} 가 실제로 구독하는 토픽 — 어노테이션에서 읽는다.
 *
 * <p>검사들이 토픽을 열거하지 않고 여기서 시작한다(§13 규칙 2). 그리고 순서 검사가 레코드를
 * 리스너에 넣는 길도 여기다 — Kafka 가 고르는 메서드와 같은 메서드를 토픽으로 찾아 부른다.
 */
public final class ListenerTopics {

    private ListenerTopics() {
    }

    /** 토픽 → 그 토픽을 받는 메서드. */
    public static Map<String, Method> of() {
        Map<String, Method> topics = new TreeMap<>();
        for (Method method : ProjectionListener.class.getDeclaredMethods()) {
            KafkaListener listener = method.getAnnotation(KafkaListener.class);
            if (listener == null) {
                continue;
            }
            for (String topic : listener.topics()) {
                Method previous = topics.put(topic, method);
                if (previous != null) {
                    throw new IllegalStateException("토픽 하나를 두 메서드가 받는다: " + topic);
                }
            }
        }
        return topics;
    }

    /**
     * 레코드를 그 토픽의 리스너 메서드에 넣는다.
     *
     * @param listener 리스너
     * @param record   레코드
     */
    public static void deliver(ProjectionListener listener, ConsumerRecord<String, String> record) {
        Method method = Objects.requireNonNull(of().get(record.topic()), () -> "받는 메서드가 없다: " + record.topic());
        try {
            method.invoke(listener, record);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        }
    }
}
