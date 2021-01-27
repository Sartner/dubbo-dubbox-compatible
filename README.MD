# 简介

解决dubbox、dubbo互相调用时会出错的问题

无需Provider、Consumer同时引用，单方引用即可，解决大规模升级困难的问题。

# 版本

| 框架     | 源码地址                                 | branch/tag    | version |
| -------- | ---------------------------------------- | ------------- | ------- |
| *dubbox* | https://github.com/dangdangdotcom/dubbox | branch:master | 2.8.4   |
| *dubbo*  | https://github.com/apache/dubbo          | tag:2.7.7     | 2.7.7   |

# Maven Dependency

```xml
<dependency>
    <groupId>io.github.sartner</groupId>
    <artifactId>dubbo-dubbox-compatible</artifactId>
    <version>2.7.7</version>
</dependency>
```

# 使用方式

将Maven依赖添加到 `dubbo` dependency 上方，以实现优先加载。

```xml
<dependency>
    <groupId>io.github.sartner</groupId>
    <artifactId>dubbo-dubbox-compatible</artifactId>
    <version>2.7.7</version>
</dependency>
<dependency>
    <groupId>org.apache.dubbo</groupId>
    <artifactId>dubbo</artifactId>
    <version>2.7.7</version>
</dependency>
```

可以在启动时添加 JVM 参数 `-verbose:class` 确认是否加载正确。

```
[Loaded org.apache.dubbo.rpc.protocol.dubbo.DubboCodec from file:/xxx/lib/dubbo-dubbox-compatible-2.7.7.jar]
```

