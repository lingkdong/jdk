/*
 * Copyright (c) 2005, 2021, Oracle and/or its affiliates. All rights reserved.
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

package sun.nio.ch;

import java.io.IOException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static sun.nio.ch.EPoll.EPOLLIN;
import static sun.nio.ch.EPoll.EPOLL_CTL_ADD;
import static sun.nio.ch.EPoll.EPOLL_CTL_DEL;
import static sun.nio.ch.EPoll.EPOLL_CTL_MOD;


/**
 * Linux epoll based Selector implementation
 */

class EPollSelectorImpl extends SelectorImpl {

    // maximum number of events to poll in one call to epoll_wait
    //IOUtil.fdLimit() :系统对当前PID fd 限制数
    //可以 cat /proc/$pid/limits 命令查看
    //Limit                     Soft Limit           Hard Limit           Units
    //Max open files            100002               100002               files
    private static final int NUM_EPOLLEVENTS = Math.min(IOUtil.fdLimit(), 1024);

    // epoll file descriptor
    private final int epfd;

    // address of poll array when polling with epoll_wait
    private final long pollArrayAddress;

    // eventfd object used for interrupt
    private final EventFD eventfd;

    // maps file descriptor to selection key, synchronize on selector
    private final Map<Integer, SelectionKeyImpl> fdToKey = new HashMap<>();

    // pending new registrations/updates, queued by setEventOps
    private final Object updateLock = new Object();
    private final Deque<SelectionKeyImpl> updateKeys = new ArrayDeque<>();//注册或更新的事件队列

    // interrupt triggering and clearing
    private final Object interruptLock = new Object();
    private boolean interruptTriggered;

    EPollSelectorImpl(SelectorProvider sp) throws IOException {
        super(sp);
        // epoll create
        //调用EPoll类的create创建epfd;
        this.epfd = EPoll.create();
        //分配轮询事件数
        this.pollArrayAddress = EPoll.allocatePollArray(NUM_EPOLLEVENTS);

        try {
            this.eventfd = new EventFD();
            IOUtil.configureBlocking(IOUtil.newFD(eventfd.efd()), false);//非阻塞IO
        } catch (IOException ioe) {
            EPoll.freePollArray(pollArrayAddress);
            FileDispatcherImpl.closeIntFD(epfd);
            throw ioe;
        }

        // register the eventfd object for wakeups
        EPoll.ctl(epfd, EPOLL_CTL_ADD, eventfd.efd(), EPOLLIN);
    }

    private void ensureOpen() {
        // 父类方法 SelectorImpl->AbstractSelector.isOpen 判断选择器是否关闭
        if (!isOpen())
            throw new ClosedSelectorException();
    }

    /**
     *  @Native public static final int EOF = -1;              // End of file 文件结束
     *     @Native public static final int UNAVAILABLE = -2;      // Nothing available (non-blocking) 无法使用
     *     @Native public static final int INTERRUPTED = -3;      // System call interrupted 系统中断
     *     @Native public static final int UNSUPPORTED = -4;      // Operation not supported 不支持操作
     *     @Native public static final int THROWN = -5;           // Exception thrown in JNI code 出错
     *     @Native public static final int UNSUPPORTED_CASE = -6; // This case not supported 不支持this case
     * @param action
     * @param timeout
     * @return
     * @throws IOException
     */
    @Override
    protected int doSelect(Consumer<SelectionKey> action, long timeout)
        throws IOException
    {
        assert Thread.holdsLock(this);

        // epoll_wait timeout is int
        //设置epoll_wait 的超时时间
        int to = (int) Math.min(timeout, Integer.MAX_VALUE);
        boolean blocking = (to != 0);
        boolean timedPoll = (to > 0);

        int numEntries;
        //EPoll.ctl: EPOLL_CTL_DEL,EPOLL_CTL_ADD,EPOLL_CTL_MOD
        //添加删除或修改 epoll 的监听fd 事件
        processUpdateQueue();
        //父类方法 SelectorImpl.processDeregisterQueue 注销fd事件和相关key
        //1.implDereg
        //2.selectedKeys.remove(ski);
        // 3.keys.remove(ski);
        //4.((SelChImpl)ch).kill();
        deregister(ski);
        processDeregisterQueue();
        try {
            begin(blocking);
            //轮询监听事件
            do {
                long startTime = timedPoll ? System.nanoTime() : 0;
                //epoll_wait等待监听事件
                numEntries = EPoll.wait(epfd, pollArrayAddress, NUM_EPOLLEVENTS, to);
                if (numEntries == IOStatus.INTERRUPTED && timedPoll) {
                    // timed poll interrupted so need to adjust timeout
                    long adjust = System.nanoTime() - startTime;
                    to -= TimeUnit.MILLISECONDS.convert(adjust, TimeUnit.NANOSECONDS);
                    if (to <= 0) {
                        // timeout expired so no retry
                        numEntries = 0;
                    }
                }
            } while (numEntries == IOStatus.INTERRUPTED);//System call interrupted 系统中断
            assert IOStatus.check(numEntries);

        } finally {
            end(blocking);
        }
        processDeregisterQueue();
        return processEvents(numEntries, action);
    }

    /**
     * Process changes to the interest ops.
     */
    private void processUpdateQueue() {
        assert Thread.holdsLock(this);

        synchronized (updateLock) {
            SelectionKeyImpl ski;
            while ((ski = updateKeys.pollFirst()) != null) {
                if (ski.isValid()) {
                    int fd = ski.getFDVal();
                    // add to fdToKey if needed
                    SelectionKeyImpl previous = fdToKey.putIfAbsent(fd, ski);
                    assert (previous == null) || (previous == ski);

                    int newEvents = ski.translateInterestOps();
                    int registeredEvents = ski.registeredEvents();
                    if (newEvents != registeredEvents) {
                        if (newEvents == 0) {
                            // remove from epoll
                            //Epoll.ctl方法
                            //epfd: epoll file descriptor 可以看成是 epoll中的fd集合
                            EPoll.ctl(epfd, EPOLL_CTL_DEL, fd, 0);
                        } else {
                            if (registeredEvents == 0) {
                                // add to epoll
                                //向epoll fd中注册 fd,且fd 关联 InterestOps事件
                                //fd->注册到epfd
                                //event->关联到fd
                                EPoll.ctl(epfd, EPOLL_CTL_ADD, fd, newEvents);
                            } else {
                                // modify events
                                //修改 fd关联的事件
                                EPoll.ctl(epfd, EPOLL_CTL_MOD, fd, newEvents);
                            }
                        }
                        ski.registeredEvents(newEvents);
                    }
                }
            }
        }
    }

    /**
     * Process the polled events.
     * If the interrupt fd has been selected, drain it and clear the interrupt.
     */
    private int processEvents(int numEntries, Consumer<SelectionKey> action)
        throws IOException
    {
        assert Thread.holdsLock(this);

        boolean interrupted = false;
        int numKeysUpdated = 0;
        for (int i=0; i<numEntries; i++) {
            long event = EPoll.getEvent(pollArrayAddress, i);
            int fd = EPoll.getDescriptor(event);//获取fd
            if (fd == eventfd.efd()) {
                interrupted = true;//如果是中断事件
            } else {
                SelectionKeyImpl ski = fdToKey.get(fd);
                if (ski != null) {
                    int rOps = EPoll.getEvents(event);
                    //如果不是中断事件 则 加入处理程序
                    //processReadyEvents 变量 查看是否在监听的key中 有这返回1 累加起来则是事件数
                    numKeysUpdated += processReadyEvents(rOps, ski, action);
                }
            }
        }

        if (interrupted) {
            clearInterrupt();//清除中断
        }

        return numKeysUpdated;
    }

    @Override
    protected void implClose() throws IOException {
        assert Thread.holdsLock(this);

        // prevent further wakeup
        synchronized (interruptLock) {
            interruptTriggered = true;
        }

        FileDispatcherImpl.closeIntFD(epfd);
        EPoll.freePollArray(pollArrayAddress);

        eventfd.close();
    }

    @Override
    protected void implDereg(SelectionKeyImpl ski) throws IOException {
        assert !ski.isValid();
        assert Thread.holdsLock(this);

        int fd = ski.getFDVal();
        if (fdToKey.remove(fd) != null) {
            if (ski.registeredEvents() != 0) {
                EPoll.ctl(epfd, EPOLL_CTL_DEL, fd, 0);
                ski.registeredEvents(0);
            }
        } else {
            assert ski.registeredEvents() == 0;
        }
    }

    @Override
    public void setEventOps(SelectionKeyImpl ski) {
        ensureOpen();
        synchronized (updateLock) {
            updateKeys.addLast(ski);
        }
    }

    @Override
    public Selector wakeup() {
        synchronized (interruptLock) {
            if (!interruptTriggered) {
                try {
                    eventfd.set();
                } catch (IOException ioe) {
                    throw new InternalError(ioe);
                }
                interruptTriggered = true;
            }
        }
        return this;
    }

    private void clearInterrupt() throws IOException {
        synchronized (interruptLock) {
            eventfd.reset();
            interruptTriggered = false;
        }
    }
}
