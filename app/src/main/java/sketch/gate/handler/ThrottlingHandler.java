package sketch.gate.handler;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
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
                    htmlTemplatePath = "templates/quota_exceeded.html"; // ◀️ 1단계에서 신설할 파일명
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

                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                return;
            }
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("[ERROR] Exception in ThrottlingHandler: " + cause.getMessage());
        ctx.close();
    }
}