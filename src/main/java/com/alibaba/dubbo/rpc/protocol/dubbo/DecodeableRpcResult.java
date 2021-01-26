/*
 * Copyright 1999-2011 Alibaba Group.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.alibaba.dubbo.rpc.protocol.dubbo;

import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.serialize.Cleanable;
import com.alibaba.dubbo.common.serialize.ObjectInput;
import com.alibaba.dubbo.common.utils.Assert;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.Codec;
import com.alibaba.dubbo.remoting.Decodeable;
import com.alibaba.dubbo.remoting.exchange.Response;
import com.alibaba.dubbo.remoting.transport.CodecSupport;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.util.Map;

/**
 * @author <a href="mailto:gang.lvg@alibaba-inc.com">kimi</a>
 */
public class DecodeableRpcResult extends RpcResult implements Codec, Decodeable {

    private static final Logger log = LoggerFactory.getLogger(DecodeableRpcResult.class);

    private Channel channel;

    private byte serializationType;

    private InputStream inputStream;

    private Response response;

    private Invocation invocation;

    private volatile boolean hasDecoded;

    public DecodeableRpcResult(Channel channel, Response response, InputStream is, Invocation invocation, byte id) {
        Assert.notNull(channel, "channel == null");
        Assert.notNull(response, "response == null");
        Assert.notNull(is, "inputStream == null");
        this.channel = channel;
        this.response = response;
        this.inputStream = is;
        this.invocation = invocation;
        this.serializationType = id;
    }

    public void encode(Channel channel, OutputStream output, Object message) throws IOException {
        throw new UnsupportedOperationException();
    }


    @Override
    public Object decode(Channel channel, InputStream input) throws IOException {
        ObjectInput in = CodecSupport.getSerialization(channel.getUrl(), serializationType)
                .deserialize(channel.getUrl(), input);

        try {
            byte flag = in.readByte();
            switch (flag) {
                case DubboCodec.RESPONSE_NULL_VALUE:
                    break;
                case DubboCodec.RESPONSE_VALUE:
                    handleValue(in);
                    break;
                case DubboCodec.RESPONSE_WITH_EXCEPTION:
                    handleException(in);
                    break;
                case DubboCodec.RESPONSE_NULL_VALUE_WITH_ATTACHMENTS:
                    handleAttachment(in);
                    break;
                case DubboCodec.RESPONSE_VALUE_WITH_ATTACHMENTS:
                    handleValue(in);
                    handleAttachment(in);
                    break;
                case DubboCodec.RESPONSE_WITH_EXCEPTION_WITH_ATTACHMENTS:
                    handleException(in);
                    handleAttachment(in);
                    break;
                default:
                    throw new IOException("Unknown result flag, expect '0' '1' '2' '3' '4' '5', but received: " + flag);
            }
            return this;
        } finally {
            // modified by lishen
            if (in instanceof Cleanable) {
                ((Cleanable) in).cleanup();
            }
        }
    }

    @Override
    public void decode() throws Exception {
        if (!hasDecoded && channel != null && inputStream != null) {
            try {
                decode(channel, inputStream);
            } catch (Throwable e) {
                if (log.isWarnEnabled()) {
                    log.warn("Decode rpc result failed: " + e.getMessage(), e);
                }
                response.setStatus(Response.CLIENT_ERROR);
                response.setErrorMessage(StringUtils.toString(e));
            } finally {
                hasDecoded = true;
            }
        }
    }

    private void handleValue(ObjectInput in) throws IOException {
        try {
            Type[] returnTypes = RpcUtils.getReturnTypes(invocation);
            Object value = null;
            if (returnTypes == null || returnTypes.length == 0) {
                value = in.readObject();
            } else if (returnTypes.length == 1) {
                value = in.readObject((Class<?>) returnTypes[0]);
            } else {
                value = in.readObject((Class<?>) returnTypes[0], returnTypes[1]);
            }
            setValue(value);
        } catch (ClassNotFoundException e) {
            rethrow(e);
        }
    }

    private void handleException(ObjectInput in) throws IOException {
        try {
            Object obj = in.readObject();
            if (!(obj instanceof Throwable)) {
                throw new IOException("Response data error, expect Throwable, but get " + obj);
            }
            setException((Throwable) obj);
        } catch (ClassNotFoundException e) {
            rethrow(e);
        }
    }

    private void handleAttachment(ObjectInput in) throws IOException {
        try {
            setAttachments((Map<String, String>) in.readObject(Map.class));
        } catch (ClassNotFoundException e) {
            rethrow(e);
        }
    }

    private void rethrow(Exception e) throws IOException {
        throw new IOException(StringUtils.toString("Read response data failed.", e));
    }
}