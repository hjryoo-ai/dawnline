/**
 * outbox 의 운영 HTTP 표면 — 격리 조회·재큐 (DESIGN.md §4.6, ADR-015 후속 정정).
 *
 * <p>이 패키지는 서블릿 웹 앱에서만 쓰인다. {@code spring-webmvc} 는 이 모듈의 {@code compileOnly} 이고, 자동 설정이
 * 클래스 이름 조건으로 막으므로 웹이 없는 소비자는 이 패키지를 로드하지 않는다.
 */
package com.dawnline.messaging.web;
