package org.zhongwenjie.netty.http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.stream.ChunkedWriteHandler;

public class HttpServer {

    public void server(final int port) throws InterruptedException {
        EventLoopGroup bossGroup=new NioEventLoopGroup();//线程一 //这个是用于ServerSocketChannel的event
        EventLoopGroup workerGroup=new NioEventLoopGroup();//线程二//这个是用于处理accept到的channel

        try {
            //引导服务器
            ServerBootstrap bootstrap=new ServerBootstrap();
            bootstrap.group(bossGroup,workerGroup)
                    .channel(NioServerSocketChannel.class)//指定NIO传输channel
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel socketChannel) throws Exception {
                            socketChannel.pipeline().addLast(new HttpServerCodec())//netty Http 编解码器
                                    .addLast(new HttpObjectAggregator(512*1024))//聚合 Http 消息
                                    .addLast(new ChunkedWriteHandler())//将数据从文件系统复制到用户内存中
                                    .addLast(new HttpServerHandler());//自定义处理器
                        }
                    });
            ChannelFuture future=bootstrap.bind("localhost",port).sync();//异步的绑定服务器//调用sync()方法阻塞等待直到绑定完成
            future.channel().closeFuture().sync();//获取Channel的CloseFuture//阻塞当前线程直到绑定完成
        }finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }


    public static void main(String[] args) throws InterruptedException {
        new HttpServer().server(8888);//启动服务 //http://localhost:8888
    }


}


































