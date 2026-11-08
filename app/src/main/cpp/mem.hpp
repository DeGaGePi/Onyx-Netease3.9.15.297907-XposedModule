// ══════════════════════════════════════════════════════════════════════════
//  mem.hpp —— 进程内存读写 / 模块基址 / 可读可写区间（header only）
//
//  ★★ 2.3.2 起的地基换掉了（上一版崩在这，别改回去）：
//
//  旧做法 = 「查 /proc/self/maps 快照 → 认为可读 → memcpy 裸读」。
//  问题：快照最多陈旧 2 秒（`time(nullptr)` 秒级缓存），而游戏进程里
//        ART GC 与 Houdini 翻译缓存在不停 mmap/munmap。快照说「可写」的页
//        可能早就没了 → memcpy 直接 SIGSEGV。
//        实机 tombstone：signal 11 SEGV_MAPERR，fault addr 正好是某个段的起始页。
//
//  新做法 = process_vm_readv / process_vm_writev **读自己进程**。
//        内核会做完整的访问检查，读不到就返回 EFAULT(-1)，**绝不产生信号**。
//        同线程组（same_thread_group）在内核里直接放行，不需要任何权限。
//        再挂一层 SIGSEGV/SIGBUS + sigsetjmp 兜底，防止个别内核/ROM 上
//        process_vm_* 不可用（那种情况自动退回保护读）。
//
//  → 任何一次野指针都只会让函数返回 0/false，绝不会把游戏带崩。
// ══════════════════════════════════════════════════════════════════════════
#pragma once

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <setjmp.h>
#include <signal.h>
#include <string>
#include <unistd.h>
#include <vector>

#include <link.h>
#include <sys/types.h>
#include <sys/uio.h>

#include <android/log.h>

#define ONYX_TAG "Onyx"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  ONYX_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  ONYX_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, ONYX_TAG, __VA_ARGS__)

namespace mem {

struct Range {
    uintptr_t s;
    uintptr_t e;   // 不含
    bool w;
    bool x;
};

inline uint64_t now_ms() {
    struct timespec ts;
    ::clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000ull + (uint64_t)ts.tv_nsec / 1000000ull;
}

// ═════════════════════ safe_copy / safe_put ═════════════════════
//
//  这是整个模块唯一允许碰内存的入口。别绕过它直接解引用。

namespace detail {

// ★★★ 2.4.14：**信号路径里禁止出现 thread_local**。
//
//   实机崩溃（2026-10-11 09:30:09，网易 crashhunter 抓到、无 /data/tombstones 记录）：
//       signal 11 SIGSEGV / SEGV_ACCERR
//       #00 libonyxmc.so (__emutls_get_address+44)
//       #01 guarded_copy   mem.hpp:129   ← sigsetjmp(tl_jb, 0)
//       #02 safe_copy_n/safe_copy/rd<u64> mem.hpp:211/220/366
//       #03 read_actor_pos_ex  onyxmc.cpp:756   (hot=false)
//       #04 is_actor_ex        onyxmc.cpp:839
//       #05 actor_vt_learn     onyxmc.cpp:1154
//       #06 scan_once / scan_loop
//
//   机制：API 24 的 aarch64 用 **emutls** 实现 thread_local（所以我们的 .so 里
//   有 `__emutls_get_address`）。`siglongjmp(tl_jb, 1)` 要先对 `tl_jb` 取址 ——
//   而 emutls 的取址函数在**首次/惰性**分配路径上会调用分配器。在「刚 SIGSEGV、
//   且 SIGSEGV 正被 handler 屏蔽（2.4.8 去掉了 SA_NODEFER）」的状态下再进这一步，
//   就再崩一次 → 第二次故障直接致命，整进程死。
//
//   ⇒ 改成**静态 jmp_buf + 静态 owner 标记**：handler 全程只碰静态内存，零 TLS。
//   owner 用 tid 标记，保证同一时刻只有一个线程在保护窗口里（并发的 hot 读
//   直接失败而不是串用同一个 jmp_buf）。
inline std::atomic<unsigned>            g_gOwner{0};      // 0=空闲，否则=窗口内线程的 tid
inline sigjmp_buf                       g_gJb;
// ★ 扫描线程的 tid —— 它**永远不允许进入信号窗口**（见 guarded_copy 开头）。
inline std::atomic<unsigned>            g_scanTid{0};
inline std::atomic<bool>                g_sigInstalled{false};
inline struct sigaction                 g_oldSegv{};
inline struct sigaction                 g_oldBus{};
inline std::atomic<int>                 g_vmState{0};   // 0=未知 1=可用 -1=不可用

// ★★ 2.4.8：hot 路径（进程内 memcpy + SIGSEGV 兜底）的信号熔断。
//   正常使用下 hot 读几乎不该触发信号（地址都是从已知对象顺出来的）；
//   一旦计数涨起来，说明有我们没预料到的悬垂 —— 继续跑只会把 Houdini 翻译锁留死。
inline std::atomic<unsigned>            g_hotFaults{0};
inline std::atomic<bool>                g_hotDisabled{false};
inline constexpr unsigned               HOT_FAULT_LIMIT = 3000;

inline void seg_handler(int sig, siginfo_t* si, void* uc) {
    // ★ 只在「本线程正持有保护窗口」时接管。全程只读静态内存 —— 不碰任何
    //   thread_local，否则就是在信号上下文里再走一遍 emutls（见文件顶部注释）。
    const unsigned me = (unsigned)::gettid();
    if (g_gOwner.load(std::memory_order_relaxed) == me) {
        g_gOwner.store(0, std::memory_order_relaxed);
        // ★★ 2.4.8 熔断：在 Houdini 下，每一次从 memcpy 里跳出来的信号都有概率
        //    把翻译层的锁留在半路（siglongjmp 绕过了它的解锁路径）。次数一多，
        //    迟早有线程卡在翻译锁上 → 游戏卡死被杀（"注入后过一会闪退"）。
        //    所以这里计数：超过阈值就**永久关掉 hot 路径**（guarded_copy 直接失败），
        //    调用方拿到 0 → 相关的 safe 路径接管。慢一点，但不会再崩。
        //    状态串里的 hf= 就是这个计数。
        if (g_hotFaults.fetch_add(1, std::memory_order_relaxed) + 1 >= HOT_FAULT_LIMIT) {
            g_hotDisabled.store(true, std::memory_order_relaxed);
        }
        ::siglongjmp(g_gJb, 1);
    }
    // 不在窗口内 → 转交给游戏/系统原本的 handler，别吞掉它们的崩溃处理
    const struct sigaction* o = (sig == SIGBUS) ? &g_oldBus : &g_oldSegv;
    if ((o->sa_flags & SA_SIGINFO) && o->sa_sigaction != nullptr) {
        o->sa_sigaction(sig, si, uc);
        return;
    }
    if (o->sa_handler != SIG_DFL && o->sa_handler != SIG_IGN && o->sa_handler != nullptr) {
        o->sa_handler(sig);
        return;
    }
    ::signal(sig, SIG_DFL);
    ::raise(sig);
}

inline void install_sig_guard() {
    if (g_sigInstalled.exchange(true)) return;
    struct sigaction sa{};
    sa.sa_sigaction = seg_handler;
    // ★★ 2.4.8：去掉 SA_NODEFER。
    //   带上它 = 信号处理期间**不阻塞自身信号** → handler 内只要再触发一次 SIGSEGV
    //   （siglongjmp 目标失效、或恢复后的那条指令立刻再次 fault），就会**递归进
    //   handler**，栈一路吃穿 → 直接崩。默认（处理期间阻塞自身）才是安全的那一侧。
    sa.sa_flags     = SA_SIGINFO;
    ::sigemptyset(&sa.sa_mask);
    ::sigaction(SIGSEGV, &sa, &g_oldSegv);
    ::sigaction(SIGBUS,  &sa, &g_oldBus);
}

/** 保护读：窗内崩了就 siglongjmp 回来当失败。开销比 process_vm_readv 还低，
 *  但会有 TOCTOU（窗口内被 munmap 才算失败），只当兜底用。 */
inline bool guarded_copy(void* dst, uintptr_t src, size_t n) {
    install_sig_guard();
    // ★ 2.4.8 熔断已生效 → 直接报告失败，上层逻辑自然退化；绝不再往野地址 memcpy。
    if (g_hotDisabled.load(std::memory_order_relaxed)) return false;
    const unsigned me = (unsigned)::gettid();
    // ★★★ 2.4.14：**扫描线程永不进入信号窗口**。
    //   scan_loop 全程只跑 safe 读（process_vm_readv）。任何一次在这里产生的信号
    //   都可能把 Houdini 翻译锁留死（2.4.7 卡死根因），或踩到 emutls 的二次故障
    //   （2.4.14 定位到的致命崩溃）。这一句是**不变量**，不是优化。
    if (g_scanTid.load(std::memory_order_relaxed) == me) return false;
    // 同一时刻只允许一个线程在窗口里；并发时后到的直接失败（hot 读失败可接受，
    // 串用同一个 jmp_buf 则会跳错栈）。
    unsigned expect = 0;
    if (!g_gOwner.compare_exchange_strong(expect, me,
                                          std::memory_order_acq_rel,
                                          std::memory_order_relaxed)) {
        return false;
    }
    if (::sigsetjmp(g_gJb, 0) == 0) {
        ::memcpy(dst, (const void*)src, n);
        g_gOwner.store(0, std::memory_order_release);
        return true;
    }
    g_gOwner.store(0, std::memory_order_release);
    return false;
}

/** 保护写：与 guarded_copy 同构（窗内崩了就 siglongjmp 当失败） */
inline bool guarded_write(uintptr_t dst, const void* src, size_t n) {
    install_sig_guard();
    if (g_hotDisabled.load(std::memory_order_relaxed)) return false;
    const unsigned me = (unsigned)::gettid();
    if (g_scanTid.load(std::memory_order_relaxed) == me) return false;
    unsigned expect = 0;
    if (!g_gOwner.compare_exchange_strong(expect, me,
                                          std::memory_order_acq_rel,
                                          std::memory_order_relaxed)) {
        return false;
    }
    if (::sigsetjmp(g_gJb, 0) == 0) {
        ::memcpy((void*)dst, src, n);
        g_gOwner.store(0, std::memory_order_release);
        return true;
    }
    g_gOwner.store(0, std::memory_order_release);
    return false;
}

/**
 * 返回**实际拷到的字节数**；< 0 = 这个 syscall 在本机不可用。
 *
 * ★★ 只有 EPERM / ENOSYS / EINVAL / ESRCH 才判「syscall 不可用」。
 *    EFAULT 与「部分读」都只说明这一段地址有洞 —— **绝不能让它们把整条读路径
 *    永久关掉**。上一版就是这么坏的（实机症状：hook 全装上、功能全失效、零报错）：
 *
 *      process_vm_readv 在「缓冲尾跨到未映射页」时返回**部分计数**，并且
 *      **不设置 errno**；代码却拿残留的 errno 去判：
 *          if (g_vmState.load() == 0) g_vmState.store(errno == EFAULT ? 1 : -1);
 *      而在这个调用之前，errno 里常常正好残留着上一次 `rd()` 失败的 EFAULT
 *      → g_vmState 被置成 1（=「syscall 正常，就是读不到」）
 *      → 此后 safe_copy 每次都 `return false`，连 guarded_copy 兜底都不走
 *      → 全模块所有内存读取全灭。
 *
 *      修法：进 syscall 前 `errno = 0`；用返回值的**正负**判断语义，不再看残留 errno。
 */
inline ssize_t vm_read_n(void* dst, uintptr_t src, size_t n) {
    struct iovec l{ dst, n };
    struct iovec r{ (void*)src, n };
    errno = 0;
    const ssize_t got = ::process_vm_readv(::getpid(), &l, 1, &r, 1, 0);
    if (got >= 0) {
        if (g_vmState.load() == 0) g_vmState.store(1);   // 能用，记住
        return got;                                      // 可能是部分读
    }
    const int e = errno;
    if (e == EPERM || e == ENOSYS || e == EINVAL || e == ESRCH) {
        if (g_vmState.load() == 0) g_vmState.store(-1);
        return -1;
    }
    if (g_vmState.load() == 0) g_vmState.store(1);
    return 0;                                            // EFAULT：地址坏了，不是 syscall 的错
}

inline bool vm_read(void* dst, uintptr_t src, size_t n) {
    return vm_read_n(dst, src, n) == (ssize_t)n;
}

inline bool vm_write(const void* src, uintptr_t dst, size_t n) {
    struct iovec l{ (void*)src, n };
    struct iovec r{ (void*)dst, n };
    const ssize_t got = ::process_vm_writev(::getpid(), &l, 1, &r, 1, 0);
    if (got == (ssize_t)n) return true;
    if (got < 0 && (errno == EPERM || errno == ENOSYS || errno == EINVAL)) return false;
    return guarded_write(dst, src, n);      // 兜底：进程内写自己的内存
}

}  // namespace detail

/** ★★★ 2.4.14：把**当前线程**标记为「扫描线程」—— 它永远不允许进入信号窗口。
 *  必须在 scan_loop 的**第一行**调用（早于该线程的任何一次读）。
 *  之后再从该线程调用 guarded_copy / safe_copy_hot / rd_hot，一律直接失败返回，
 *  而不会产生任何信号 —— 这条不变量就是 2.4.14 那次实机致命崩溃的修复。 */
inline void mark_scan_thread() {
    detail::g_scanTid.store((unsigned)::gettid(), std::memory_order_release);
}

/**
 * 安全拷贝 —— **返回真正拷到的字节数**（0 = 一个字节都没读到）。绝不产生信号。
 * 扫描器用它拿到「部分成功」的字节数继续扫，而不是整块丢弃。
 */
inline size_t safe_copy_n(void* dst, uintptr_t src, size_t n) {
    if (n == 0) return 0;
    if (src < 0x1000) return 0;
    if (detail::g_vmState.load() >= 0) {
        const ssize_t got = detail::vm_read_n(dst, src, n);
        if (got == (ssize_t)n) return n;
        if (got > 0) return (size_t)got;            // ★ 部分读：能扫多少算多少
        // 整块不可读 / syscall 不可用
    }
    // ★★★ 2.4.14：**冷路径到此为止 —— 绝不回落到 guarded_copy（信号兜底）**。
    //
    //   老代码是： if (n > 4096) return 0;  return guarded_copy(...) ? n : 0;
    //
    //   那条兜底在 Houdini 上是**纯风险、零收益**：
    //     · 地址坏 → guarded_copy 里的 memcpy 必然 fault → siglongjmp 从信号里跳出
    //       （Houdini 翻译锁留在半路），而且恢复路径读 thread_local 时会走
    //       __emutls_get_address —— 实机就是这么整进程死掉的（见文件顶部注释）。
    //     · syscall 不可用（g_vmState < 0）时才有点用，但本机实测 vm=1，
    //       process_vm_readv 一直是好的；真不可用时调用方本来就该走 hot 路径。
    //
    //   所以：冷路径 = process_vm_readv 读得到就给，读不到就返回 0。
    //   零信号、零抖动，扫描线程与堆扫路径从此不可能产生任何信号。
    return 0;
}

inline bool safe_copy(void* dst, uintptr_t src, size_t n) {
    return safe_copy_n(dst, src, n) == n;
}

// ═══════════════════ ★★ 主线程热路径：进程内 memcpy，不走 syscall ═══════════════════
//
//  为什么必须把「主线程读」和「堆扫读」分开 —— 实机 ANR 的根因就是没分：
//
//    在 MuMu / Houdini（x86_64 上跑 ARM64 guest）下，guest 的 `syscall` 指令要被
//    翻译层 trap + 转译，单次 process_vm_readv 实测 **~145 us**（原生 Linux 是 1–2 us，
//    差两个数量级）。证据：实机日志 scan 一轮读 384MB / 6144 次 syscall 用了 891ms。
//
//    于是「在主线程里循环调用 rd<>」等于自杀：
//    nTick 里原来的 find_gamemode 要跑 486 轮循环 × 最多 3 次 rd ≈ 1000+ 次 syscall
//    ≈ 150 ms，每 300 ms 来一次 → 主线程一半时间卡在 syscall 里
//    → 系统弹「应用无响应」。
//
//  guarded_copy 是**进程内 memcpy**：Houdini 早已把这几个字节的 guest 代码翻译/缓存好，
//  代价是纳秒级，比 process_vm_* 快三个数量级。代价是 TOCTOU（窗口内被 munmap 才失败），
//  所以分工是：
//    · **主线程读「已知合法对象的字段」**（Actor/Player/GameMode 的偏移）→ 用 hot 版。
//      这些对象是游戏自己持有的，生命周期内不会在几纳秒里消失；且有 SIGSEGV 兜底。
//    · **堆扫 / 从影子缓冲里"猜"出来的地址** → 仍然用 safe_copy（process_vm_readv，
//      内核做完整访问检查，读不到返回 EFAULT，**零信号风险**）。快照会过期，这里必须安全。
//
//  → 判据：**这个地址是从别的对象里顺出来的，还是从堆里猜出来的？** 顺出来的用 hot。

/**
 * 保护读，返回实际字节数（0 = 不可读）。不产生信号。
 *
 * ★★ 2.4.2：整块失败时**逐页降级**，返回「从头开始连续可读的前缀」字节数。
 *
 *   上一版是 `guarded_copy(...) ? n : 0` —— guarded_copy 内部一句 memcpy，
 *   **全有或全无**：只要 n 字节里任何一页不可读，整个 memcpy 抛 SIGSEGV、
 *   siglongjmp 回来、返回 0。
 *
 *   而调用方（find_gamemode / player_inventory）一次要读 Player 的 body
 *   0x1300 = 4864 字节 —— **必然跨页**（页 4096）。Player 对象住在堆尾、
 *   后面紧跟守护页时，整块读直接返回 0，调用方那个 `if (gotBody == sizeof(body))`
 *   分支立刻 break，于是 gamemode 永远探不到 →
 *   KillAura / Scaffold / AutoBreak / Nuker / Game Mode 全都在第一道闸门 return。
 *   （用户症状：自动搭路和杀戮开了没反应。）
 *
 *   现在：先试整块（快路径，一页内直接成功）；失败则按页推进，遇到第一个
 *   不可读页就停 —— 调用方拿到「前缀」，能扫多少算多少。
 */
inline size_t safe_copy_hot(void* dst, uintptr_t src, size_t n) {
    if (n == 0) return 0;
    if (src < 0x1000) return 0;
    if (detail::guarded_copy(dst, src, n)) return n;

    // 整块失败 → 逐页兜，返回可读前缀长度
    uint8_t* d = reinterpret_cast<uint8_t*>(dst);
    constexpr size_t PG = 4096;
    size_t done = 0;
    while (done < n) {
        const size_t step = std::min(PG - ((src + done) & (PG - 1)), n - done);
        if (!detail::guarded_copy(d + done, src + done, step)) break;
        done += step;
    }
    return done;
}

inline bool safe_copy_pin(void* dst, uintptr_t src, size_t n) {
    return safe_copy_hot(dst, src, n) == n;
}

/** 主线程热路径的 rd —— 进程内 memcpy，纳秒级 */
template <typename T>
inline T rd_hot(uintptr_t a) {
    T v{};
    if (!safe_copy_hot(&v, a, sizeof(T))) v = T{};
    return v;
}

inline bool safe_put(const void* src, uintptr_t dst, size_t n) {
    if (n == 0) return true;
    if (dst < 0x1000) return false;
    return detail::vm_write(src, dst, n);
}

// ───────────────────────── 区间查询（仅用于「猜大小」，不再作为安全依据）─────────────────────────

inline std::vector<Range>& ranges_raw(bool force = false) {
    static std::vector<Range> v;
    static uint64_t last = 0;
    const uint64_t now = now_ms();
    if (!force && !v.empty() && now - last < 250) return v;

    std::vector<Range> out;
    FILE* f = ::fopen("/proc/self/maps", "r");
    if (!f) return v;
    char line[512];
    while (::fgets(line, sizeof(line), f)) {
        uintptr_t a = 0, b = 0;
        char perms[8] = {0};
        if (::sscanf(line, "%lx-%lx %7s", (unsigned long*)&a, (unsigned long*)&b, perms) != 3) continue;
        if (b <= a) continue;
        Range r;
        r.s = a;
        r.e = b;
        r.w = (perms[1] == 'w');
        r.x = (perms[2] == 'x');
        out.push_back(r);
    }
    ::fclose(f);
    v.swap(out);
    last = now;
    return v;
}

inline bool readable(uintptr_t a, size_t n) {
    if (a < 0x1000 || n == 0 || n > (1u << 28)) return false;
    const uintptr_t end = a + n;
    if (end < a) return false;
    for (const auto& r : ranges_raw()) {
        if (a >= r.s && end <= r.e) return true;
    }
    return false;
}

inline std::vector<Range> writable_ranges() {
    std::vector<Range> data, exec;
    for (const auto& r : ranges_raw(true)) {
        if (!r.w) continue;                            // 无写权限的段里没有对象
        const uintptr_t sz = r.e - r.s;
        if (sz < (1u << 16)) continue;                 // 小于 64KB 的零碎页不是堆
        // ★ 不再设「大于 N GB 就跳过」的上限。上一版有 `sz > 12GB` 的过滤，
        //   而 Houdini（MuMu 上跑 ARM64 的翻译层）会一次性保留**超大虚拟区间**，
        //   guest 的对象就住在里面 —— 一过滤就等于整个堆都没扫，命中恒为 0。
        //   现在超大的区间也照扫，靠 scan_once 的「单轮预算 + 轮转游标」分摊。
        if (r.x) exec.push_back(r); else data.push_back(r);
    }
    // 可执行页（Houdini 翻译缓存 / JIT）排到最后：那些是代码，不会有对象，
    // 但万一 guest 数据真的落在 RWX 区里，也还能兜到 —— 只是优先级最低。
    data.insert(data.end(), exec.begin(), exec.end());
    return data;
}

/** 当前读路径状态：0=未知 1=process_vm_* 可用 -1=不可用（走保护读兜底） */
inline int vm_state() { return detail::g_vmState.load(); }

// ───────────────────────── 读写 ─────────────────────────

template <typename T>
inline T rd(uintptr_t a) {
    T v{};
    if (!safe_copy(&v, a, sizeof(T))) v = T{};
    return v;
}

/** 保留旧名字（调用方语义上相信这块内存）；现在它和 rd 一样安全 */
template <typename T>
inline T rd_fast(uintptr_t a) {
    return rd<T>(a);
}

template <typename T>
inline bool wr(uintptr_t a, T v) {
    if (!readable(a, sizeof(T))) return false;       // 先粗筛，省一次 syscall
    return safe_put(&v, a, sizeof(T));
}

/** 按块拷进影子缓冲：扫描器专用，一次 syscall 拿 64KB，代价极低 */
inline bool safe_block(void* dst, uintptr_t src, size_t n) {
    return safe_copy(dst, src, n);
}

inline bool sane_float(float f) {
    return std::isfinite(f) && f > -1.0e7f && f < 1.0e7f;
}

inline bool sane_ptr(uintptr_t p) {
    return p >= 0x1000 && p < (uintptr_t)0x00007FFFFFFF0000ull;
}

// ───────────────────────── 模块基址 ─────────────────────────

inline int phdr_cb(struct dl_phdr_info* info, size_t, void* data) {
    if (info == nullptr || info->dlpi_name == nullptr || data == nullptr) return 0;
    const char* n = info->dlpi_name;
    if (::strstr(n, "libminecraftpe.so") == nullptr) return 0;
    uintptr_t lo = (uintptr_t)-1, hi = 0;
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr)* ph = &info->dlpi_phdr[i];
        if (ph->p_type != PT_LOAD) continue;
        const uintptr_t s = (uintptr_t)info->dlpi_addr + ph->p_vaddr;
        const uintptr_t e = s + ph->p_memsz;
        if (s < lo) lo = s;
        if (e > hi) hi = e;
    }
    uintptr_t* o = (uintptr_t*)data;
    o[0] = lo;
    o[1] = hi;
    return 1;   // 找到就停
}

struct Base {
    uintptr_t lo = 0;
    uintptr_t hi = 0;
};

/** 解析 libminecraftpe.so 的加载区间；找不到返回 {0,0} */
inline Base game_module() {
    uintptr_t out[2] = {0, 0};
    ::dl_iterate_phdr(phdr_cb, out);
    Base b;
    b.lo = out[0];
    b.hi = out[1];
    return b;
}

}  // namespace mem
