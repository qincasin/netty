/*
* Copyright 2014 The Netty Project
*
* The Netty Project licenses this file to you under the Apache License,
* version 2.0 (the "License"); you may not use this file except in compliance
* with the License. You may obtain a copy of the License at:
*
*   https://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
* WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
* License for the specific language governing permissions and limitations
* under the License.
*/
package io.netty.channel;

import io.netty.util.concurrent.EventExecutor;

final class DefaultChannelHandlerContext extends AbstractChannelHandlerContext {

    private final ChannelHandler handler;

    //参数1：pipeline 外层容器，盛装CTX(handler) 的管道容器
    //参数2：executor 事件执行器   一般情况是 null，除非 指定了该事件执行器
    //参数3：name
    //参数4：handler  业务真正实现的处理器
    DefaultChannelHandlerContext(
            DefaultChannelPipeline pipeline, EventExecutor executor, String name, ChannelHandler handler) {
        //参数1：pipeline 外层容器，盛装CTX(handler) 的管道容器
        //参数2：executor 事件执行器   一般情况是 null，除非 指定了该事件执行器
        //参数3：name
        //参数4：handler 的处理器真实的类型
        super(pipeline, executor, name, handler.getClass());
        this.handler = handler;
    }

    @Override
    public ChannelHandler handler() {
        return handler;
    }
}
