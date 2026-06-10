package sketch.gate;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

public class App {
    public static void main(String[] args) {
        System.out.println("[INFO] Sketch-Gate System - Launching Netty Network Engine...");

        // 1. 환경설정 및 핵심 방어 엔진 초기화
        ConfigManager config = ConfigManager.getInstance();
        int port = config.getInt("server.port", 8080);

        TwinSketchManager sketchManager = new TwinSketchManager();
        FilterService filterService = new FilterService(sketchManager);

        // 2. Netty의 멀티스레드 이벤트 루프 그룹 생성 (Boss: 연결 수락, Worker: 패킷 처리)
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup(); // 대량 트래픽용 기본 CPU 코어 수 기반 스레드 풀

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();

                            // [1] HTTP 프로토콜 디코딩/인코딩 핸들러
                            p.addLast(new HttpServerCodec());
                            // [2] HTTP 조각 메시지를 하나의 완전한 객체로 묶어주는 애그리게이터 (최대 64KB)
                            p.addLast(new HttpObjectAggregator(65536));

                            // [3] 최전방 DDoS 방어 게이트웨이 핸들러 (우리가 만든 심장부)
                            p.addLast(new ThrottlingHandler(filterService));

                            // [4] 방어벽을 무사히 통과한 패킷이 도달할 최종 백엔드 비즈니스 핸들러
                            p.addLast(new GateServerHandler());
                        }
                    })
                    // 고성능 서버를 위한 TCP 옵션 커스텀 설정
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            // 3. 설정된 포트로 서버 바인딩 및 구동 시작
            ChannelFuture f = b.bind(port).sync();
            System.out.println("--------------------------------------------------");
            System.out.println("[SUCCESS] Sketch-Gate Firewall Server is running on port: " + port);
            System.out.println("[INFO] Monitoring and protecting in real-time...");
            System.out.println("--------------------------------------------------");

            // 서버 소켓이 닫힐 때까지 메인 스레드 대기 (서버 유지)
            f.channel().closeFuture().sync();

        } catch (InterruptedException e) {
            System.err.println("[ERROR] Server interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            // 서버 종료 시 자원 회수
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
            System.out.println("[INFO] Sketch-Gate Server shutdown gracefully.");
        }
    }
}