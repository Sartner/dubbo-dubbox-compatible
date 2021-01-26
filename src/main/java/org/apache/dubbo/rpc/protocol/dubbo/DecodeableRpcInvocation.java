/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc.protocol.dubbo;


import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.serialize.Cleanable;
import org.apache.dubbo.common.serialize.ObjectInput;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.Codec;
import org.apache.dubbo.remoting.Decodeable;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.transport.CodecSupport;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.MethodDescriptor;
import org.apache.dubbo.rpc.model.ServiceDescriptor;
import org.apache.dubbo.rpc.model.ServiceRepository;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import static org.apache.dubbo.common.URL.buildKey;
import static org.apache.dubbo.common.constants.CommonConstants.*;
import static org.apache.dubbo.rpc.protocol.dubbo.CallbackServiceCodec.decodeInvocationArgument;

public class DecodeableRpcInvocation extends RpcInvocation implements Codec, Decodeable {

    private static final Logger log = LoggerFactory.getLogger(DecodeableRpcInvocation.class);

    private Channel channel;

    private byte serializationType;

    private InputStream inputStream;

    private Request request;

    private volatile boolean hasDecoded;

    public DecodeableRpcInvocation(Channel channel, Request request, InputStream is, byte id) {
        Assert.notNull(channel, "channel == null");
        Assert.notNull(request, "request == null");
        Assert.notNull(is, "inputStream == null");
        this.channel = channel;
        this.request = request;
        this.inputStream = is;
        this.serializationType = id;
    }

    @Override
    public void decode() throws Exception {
        if (!hasDecoded && channel != null && inputStream != null) {
            try {
                decode(channel, inputStream);
            } catch (Throwable e) {
                if (log.isWarnEnabled()) {
                    log.warn("Decode rpc invocation failed: " + e.getMessage(), e);
                }
                request.setBroken(true);
                request.setData(e);
            } finally {
                hasDecoded = true;
            }
        }
    }

    @Override
    public void encode(Channel channel, OutputStream output, Object message) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object decode(Channel channel, InputStream input) throws IOException {
        ObjectInput in = CodecSupport.getSerialization(channel.getUrl(), serializationType)
                .deserialize(channel.getUrl(), input);

        String dubboVersion = in.readUTF();

        if (StringUtils.isNotEmpty(dubboVersion) && dubboVersion.startsWith(DUBBOX_VERSION)){
            // dubbox 基于 dubbo 2.5.x 版本，在源码的 DubboCodec 类中，版本号写死为2.0.0
            // 这里我们把 Request 的 Version 与源码中设置相同 Protocol Version 2.0.0
            // 不知道为什么 dubbo 2.7.x 的源码里 request setVersion 写死成 Version.getProtocolVersion()，不应该跟请求方协议版本一致吗？
            request.setVersion("2.0.0");

            return dubboxDecode(in);
        }
        request.setVersion(dubboVersion);
        setAttachment(DUBBO_VERSION_KEY, dubboVersion);

        String path = in.readUTF();
        setAttachment(PATH_KEY, path);
        setAttachment(VERSION_KEY, in.readUTF());

        setMethodName(in.readUTF());

        String desc = in.readUTF();
        setParameterTypesDesc(desc);

        try {
            Object[] args = DubboCodec.EMPTY_OBJECT_ARRAY;
            Class<?>[] pts = DubboCodec.EMPTY_CLASS_ARRAY;
            if (desc.length() > 0) {
//                if (RpcUtils.isGenericCall(path, getMethodName()) || RpcUtils.isEcho(path, getMethodName())) {
//                    pts = ReflectUtils.desc2classArray(desc);
//                } else {
                ServiceRepository repository = ApplicationModel.getServiceRepository();
                ServiceDescriptor serviceDescriptor = repository.lookupService(path);
                if (serviceDescriptor != null) {
                    MethodDescriptor methodDescriptor = serviceDescriptor.getMethod(getMethodName(), desc);
                    if (methodDescriptor != null) {
                        pts = methodDescriptor.getParameterClasses();
                        this.setReturnTypes(methodDescriptor.getReturnTypes());
                    }
                }
                if (pts == DubboCodec.EMPTY_CLASS_ARRAY) {
                    if (!RpcUtils.isGenericCall(path, getMethodName()) && !RpcUtils.isEcho(path, getMethodName())) {
                        throw new IllegalArgumentException("Service not found:" + path + ", " + getMethodName());
                    }
                    pts = ReflectUtils.desc2classArray(desc);
                }
//                }

                args = new Object[pts.length];
                for (int i = 0; i < args.length; i++) {
                    try {
                        args[i] = in.readObject(pts[i]);
                    } catch (Exception e) {
                        if (log.isWarnEnabled()) {
                            log.warn("Decode argument failed: " + e.getMessage(), e);
                        }
                    }
                }
            }
            setParameterTypes(pts);

            Map<String, Object> map = in.readAttachments();
            if (map != null && map.size() > 0) {
                Map<String, Object> attachment = getObjectAttachments();
                if (attachment == null) {
                    attachment = new HashMap<>();
                }
                attachment.putAll(map);
                setObjectAttachments(attachment);
            }

            //decode argument ,may be callback
            for (int i = 0; i < args.length; i++) {
                args[i] = decodeInvocationArgument(channel, this, pts, i, args[i]);
            }

            setArguments(args);
            String targetServiceName = buildKey((String) getAttachment(PATH_KEY),
                    getAttachment(GROUP_KEY),
                    getAttachment(VERSION_KEY));
            setTargetServiceUniqueName(targetServiceName);
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read invocation data failed.", e));
        } finally {
            if (in instanceof Cleanable) {
                ((Cleanable) in).cleanup();
            }
        }
        return this;
    }

    private static final String DUBBOX_VERSION="2.8.4";

    private Object dubboxDecode(ObjectInput in) throws IOException {
        try {
            setAttachment(DUBBO_VERSION_KEY, DUBBOX_VERSION);
            setAttachment(PATH_KEY, in.readUTF());
            setAttachment(VERSION_KEY, in.readUTF());

            setMethodName(in.readUTF());
            try {
                Object[] args;
                Class<?>[] pts;

                // NOTICE modified by lishen
                int argNum = in.readInt();
                if (argNum >= 0) {
                    if (argNum == 0) {
                        pts = DubboCodec.EMPTY_CLASS_ARRAY;
                        args = DubboCodec.EMPTY_OBJECT_ARRAY;
                    } else {
                        args = new Object[argNum];
                        pts = new Class[argNum];
                        for (int i = 0; i < args.length; i++) {
                            try {
                                args[i] = in.readObject();
                                pts[i] = args[i].getClass();
                            } catch (Exception e) {
                                if (log.isWarnEnabled()) {
                                    log.warn("Decode argument failed: " + e.getMessage(), e);
                                }
                            }
                        }
                    }
                } else {
                    String desc = in.readUTF();
                    if (desc.length() == 0) {
                        pts = DubboCodec.EMPTY_CLASS_ARRAY;
                        args = DubboCodec.EMPTY_OBJECT_ARRAY;
                    } else {
                        pts = ReflectUtils.desc2classArray(desc);
                        args = new Object[pts.length];
                        for (int i = 0; i < args.length; i++) {
                            try {
                                args[i] = in.readObject(pts[i]);
                            } catch (Exception e) {
                                if (log.isWarnEnabled()) {
                                    log.warn("Decode argument failed: " + e.getMessage(), e);
                                }
                            }
                        }
                    }
                }
                setParameterTypes(pts);

                Map<String, String> map = (Map<String, String>) in.readObject(Map.class);
                if (map != null && map.size() > 0) {
                    Map<String, String> attachment = getAttachments();
                    if (attachment == null) {
                        attachment = new HashMap<String, String>();
                    }
                    attachment.putAll(map);
                    setAttachments(attachment);
                }
                //decode argument ,may be callback
                for (int i = 0; i < args.length; i++) {
                    args[i] = decodeInvocationArgument(channel, this, pts, i, args[i]);
                }

                setArguments(args);

            } catch (ClassNotFoundException e) {
                throw new IOException(StringUtils.toString("Read invocation data failed.", e));
            }
        } finally {
            // modified by lishen
            if (in instanceof Cleanable) {
                ((Cleanable) in).cleanup();
            }
        }
        return this;
    }
}
