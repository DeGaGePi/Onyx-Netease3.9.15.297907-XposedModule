


















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
    uintptr_t e;   
    bool w;
    bool x;
};

inline uint64_t now_ms() {
    struct timespec ts;
    ::clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000ull + (uint64_t)ts.tv_nsec / 1000000ull;
}





namespace detail {






















inline std::atomic<unsigned>            g_gOwner{0};      
inline sigjmp_buf                       g_gJb;

inline std::atomic<unsigned>            g_scanTid{0};
inline std::atomic<bool>                g_sigInstalled{false};
inline struct sigaction                 g_oldSegv{};
inline struct sigaction                 g_oldBus{};
inline std::atomic<int>                 g_vmState{0};   




inline std::atomic<unsigned>            g_hotFaults{0};
inline std::atomic<bool>                g_hotDisabled{false};
inline constexpr unsigned               HOT_FAULT_LIMIT = 3000;

inline void seg_handler(int sig, siginfo_t* si, void* uc) {
    
    
    const unsigned me = (unsigned)::gettid();
    if (g_gOwner.load(std::memory_order_relaxed) == me) {
        g_gOwner.store(0, std::memory_order_relaxed);
        
        
        
        
        
        
        if (g_hotFaults.fetch_add(1, std::memory_order_relaxed) + 1 >= HOT_FAULT_LIMIT) {
            g_hotDisabled.store(true, std::memory_order_relaxed);
        }
        ::siglongjmp(g_gJb, 1);
    }
    
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
    
    
    
    
    sa.sa_flags     = SA_SIGINFO;
    ::sigemptyset(&sa.sa_mask);
    ::sigaction(SIGSEGV, &sa, &g_oldSegv);
    ::sigaction(SIGBUS,  &sa, &g_oldBus);
}

 

inline bool guarded_copy(void* dst, uintptr_t src, size_t n) {
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
        ::memcpy(dst, (const void*)src, n);
        g_gOwner.store(0, std::memory_order_release);
        return true;
    }
    g_gOwner.store(0, std::memory_order_release);
    return false;
}

 
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

 
















inline ssize_t vm_read_n(void* dst, uintptr_t src, size_t n) {
    struct iovec l{ dst, n };
    struct iovec r{ (void*)src, n };
    errno = 0;
    const ssize_t got = ::process_vm_readv(::getpid(), &l, 1, &r, 1, 0);
    if (got >= 0) {
        if (g_vmState.load() == 0) g_vmState.store(1);   
        return got;                                      
    }
    const int e = errno;
    if (e == EPERM || e == ENOSYS || e == EINVAL || e == ESRCH) {
        if (g_vmState.load() == 0) g_vmState.store(-1);
        return -1;
    }
    if (g_vmState.load() == 0) g_vmState.store(1);
    return 0;                                            
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
    return guarded_write(dst, src, n);      
}

}  

 



inline void mark_scan_thread() {
    detail::g_scanTid.store((unsigned)::gettid(), std::memory_order_release);
}

 



inline size_t safe_copy_n(void* dst, uintptr_t src, size_t n) {
    if (n == 0) return 0;
    if (src < 0x1000) return 0;
    if (detail::g_vmState.load() >= 0) {
        const ssize_t got = detail::vm_read_n(dst, src, n);
        if (got == (ssize_t)n) return n;
        if (got > 0) return (size_t)got;            
        
    }
    
    
    
    
    
    
    
    
    
    
    
    
    
    return 0;
}

inline bool safe_copy(void* dst, uintptr_t src, size_t n) {
    return safe_copy_n(dst, src, n) == n;
}
























 


















inline size_t safe_copy_hot(void* dst, uintptr_t src, size_t n) {
    if (n == 0) return 0;
    if (src < 0x1000) return 0;
    if (detail::guarded_copy(dst, src, n)) return n;

    
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
        if (!r.w) continue;                            
        const uintptr_t sz = r.e - r.s;
        if (sz < (1u << 16)) continue;                 
        
        
        
        
        if (r.x) exec.push_back(r); else data.push_back(r);
    }
    
    
    data.insert(data.end(), exec.begin(), exec.end());
    return data;
}

 
inline int vm_state() { return detail::g_vmState.load(); }



template <typename T>
inline T rd(uintptr_t a) {
    T v{};
    if (!safe_copy(&v, a, sizeof(T))) v = T{};
    return v;
}

 
template <typename T>
inline T rd_fast(uintptr_t a) {
    return rd<T>(a);
}

template <typename T>
inline bool wr(uintptr_t a, T v) {
    if (!readable(a, sizeof(T))) return false;       
    return safe_put(&v, a, sizeof(T));
}

 
inline bool safe_block(void* dst, uintptr_t src, size_t n) {
    return safe_copy(dst, src, n);
}

inline bool sane_float(float f) {
    return std::isfinite(f) && f > -1.0e7f && f < 1.0e7f;
}

inline bool sane_ptr(uintptr_t p) {
    return p >= 0x1000 && p < (uintptr_t)0x00007FFFFFFF0000ull;
}



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
    return 1;   
}

struct Base {
    uintptr_t lo = 0;
    uintptr_t hi = 0;
};

 
inline Base game_module() {
    uintptr_t out[2] = {0, 0};
    ::dl_iterate_phdr(phdr_cb, out);
    Base b;
    b.lo = out[0];
    b.hi = out[1];
    return b;
}

}  
