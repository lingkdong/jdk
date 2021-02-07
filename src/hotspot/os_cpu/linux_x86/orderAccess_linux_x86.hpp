/*
 * Copyright (c) 2003, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
 *
 */

#ifndef OS_CPU_LINUX_X86_ORDERACCESS_LINUX_X86_HPP
#define OS_CPU_LINUX_X86_ORDERACCESS_LINUX_X86_HPP

// Included in orderAccess.hpp header file.

// Compiler version last used for testing: gcc 4.8.2
// Please update this information when this file changes

// Implementation of class OrderAccess.

// A compiler barrier, forcing the C++ compiler to invalidate all memory assumptions
//设置 memory 无效 缓存无效 需要重新同步 工作内存和主内存
static inline void compiler_barrier() {
  __asm__ volatile ("" : : : "memory");
}
//compiler_barrier: 编译器屏障
//jsR 4种内存屏障
// load1;loadload;load2
//load2及后续读取操作在读取数据之前保证 load1要读取的数据已读取完毕
inline void OrderAccess::loadload()   { compiler_barrier(); }
//store1;storestore;store2
//在store2及后续写入操作执行之前，保证store1的写操作对其他处理器可见
inline void OrderAccess::storestore() { compiler_barrier(); }
//load1;loadstore;store2
//在store2及后续写入操作执行之前，保证load1读取的数据已读取完毕
inline void OrderAccess::loadstore()  { compiler_barrier(); }
//volatile使用的 storeload屏障 store1;storeload;load2
//在load2及后续读取操作之前 保证 store1的写对所有处理器可见
inline void OrderAccess::storeload()  { fence();            }
// windows系统中有 acquire和 release
//acquire
inline void OrderAccess::acquire()    { compiler_barrier(); }
inline void OrderAccess::release()    { compiler_barrier(); }

inline void OrderAccess::fence() {
   // always use locked addl since mfence is sometimes expensive
   //lock;指令锁总线
#ifdef AMD64
  __asm__ volatile ("lock; addl $0,0(%%rsp)" : : : "cc", "memory");
#else
  __asm__ volatile ("lock; addl $0,0(%%esp)" : : : "cc", "memory");
#endif
  compiler_barrier();
}

inline void OrderAccess::cross_modify_fence_impl() {
  int idx = 0;
#ifdef AMD64
  __asm__ volatile ("cpuid " : "+a" (idx) : : "ebx", "ecx", "edx", "memory");
#else
  // On some x86 systems EBX is a reserved register that cannot be
  // clobbered, so we must protect it around the CPUID.
  __asm__ volatile ("xchg %%esi, %%ebx; cpuid; xchg %%esi, %%ebx " : "+a" (idx) : : "esi", "ecx", "edx", "memory");
#endif
}

#endif // OS_CPU_LINUX_X86_ORDERACCESS_LINUX_X86_HPP
