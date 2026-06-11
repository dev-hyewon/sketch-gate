package sketch.gate.handler;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import sketch.gate.service.FilterService;
import sketch.gate.util.ResponseTemplateLoader;

import java.net.InetSocketAddress;

public class ThrottlingHandler extends ChannelInboundHandlerAdapter {
    private final FilterService filterService;

    public ThrottlingHandler(FilterService filterService) {
        this.filterService = filterService;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            HttpRequest request = (HttpRequest) msg;

            InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
            String clientIp = remoteAddress.getAddress().getHostAddress();

            FilterService.FilterResult result = filterService.checkResult(clientIp);

            // ALLOWED가 아니라면 차단 공정 시작 (RPM 또는 RPD 걸림)
            if (result != FilterService.FilterResult.ALLOWED) {

                String userAgent = request.headers().get("User-Agent", "").toLowerCase();
                String accept = request.headers().get("Accept", "").toLowerCase();
                boolean isTerminal = userAgent.contains("curl")
                        || userAgent.contains("wget")
                        || accept.contains("text/plain");

                FullHttpResponse response;
                String textResponse;
                String htmlTemplatePath;

                if (result == FilterService.FilterResult.DENIED_DAILY) {
                    // 일일 제한(RPD) 초과 케이스
                    textResponse = "403 Forbidden: Daily Quota Exceeded.\n";
                    htmlTemplatePath = "templates/quota_exceeded.html";
                } else {
                    // 분당 제한(RPM) 초과 케이스 (기존 유지)
                    textResponse = "403 Forbidden: DDoS Attack Detected.\n";
                    htmlTemplatePath = "templates/forbidden.html";
                }

                if (isTerminal) {
                    // 터미널 접근용
                    response = new DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1,
                            HttpResponseStatus.FORBIDDEN,
                            Unpooled.copiedBuffer(textResponse, CharsetUtil.UTF_8));
                    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
                } else {
                    // 일반 브라우저 접근용
                    String forbiddenHtml = ResponseTemplateLoader.loadHtml(htmlTemplatePath);
                    response = new DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1,
                            HttpResponseStatus.FORBIDDEN,
                            Unpooled.copiedBuffer(forbiddenHtml, CharsetUtil.UTF_8));
                    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
                }

                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());

                // 403 Forbidden 응답 전송 및 채널 닫기 기동
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);

                // 인입된 인바운드 HTTP 요청 객체(`msg`)의 참조 카운트를 감소시켜 메모리를 해제합니다.
                // 이 핸들러가 파이프라인의 종착점이 되어 직접 응답을 끝냈으므로, 더 이상 다음 핸들러로 넘기지 않고 여기서 완전히 소멸시킵니다.
                ReferenceCountUtil.release(msg);
                return;
            }
        }

        // 정상 통과(ALLOWED)일 때는 super.channelRead를 통해 파이프라인의 다음 핸들러(GateServerHandler)로
        // msg가 온전히 토스되므로 여기서 release를 하면 안 됩니다.
        super.channelRead(ctx, msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("[ERROR] Exception in ThrottlingHandler: " + cause.getMessage());
        ctx.close();
    }
}