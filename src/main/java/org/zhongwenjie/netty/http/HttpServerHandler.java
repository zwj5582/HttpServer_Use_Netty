package org.zhongwenjie.netty.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.stream.ChunkedFile;
import io.netty.util.CharsetUtil;

import javax.activation.MimetypesFileTypeMap;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

public class HttpServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Pattern ALLOWED_FILE_NAME = Pattern.compile("[A-Za-z0-9][ -_A-Za-z0-9\\.]*");

    private static final String WEB_ROOT=System.getProperty("user.dir")+File.separator+"ROOT";//定义web根目录

    private FullHttpRequest request;

    /**
     * 当服务器接收到消息时，会自动触发 channelRead0方法
     * @param ctx
     * @param msg
     * @throws Exception
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
        messageReceived(ctx,msg);
    }

    /**
     * 实际处理http请求
     * @param ctx
     * @param msg
     */
    private void messageReceived(ChannelHandlerContext ctx,FullHttpRequest msg) throws URISyntaxException, IOException {

        this.request=msg;

        if (!request.decoderResult().isSuccess()){
            //解码未成功
            error(ctx,HttpResponseStatus.BAD_REQUEST);
            return;
        }

        if (request.method()!=HttpMethod.GET){
            //现在只接收GET请求
            error(ctx,HttpResponseStatus.METHOD_NOT_ALLOWED);
            return;
        }

        URI uri=new URI(msg.getUri());
        String path = WEB_ROOT + uri.getPath();
        File file=new File(path);

        //判断资源是否存在，否则响应返回404
        if (file.isHidden()||!file.exists()){
            error(ctx,HttpResponseStatus.NOT_FOUND);
            return;
        }
        if (file.isDirectory()){
            if (uri.getPath().endsWith("/")) {
                //返回当前目录结构
                index(ctx,file);
            }
            else
                //这里重定向
                sendRedirect(ctx,uri.getPath()+"/");
            return;
        }

        //判断资源是个文件，否则响应返回403
        if(!file.isFile()){
            error(ctx,HttpResponseStatus.FORBIDDEN);
            return;
        }

        //响应请求的资源
        handlerHttp(ctx,file);
    }

    /**
     *显示当前目录结构
     * @param ctx
     * @param dir
     * @throws IOException
     */
    private void index(ChannelHandlerContext ctx,File dir) {
        FullHttpResponse response=new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE,"text/html;charset=UTF-8");
        String dirName=dir.getName();
        StringBuilder sb=new StringBuilder();
        sb.append("<!DOCTYPE html>\r\n")
                .append("<html><head><title>")
                .append(dirName)
                .append("目录:")
                .append("</title></head><body>\r\n")
                .append("<h3>")
                .append(dirName)
                .append(" 目录：")
                .append("</h3>\r\n")
                .append("<ul>");
        if (!dir.equals(new File(WEB_ROOT)))
                sb.append("<li style=\"list-style: none;margin:10px;\">" +
                        "<img src=\"/_sys/images/folder_icon.png\" width=\"20\" height=\"20\" />" +
                        "&nbsp;&nbsp;<a href=\"../\" style=\"text-decoration:none;\" >..</a></li>\r\n");
        for (File f : dir.listFiles()){
            if (f.isHidden()||!f.canRead()||!ALLOWED_FILE_NAME.matcher(f.getName()).matches())
                continue;
            if (f.isDirectory())
                sb.append("<li style=\"list-style: none;margin:10px;\">" +
                        "<img src=\"/_sys/images/folder_icon.png\" width=\"20\" height=\"20\" />" +
                        "&nbsp;&nbsp;<a href=\"");
            else
                sb.append("<li style=\"list-style: none;margin:10px;\">" +
                        "<img src=\"/_sys/images/gdcsi.png\" width=\"20\" height=\"20\" />" +
                        "&nbsp;&nbsp;<a href=\"");
            sb.append(f.getPath().replace(WEB_ROOT,""))
                    .append("\" style=\"text-decoration:none;\">")
                    .append(f.getName())
                    .append("</a></li>\r\n");
        }
        sb.append("</ul></body></html>\r\n");
        ByteBuf buffer=Unpooled.copiedBuffer(sb,CharsetUtil.UTF_8);
        response.content().writeBytes(buffer);
        buffer.release();
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * 重定向
     * @param ctx
     * @param path
     */
    private void sendRedirect(ChannelHandlerContext ctx,String path){
        FullHttpResponse response=new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,HttpResponseStatus.FOUND);
        response.headers().set(HttpHeaderNames.LOCATION,path);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * 响应请求的错误
     * @param ctx
     * @param status
     */
    private void error(ChannelHandlerContext ctx,HttpResponseStatus status){
        FullHttpResponse response=new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,status,
                Unpooled.copiedBuffer("Failure : "+ status.toString()+"\r\n", CharsetUtil.UTF_8));
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }


    /**
     * 响应请求的资源
     * @param ctx
     * @param file
     * @throws IOException
     */
    private void handlerHttp(ChannelHandlerContext ctx,File file) throws IOException {
        RandomAccessFile f=null;
        try {
            f=new RandomAccessFile(file,"r");
        } catch (FileNotFoundException e) {
            error(ctx,HttpResponseStatus.NOT_FOUND);
            return;
        }
        HttpResponse response=new DefaultHttpResponse(HttpVersion.HTTP_1_1,HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH,f.length());
        setContentTypeHeader(response,file);
        if (request.headers().get(HttpHeaderNames.CONNECTION).equals(HttpHeaderValues.KEEP_ALIVE))
            response.headers().set(HttpHeaderNames.CONNECTION,HttpHeaderValues.KEEP_ALIVE);
        ctx.write(response);
        ctx.write(new ChunkedFile(f,0,f.length(),8192),ctx.newProgressivePromise());
        ChannelFuture lastContentFuture=ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        if (request.headers().get(HttpHeaderNames.CONNECTION).equals(HttpHeaderValues.KEEP_ALIVE))
            lastContentFuture.addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * 查找 MIME 类型文件条目,并设置 response MIME 类型
     * @param response
     * @param file
     */
    private void setContentTypeHeader(HttpResponse response, File file) {
        MimetypesFileTypeMap mimetypesFileTypeMap=new MimetypesFileTypeMap();
        response.headers().set(HttpHeaderNames.CONTENT_TYPE,mimetypesFileTypeMap.getContentType(file.getPath()));
    }

    /**
     * netty处理异常
     * @param ctx
     * @param cause
     * @throws Exception
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        if (ctx.channel().isActive()){
            error(ctx,HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
