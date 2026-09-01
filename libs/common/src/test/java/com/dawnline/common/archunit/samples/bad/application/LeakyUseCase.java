package com.dawnline.common.archunit.samples.bad.application;

import com.dawnline.common.archunit.samples.bad.adapter.out.persistence.SampleRepository;

/**
 * 규칙 검증용 표본: <strong>일부러</strong> adapter 를 참조하는 유스케이스.
 *
 * <p>{@code APPLICATION_DOES_NOT_DEPEND_ON_ADAPTER} 규칙이 이것을 반드시 잡아내야 한다.
 * 실제 서비스 코드가 아니라 규칙의 음성 검증용 표본이다.
 */
public final class LeakyUseCase {

    private final SampleRepository repository = new SampleRepository();

    public String load(String id) {
        return repository.load(id);
    }
}
