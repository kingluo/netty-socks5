# netty-socks5
netty socks5 server

# Build and Run

```
./gradlew shadowjar # gradlew.bat shadowjar
java -jar build\libs\netty-socks5-1.0-SNAPSHOT-all.jar -h
```

# lib usage

```java
void startSocks5Server(String ip, int port, io.netty.channel.ChannelHandler encoder)
```

For example:

```java
new Socks5Server().startSocks5Server(ip, port, null);
```
