package foo.bar;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ReplayingDecoder;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.util.ByteProcessor;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.apache.commons.cli.*;

public class Socks5Server {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Socks5Server.class);
    private final static Socks5Handler SOCKS_5_HANDLER = new Socks5Handler();

    public void startSocks5Server(String ip, int port, io.netty.channel.ChannelHandler encoder) throws Exception {
        log.info("Start Socks5 Server, ip={}, port={}", ip, port);

        EventLoopGroup bossGroup = new NioEventLoopGroup();
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline().addLast("ReadTimeoutHandler", new ReadTimeoutHandler(30));
                            ch.pipeline().addLast("WriteTimeoutHandler", new WriteTimeoutHandler(30));
                            ch.pipeline().addLast(new Socks5Decoder());
                            if (encoder != null) {
                                ch.pipeline().addLast(encoder);
                            }
                            ch.pipeline().addLast(SOCKS_5_HANDLER);
                        }
                    });
            ChannelFuture f = b.bind(ip, port).sync();
            f.channel().closeFuture().sync();
            System.out.println("server quit");
        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }

    public static void main(String[] args) {
        CommandLineParser parser = new DefaultParser();
        Options options = new Options();
        options.addOption(Option.builder("i").longOpt("ip").desc("listen address").hasArg().build());
        options.addOption(Option.builder("p").longOpt("port").desc("listen port").type(Number.class).hasArg().build());
        options.addOption(Option.builder("h").longOpt("help").desc("print help").build());

        try {
            CommandLine cmd = parser.parse(options, args);
            if (cmd.hasOption("help") || !cmd.hasOption("ip") || !cmd.hasOption("port")) {
                HelpFormatter formatter = new HelpFormatter();
                formatter.printHelp("java_socks5 <ip> <port>", options);
                return;
            }

            String ip = cmd.getOptionValue("ip");
            int port = ((Number) cmd.getParsedOptionValue("port")).intValue();

            new Socks5Server().startSocks5Server(ip, port, null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

class Socks5CopyHandler extends ChannelInboundHandlerAdapter {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Socks5CopyHandler.class);

    private String readHost;
    private String writeHost;

    private Channel peer;

    Socks5CopyHandler(String readHost, String writeHost, Channel peer) {
        this.readHost = readHost;
        this.writeHost = writeHost;
        this.peer = peer;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        final int bytes = ((ByteBuf) msg).readableBytes();
        peer.write(msg).addListener(future -> {
            if (!future.isSuccess()) {
                log.error("write failed, peer={}", peer.remoteAddress());
                ctx.close();
            }
        });
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        peer.flush();
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        log.debug("connection isWritable={}, addr={}", ctx.channel().isWritable(), readHost);
        peer.config().setAutoRead(ctx.channel().isWritable());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.error("connection closed, ctx={}", ctx);
        peer.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("exception caught, ctx={}", ctx, cause);
        ctx.close();
    }
}

class Utils {
    static ByteBuf copy(ChannelHandlerContext ctx, byte[] src) {
        ByteBuf buf = ctx.alloc().buffer(src.length);
        buf.writeBytes(src);
        return buf;
    }
}

class UpstreamAddr {
    String addr;
    int port;
}

@ChannelHandler.Sharable
class Socks5Handler extends SimpleChannelInboundHandler<UpstreamAddr> {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Socks5Handler.class);

    private final static byte[] connectRsp = {0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
    private final static byte[] connectFailRsp = {0x05, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("exception caught, ctx={}", ctx, cause);
        ctx.close();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, UpstreamAddr msg) throws Exception {
        InetSocketAddress addr = (InetSocketAddress) ctx.channel().remoteAddress();
        String readHost = addr.getHostString();
        String writeHost = msg.addr;

        Channel downStream = ctx.channel();
        Socks5CopyHandler upStreamHandler = new Socks5CopyHandler(writeHost, readHost, downStream);

        Bootstrap b = new Bootstrap()
                .group(downStream.eventLoop())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline().addFirst("ReadTimeoutHandler", new ReadTimeoutHandler(300));
                        ch.pipeline().addLast("WriteTimeoutHandler", new WriteTimeoutHandler(30));
                        ch.pipeline().addLast(upStreamHandler);
                    }
                });

        ChannelFuture f = b.connect(msg.addr, msg.port).addListener((ChannelFuture future) -> {
            if (future.isSuccess()) {
                Channel upStream = future.channel();
                log.info("connect success, host={}", upStream.remoteAddress());
                ctx.pipeline().remove("tmp");
                ctx.pipeline().addFirst("ReadTimeoutHandler", new ReadTimeoutHandler(300));
                ctx.pipeline().addLast(new Socks5CopyHandler(readHost, writeHost, upStream));
                ctx.writeAndFlush(Utils.copy(ctx, connectRsp));
            } else {
                log.error("connect failed: host={}, cause={}", future.channel().remoteAddress(), future.cause());
                ctx.writeAndFlush(Utils.copy(ctx, connectFailRsp)).addListener(ChannelFutureListener.CLOSE);
            }
        });

        ctx.pipeline().remove(this);
        ctx.pipeline().remove("ReadTimeoutHandler");
        ctx.pipeline().addLast("tmp", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                log.error("unexpected incoming data when connecting to peer, ctx={}", ctx);
                ctx.close();
            }

            @Override
            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                log.error("downstream closed before upstream connected, ctx={}", ctx);
                f.cancel(true);
            }
        });
    }
}

enum Socks5MsgType {
    HANDSHAKE,
    CONNECT
}

class Socks5Decoder extends ReplayingDecoder<Socks5MsgType> {
    private final static byte[] handshakeRsp = {0x05, 0x00};

    Socks5Decoder() {
        super(Socks5MsgType.HANDSHAKE);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (state() == Socks5MsgType.HANDSHAKE) {
            Byte c = in.readByte();
            if (c != 0x05) {
                System.out.println("invalid proto");
                ctx.close();
                return;
            }
            int len = in.readByte();
            in.skipBytes(len);
            ctx.writeAndFlush(Utils.copy(ctx, handshakeRsp));
            checkpoint(Socks5MsgType.CONNECT);
            return;
        }

        if (state() == Socks5MsgType.CONNECT) {
            int ver = in.readByte();
            int cmd = in.readByte();
            if (ver != 0x05 || cmd != 1) {
                System.out.printf("invalid proto, ver=%d, cmd=%d\n", ver, cmd);
                ctx.close();
                return;
            }
            in.skipBytes(1);
            int type = in.readByte();
            switch (type) {
                case 1: {
                    UpstreamAddr addr = new UpstreamAddr();
                    byte[] buf = new byte[4];
                    in.readBytes(buf);
                    try {
                        addr.addr = InetAddress.getByAddress(buf).getHostAddress();
                    } catch (UnknownHostException e) {
                        e.printStackTrace();
                        ctx.close();
                        return;
                    }
                    addr.port = in.readUnsignedShort();
                    ctx.pipeline().remove(this);
                    out.add(addr);
                    break;
                }
                case 3: {
                    int len = in.readByte();
                    UpstreamAddr addr = new UpstreamAddr();
                    byte[] data = new byte[len];
                    in.readBytes(data);
                    addr.addr = new String(data);
                    addr.port = in.readUnsignedShort();
                    ctx.pipeline().remove(this);
                    out.add(addr);
                    break;
                }
                default:
                    System.out.println("Address type not supported: type=" + type);
                    ctx.close();
            }
        }
    }
}
