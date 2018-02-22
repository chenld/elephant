package com.yanghui.elephant.remoting.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.extern.log4j.Log4j2;

import com.yanghui.elephant.common.utils.Pair;
import com.yanghui.elephant.remoting.RemotingServer;
import com.yanghui.elephant.remoting.RequestProcessor;
import com.yanghui.elephant.remoting.common.RemotingUtil;
import com.yanghui.elephant.remoting.procotol.RemotingCommand;
/**
 * 
 * @author --小灰灰--
 *
 */
@Log4j2
public class NettyRemotingServer extends NettyRemotingAbstract implements RemotingServer {

	private final ServerBootstrap serverBootstrap;
	private final EventLoopGroup eventLoopGroupSelector;
	private final EventLoopGroup eventLoopGroupBoss;
	private final NettyServerConfig nettyServerConfig;

	private DefaultEventExecutorGroup defaultEventExecutorGroup;
	
	private int port = 0;
	
	public NettyRemotingServer(final NettyServerConfig nettyServerConfig) {
		this.nettyServerConfig = nettyServerConfig;
		this.serverBootstrap = new ServerBootstrap();
		this.eventLoopGroupBoss = new NioEventLoopGroup(1, new ThreadFactory() {
			private AtomicInteger threadIndex = new AtomicInteger(0);
			@Override
			public Thread newThread(Runnable r) {
				return new Thread(r, String.format("NettyBoss_%d",this.threadIndex.incrementAndGet()));
			}
		});
		this.eventLoopGroupSelector = new NioEventLoopGroup(
		nettyServerConfig.getServerSelectorThreads(),
		new ThreadFactory() {
			private AtomicInteger threadIndex = new AtomicInteger(0);
			private int threadTotal = nettyServerConfig.getServerSelectorThreads();
			@Override
			public Thread newThread(Runnable r) {
				return new Thread(r, String.format("NettyServerEPOLLSelector_%d_%d", threadTotal,this.threadIndex.incrementAndGet()));
			}
		});
		
	}

	@Override
	public void start() {
		this.defaultEventExecutorGroup = new DefaultEventExecutorGroup(nettyServerConfig.getServerWorkerThreads(),new ThreadFactory() {
			private AtomicInteger threadIndex = new AtomicInteger(0);
			@Override
			public Thread newThread(Runnable r) {
				return new Thread(r, "NettyServerCodecThread_" + this.threadIndex.incrementAndGet());
			}
		});
		ServerBootstrap childHandler = this.serverBootstrap
				.group(this.eventLoopGroupBoss, this.eventLoopGroupSelector)
				.channel(NioServerSocketChannel.class)
				.option(ChannelOption.SO_BACKLOG, 1024)
				.option(ChannelOption.SO_REUSEADDR, true)
				.option(ChannelOption.SO_KEEPALIVE, false)
				.childOption(ChannelOption.TCP_NODELAY, true)
				.option(ChannelOption.SO_SNDBUF,nettyServerConfig.getServerSocketSndBufSize())
				.option(ChannelOption.SO_RCVBUF,nettyServerConfig.getServerSocketRcvBufSize())
				.localAddress(new InetSocketAddress(this.nettyServerConfig.getListenPort()))
				.childHandler(new ChannelInitializer<SocketChannel>() {
					@Override
					public void initChannel(SocketChannel ch) throws Exception {
						//编码
						ch.pipeline().addLast(new NettyEncoder());
						//解码
						ch.pipeline().addLast(new NettyDecoder());
						//心跳
						ch.pipeline().addLast(new IdleStateHandler(0, 0, nettyServerConfig.getServerChannelMaxIdleTimeSeconds()));
						//业务处理
						ch.pipeline().addLast(defaultEventExecutorGroup,new NettyConnetManageHandler(),new NettyServerHandler());
					}
				});
		if (nettyServerConfig.isServerPooledByteBufAllocatorEnable()) {
            childHandler.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        }
		try {
            ChannelFuture sync = this.serverBootstrap.bind().sync();
            InetSocketAddress addr = (InetSocketAddress) sync.channel().localAddress();
            this.port = addr.getPort();
            log.info("netty server already started！monitor at port {}",this.port);
        } catch (InterruptedException e1) {
            throw new RuntimeException("this.serverBootstrap.bind().sync() InterruptedException", e1);
        }
	}
	
	class NettyConnetManageHandler extends ChannelDuplexHandler{
		@Override
	    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
			log.warn("NETTY SERVER PIPELINE: exceptionCaught {}", ctx.channel().remoteAddress());
            RemotingUtil.closeChannel(ctx.channel());
	    }
		@Override
		public void userEventTriggered(ChannelHandlerContext ctx, Object evt)throws Exception {
			if (evt instanceof IdleStateEvent) {
                IdleStateEvent evnet = (IdleStateEvent) evt;
                if (evnet.state().equals(IdleState.ALL_IDLE)) {
                    log.warn("NETTY SERVER PIPELINE: IDLE exception [{}]", ctx.channel().remoteAddress());
                    RemotingUtil.closeChannel(ctx.channel());
                }
            }
            ctx.fireUserEventTriggered(evt);
		}
	}
	
	class NettyServerHandler extends SimpleChannelInboundHandler<RemotingCommand> {
		@Override
	    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
	        ctx.flush();
	    }
		@Override
		protected void channelRead0(ChannelHandlerContext ctx, RemotingCommand msg)	throws Exception {
			processMessageReceived(ctx, msg);
		}
	}
	
	@Override
	public void shutdown() {
		this.eventLoopGroupBoss.shutdownGracefully();

        this.eventLoopGroupSelector.shutdownGracefully();
        
        this.defaultEventExecutorGroup.shutdownGracefully();
	}

	@Override
	public void registerDefaultProcessor(RequestProcessor processor,ExecutorService executor) {
		this.defaultRequestProcessor = new Pair<RequestProcessor, ExecutorService>(processor, executor);
	}

	@Override
	public int localListenPort() {
		return this.port;
	}

	@Override
	public void registerProcessor(int requestCode, RequestProcessor processor,ExecutorService executor) {
		Pair<RequestProcessor, ExecutorService> pair = new Pair<RequestProcessor, ExecutorService>(processor, executor);
		this.processorTable.put(requestCode, pair);
	}
}
