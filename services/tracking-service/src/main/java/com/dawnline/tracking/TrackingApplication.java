package com.dawnline.tracking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * tracking-service 부트스트랩 (DESIGN.md §5.4).
 *
 * <p>기동·레디니스는 Actuator 기본 기능을 쓴다(DESIGN.md §8.6). 레디니스 그룹 구성과
 * 그레이스풀 셧다운 설정은 {@code src/main/resources/application.yml} 에 있다.
 */
@SpringBootApplication
public class TrackingApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrackingApplication.class, args);
    }
}
