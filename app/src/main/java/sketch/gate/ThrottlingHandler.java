package sketch.gate;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

import java.net.InetSocketAddress;

public class ThrottlingHandler extends ChannelInboundHandlerAdapter {
    private final FilterService filterService;

    public ThrottlingHandler(FilterService filterService) {
        this.filterService = filterService;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            // 💡 향후 URI, User-Agent 기반 L7 필터링 확장 시 사용
            // HttpRequest request = (HttpRequest) msg;

            InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
            String clientIp = remoteAddress.getAddress().getHostAddress();

            // 실시간 차단 성공 시 콘솔 로그를 남기지 않고 즉시 403으로 응답만 출력됨
            if (!filterService.isAllowed(clientIp)) {
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.FORBIDDEN,
                        Unpooled.copiedBuffer("<html><body><h1>403 Forbidden: DDoS Attack Detected.</h1></body></html>",
                                CharsetUtil.UTF_8));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
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