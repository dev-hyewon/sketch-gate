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

            if (!filterService.isAllowed(clientIp)) {
                String userAgent = request.headers().get("User-Agent", "").toLowerCase();
                String accept = request.headers().get("Accept", "").toLowerCase();
                boolean isTerminal = userAgent.contains("curl")
                        || userAgent.contains("wget")
                        || accept.contains("text/plain");

                FullHttpResponse response;

                if (isTerminal) {
                    // 터미널 접근용: 깔끔한 한 줄 텍스트 응답 (줄바꿈 \n 포함)
                    String textResponse = "403 Forbidden: DDoS Attack Detected.\n";
                    response = new DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1,
                            HttpResponseStatus.FORBIDDEN,
                            Unpooled.copiedBuffer(textResponse, CharsetUtil.UTF_8));
                    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
                } else {
                    // 일반 브라우저 접근용: 기존대로 HTML 템플릿 응답
                    String forbiddenHtml = ResponseTemplateLoader.loadHtml("templates/forbidden.html");
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