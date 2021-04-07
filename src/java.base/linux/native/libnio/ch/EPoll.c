/*
 * Copyright (c) 2008, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

 #include <dlfcn.h>
 #include <unistd.h>
 #include <sys/types.h>
 #include <sys/epoll.h>

#include "jni.h"
#include "jni_util.h"
#include "jvm.h"
#include "jlong.h"
#include "nio.h"
#include "nio_util.h"

#include "sun_nio_ch_EPoll.h"

JNIEXPORT jint JNICALL
Java_sun_nio_ch_EPoll_eventSize(JNIEnv* env, jclass clazz)
{
    return sizeof(struct epoll_event);
}

JNIEXPORT jint JNICALL
Java_sun_nio_ch_EPoll_eventsOffset(JNIEnv* env, jclass clazz)
{
    return offsetof(struct epoll_event, events);
}

JNIEXPORT jint JNICALL
Java_sun_nio_ch_EPoll_dataOffset(JNIEnv* env, jclass clazz)
{
    return offsetof(struct epoll_event, data);
}
/*
*
* c++代码 EPoll_create
* linux 命令  man epoll_create1 可以查看系统命令
* EPOLL_CLOEXEC
    Set the close-on-exec (FD_CLOEXEC) flag on the new file descriptor.
    See the description of the O_CLOEXEC flag in open(2) for reasons why this may  be  use‐ ful.
    在线文档见 https://www.kernel.org/doc/man-pages/
    https://man7.org/linux/man-pages/man2/epoll_create1.2.html
*/
JNIEXPORT jint JNICALL
Java_sun_nio_ch_EPoll_create(JNIEnv *env, jclass clazz) {
    //fd :file descriptor ,linux 一切系统皆文件
    //创建一个epoll对象
    int epfd = epoll_create1(EPOLL_CLOEXEC);
    if (epfd < 0) {
        JNU_ThrowIOExceptionWithLastError(env, "epoll_create1 failed");
    }
    return epfd;
}
/**
*epoll_ctl 添加 删除或 修改 fd监听事件
* // opcodes
     static final int EPOLL_CTL_ADD  = 1;
     static final int EPOLL_CTL_DEL  = 2;
     static final int EPOLL_CTL_MOD  = 3;
  man epoll_ctl
     EPOLL_CTL_ADD
                Register the target file descriptor fd on the epoll instance referred to by the file descriptor epfd and associate the event event with  the  internal  file
                linked to fd.
     EPOLL_CTL_MOD
            Change the event event associated with the target file descriptor fd.
     EPOLL_CTL_DEL
            Remove (deregister) the target file descriptor fd from the epoll instance referred to by epfd.  The event is ignored and can be NULL (but see BUGS below).

   https://man7.org/linux/man-pages/man2/epoll_ctl.2.html
**/
JNIEXPORT jint JNICALL
Java_sun_nio_ch_EPoll_ctl(JNIEnv *env, jclass clazz, jint epfd,
                          jint opcode, jint fd, jint events)
{
    struct epoll_event event;
    int res;

    event.events = events;
    event.data.fd = fd;

    res = epoll_ctl(epfd, (int)opcode, (int)fd, &event);
    return (res == 0) ? 0 : errno;
}
/*
EPoll_wait
等待IO事件，timeout=-1 block 阻塞,timeout=0  return immediately 立即返回结果
man epoll_wait
https://man7.org/linux/man-pages/man2/epoll_wait.2.html
*/
JNIEXPORT jint JNICALL
Java_sun_nio_ch_EPoll_wait(JNIEnv *env, jclass clazz, jint epfd,
                           jlong address, jint numfds, jint timeout)
{
    struct epoll_event *events = jlong_to_ptr(address);
    int res = epoll_wait(epfd, events, numfds, timeout);
    if (res < 0) {
        if (errno == EINTR) {
            return IOS_INTERRUPTED;
        } else {
            JNU_ThrowIOExceptionWithLastError(env, "epoll_wait failed");
            return IOS_THROWN;
        }
    }
    return res;
}
