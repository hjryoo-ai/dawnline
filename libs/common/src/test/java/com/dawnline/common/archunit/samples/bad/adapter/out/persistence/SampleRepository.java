package com.dawnline.common.archunit.samples.bad.adapter.out.persistence;

/** 규칙 검증용 표본: application 이 참조하면 안 되는 아웃바운드 어댑터. */
public final class SampleRepository {

    public String load(String id) {
        return id;
    }
}
