package sketch.gate;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

public class GateServerHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            HttpRequest request = (HttpRequest) msg;

            String userAgent = request.headers().get("User-Agent", "").toLowerCase();
            String accept = request.headers().get("Accept", "").toLowerCase();
            boolean isTerminal = userAgent.contains("curl")
                    || userAgent.contains("wget")
                    || accept.contains("text/plain");

            FullHttpResponse response;

            if (isTerminal) {
                // 터미널 전용: 깔끔한 한 줄 텍스트 응답 (\n 포함)
                String textResponse = "200 OK: Welcome to Sketch-Gate Secure Network!\n";
                response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.OK,
                        Unpooled.copiedBuffer(textResponse, CharsetUtil.UTF_8));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
            } else {
                // 웹 브라우저 전용: 기존 HTML 템플릿 외장화 응답
                String welcomeHtml = ResponseTemplateLoader.loadHtml("templates/welcome.html");
                response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.OK,
                        Unpooled.copiedBuffer(welcomeHtml, CharsetUtil.UTF_8));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
            }

            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        super.channelRead(ctx, msg);
    }
}