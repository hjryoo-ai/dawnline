package com.dawnline.common.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 서버의 응답을 원하는 만큼 늦추는 TCP 프록시 — 「연결 수립이 느리다」와 「명령이 느리다」를 테스트가 켜고 끈다
 * ([ADR-069](docs/adr/ADR-069-redis-connect-budget-is-not-the-command-budget.md)).
 *
 * <p>늦추는 것은 <strong>서버 → 클라이언트</strong> 방향뿐이다. 핸드셰이크(Redis 의 {@code HELLO})도 명령도 서버의 응답을 기다리므로,
 * 한 방향만 늦춰도 둘 다 늦어진다. 둘을 가르는 손잡이가 둘이다 — {@link #delayFirstReply} 는 <em>새 연결의 첫 응답</em>(핸드셰이크)만,
 * {@link #delayReplies} 는 모든 응답(명령)을 늦춘다. 앞의 것이 「콜드 경로의 느린 연결 수립, 그 뒤의 명령은 정상」을 흉내 낸다. 새 라이브러리(Toxiproxy)를 들이지 않는 이유는 필요한 것이 이 한 가지뿐이기 때문이다(CLAUDE.md 「새 라이브러리 최소화」).
 *
 * <p>받은 연결 수를 센다 — 「선연결한 연결을 명령이 그대로 쓴다」를 보는 자리다.
 *
 * <p>「서버가 없다」는 {@link #refuseConnections} 로 만든다 — 프록시를 닫지 않는다. 닫으면 포트가 풀리고, 병렬로 도는 다른 테스트
 * JVM 이 같은 루프백 포트를 다시 잡을 수 있다 — 그러면 「없는 서버」에 무언가가 대답한다.
 */
public final class DelayingTcpProxy implements AutoCloseable {

    private final ServerSocket server;
    private final String upstreamHost;
    private final int upstreamPort;
    private final AtomicInteger accepted = new AtomicInteger();
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private volatile Duration replyDelay = Duration.ZERO;
    private volatile Duration firstReplyDelay = Duration.ZERO;
    private volatile boolean refusing;

    private DelayingTcpProxy(String upstreamHost, int upstreamPort) throws IOException {
        this.upstreamHost = Objects.requireNonNull(upstreamHost, "upstreamHost");
        this.upstreamPort = upstreamPort;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().name("delaying-proxy-accept").start(this::acceptLoop);
    }

    /**
     * 프록시를 연다. 지연은 0 으로 시작한다.
     *
     * @param upstreamHost 실제 서버 호스트
     * @param upstreamPort 실제 서버 포트
     * @return 열린 프록시
     */
    public static DelayingTcpProxy to(String upstreamHost, int upstreamPort) {
        try {
            return new DelayingTcpProxy(upstreamHost, upstreamPort);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /** @return 클라이언트가 붙을 포트 (루프백) */
    public int port() {
        return server.getLocalPort();
    }

    /** @return 루프백 주소 문자열 */
    public String host() {
        return server.getInetAddress().getHostAddress();
    }

    /**
     * 서버 응답의 각 조각을 이만큼 늦춘다. 이미 열린 연결에도 곧바로 적용된다.
     *
     * @param delay 늦출 시간. {@link Duration#ZERO} 면 늦추지 않는다
     */
    public void delayReplies(Duration delay) {
        this.replyDelay = Objects.requireNonNull(delay, "delay");
    }

    /**
     * <em>이후에 열리는</em> 연결마다 서버의 첫 응답 조각만 이만큼 늦춘다 — 핸드셰이크가 느린 연결 수립이다.
     *
     * @param delay 늦출 시간. {@link Duration#ZERO} 면 늦추지 않는다
     */
    public void delayFirstReply(Duration delay) {
        this.firstReplyDelay = Objects.requireNonNull(delay, "delay");
    }

    /**
     * 포트는 쥔 채 서버를 없앤다 — 열린 연결을 끊고, 이후의 연결은 받자마자 끊는다.
     */
    public void refuseConnections() {
        this.refusing = true;
        closeSockets();
    }

    /** @return 지금까지 받은 클라이언트 연결 수 */
    public int acceptedConnections() {
        return accepted.get();
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // 닫는 중이다.
        }
        closeSockets();
    }

    private void closeSockets() {
        for (Socket socket : sockets) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 닫는 중이다.
            }
        }
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                accepted.incrementAndGet();
                if (refusing) {
                    client.close();
                    continue;
                }
                Socket upstream = new Socket();
                upstream.connect(new InetSocketAddress(upstreamHost, upstreamPort), 5_000);
                client.setTcpNoDelay(true);
                upstream.setTcpNoDelay(true);
                sockets.add(client);
                sockets.add(upstream);
                Duration handshake = firstReplyDelay;
                Thread.ofVirtual().start(() -> pump(client, upstream, false, Duration.ZERO));
                Thread.ofVirtual().start(() -> pump(upstream, client, true, handshake));
            } catch (IOException closedOrFailed) {
                if (server.isClosed()) {
                    return;
                }
            }
        }
    }

    private void pump(Socket from, Socket to, boolean reply, Duration firstDelay) {
        byte[] buffer = new byte[8192];
        boolean first = true;
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                Duration delay = first && !firstDelay.isZero() ? firstDelay : replyDelay;
                first = false;
                if (reply && !delay.isZero()) {
                    Thread.sleep(delay);
                }
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException | InterruptedException closed) {
            // 한쪽이 닫혔다 — 다른 쪽도 닫는다.
        } finally {
            try {
                from.close();
                to.close();
            } catch (IOException ignored) {
                // 닫는 중이다.
            }
        }
    }
}
