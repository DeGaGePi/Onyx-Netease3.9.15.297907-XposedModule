












#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <algorithm>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>
#include <ctime>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/resource.h>   

#include "mem.hpp"
#include "offsets.hpp"

using namespace mem;



static std::atomic<uintptr_t> g_libBase{0};
static std::atomic<uintptr_t> g_libEnd{0};
static std::atomic<uintptr_t> g_player{0};
static std::atomic<uintptr_t> g_gamemode{0};
static std::atomic<uintptr_t> g_selfCtx{0};   
static std::atomic<bool>      g_selfSeen{false};  

static std::atomic<int>  g_scans{0};
static std::atomic<int>  g_scanMs{-1};
static std::atomic<int>  g_rejected{0};
static std::atomic<int>  g_entCount{0};
static std::atomic<unsigned> g_scanKB{0};     

static std::atomic<bool> g_kaura{false};
static std::atomic<bool> g_scaffold{false};
static std::atomic<double> g_kauraRange{3.5};
static std::atomic<int>  g_kauraMode{0};      
static std::atomic<int>  g_kauraDelay{100};   



static std::atomic<int>  g_kauraTarget{2};

static std::atomic<int>  g_scaffoldDelay{100};  



static std::atomic<unsigned> g_posOk{0};
static std::atomic<unsigned> g_posFail{0};
static std::atomic<unsigned> g_kaNoGm{0};
static std::atomic<unsigned> g_scNoGm{0};
static std::atomic<unsigned> g_builds{0};       
static std::atomic<unsigned> g_buildOk{0};      
static std::atomic<unsigned> g_bpFallback{0};   

static std::atomic<unsigned> g_attacks{0};
static std::atomic<unsigned> g_mainTicks{0};
static std::atomic<int>  g_seen{0};
static std::atomic<bool> g_threadUp{false};


static std::atomic<bool>   g_esp{false};
static std::atomic<double> g_espRange{32.0};
static std::atomic<int>    g_espMode{0};        
static std::atomic<bool>   g_espTeam{false};
static std::atomic<unsigned> g_espDrawn{0};
static std::atomic<int>    g_espFail{0};        

struct Ent {
    uintptr_t p;
    float x, y, z;
    bool  isPlayer;      
    int   src;           
};

 
struct BPos {
    int32_t x, y, z;
};

static std::mutex g_entMtx;
static std::vector<Ent> g_ents;




static std::atomic<bool>     g_fly{false};
static std::atomic<unsigned> g_flyWrites{0};
static std::atomic<int>      g_flyRead{-1};     


static std::atomic<bool>     g_autojump{false};
static std::atomic<int>      g_jumpDelay{300};
static std::atomic<unsigned> g_jumps{0};



static std::atomic<bool>     g_tpOn{false};
static std::atomic<int>      g_tpMode{0};       
static std::atomic<double>   g_tpDist{4.0};
static std::atomic<unsigned> g_tpCount{0};   


static std::atomic<bool>     g_hitbox{false};
static std::atomic<double>   g_hbW{0.9};
static std::atomic<double>   g_hbH{2.2};
static std::atomic<unsigned> g_hbWrites{0};





static std::atomic<int>      g_hbTarget{0};


static std::atomic<bool>     g_autobreak{false};
static std::atomic<bool>     g_nuker{false};
static std::atomic<int>      g_nukerRange{3};
static std::atomic<unsigned> g_breaks{0};
static std::atomic<unsigned> g_breakOk{0};
static int                   g_nukerCursor = 0;   


static std::atomic<int>      g_gametype{-1};      



static std::atomic<bool>     g_gamemodeOn{false};
static std::atomic<unsigned> g_gmWrites{0};


static std::atomic<bool>     g_chest{false};
static std::atomic<int>      g_chestRange{16};
static std::atomic<unsigned> g_chestHits{0};
static std::atomic<int>      g_chestProgress{0};
static std::mutex            g_chestMtx;
static std::vector<BPos>     g_chestPos;


static std::atomic<int>      g_dimPlayers{0};
static std::atomic<int>      g_dimFails{0};
static std::atomic<unsigned> g_dimSweeps{0};

















static std::atomic<uint64_t> g_nowMs{0};

namespace hk {

struct Entry {
    uintptr_t target;
    void*     tramp;
    int       insns;
};

constexpr int MAX_HOOKS = 16;
static Entry g_entries[MAX_HOOKS];
static int   g_count = 0;
static std::atomic<int> g_ok{0};
static std::atomic<int> g_fail{0};

 
static bool pcrel(uint32_t in) {
    if ((in & 0xFC000000u) == 0x14000000u) return true;   
    if ((in & 0xFC000000u) == 0x94000000u) return true;   
    if ((in & 0xFF000010u) == 0x54000000u) return true;   
    if ((in & 0x7E000000u) == 0x34000000u) return true;   
    if ((in & 0x7E000000u) == 0x36000000u) return true;   
    if ((in & 0x1F000000u) == 0x10000000u) return true;   
    if ((in & 0x3B000000u) == 0x18000000u) return true;   
    if ((in & 0x3B000000u) == 0x98000000u) return true;   
    if ((in & 0xFF000000u) == 0xD8000000u) return true;   
    return false;
}

 
static inline int64_t sx(uint64_t v, int bits) {
    const uint64_t m = 1ull << (bits - 1);
    return (int64_t)((v ^ m) - m);
}

static constexpr uint32_t NOP_I    = 0xD503201Fu;
static constexpr uint32_t LDRX16_8 = 0x58000050u;   
static constexpr uint32_t BRX16    = 0xD61F0200u;   
static constexpr uint32_t BLRX16   = 0xD63F0200u;   
static constexpr int      STUB     = 4;             
static constexpr int      MAX_OUT  = 26;            

 






















static int relocate(const uint32_t* code, uintptr_t target, uint32_t* out) {
    int n = 0;
    for (int i = 0; i < 4; ++i) {
        const uint32_t  in = code[i];
        const uintptr_t s  = target + (uintptr_t)i * 4;

        if (!pcrel(in)) {
            if (n + 1 > MAX_OUT) return 0;
            out[n++] = in;
            continue;
        }

        uint32_t  inv = 0;
        uintptr_t to  = 0;
        bool      cond = false;

        if ((in & 0xFF000010u) == 0x54000000u) {              
            to   = (uintptr_t)((int64_t)s + sx((in >> 5) & 0x7FFFFull, 19) * 4);
            inv  = 0x54000000u | (5u << 5) | ((in & 0xFu) ^ 1u);
            cond = true;
        } else if ((in & 0x7E000000u) == 0x34000000u) {        
            to   = (uintptr_t)((int64_t)s + sx((in >> 5) & 0x7FFFFull, 19) * 4);
            inv  = ((in ^ 0x01000000u) & ~0x00FFFFE0u) | (5u << 5);
            cond = true;
        } else if ((in & 0x7E000000u) == 0x36000000u) {        
            to   = (uintptr_t)((int64_t)s + sx((in >> 5) & 0x3FFFull, 14) * 4);
            inv  = ((in ^ 0x01000000u) & ~0x0007FFE0u) | (5u << 5);
            cond = true;
        } else if ((in & 0xFC000000u) == 0x14000000u ||        
                   (in & 0xFC000000u) == 0x94000000u) {        
            to = (uintptr_t)((int64_t)s + sx(in & 0x03FFFFFFull, 26) * 4);
        } else {
            return 0;                                          
        }

        const bool isBl = (in & 0xFC000000u) == 0x94000000u;

        if (cond) {
            
            
            if ((n & 1) == 0) {
                if (n + 1 > MAX_OUT) return 0;
                out[n++] = NOP_I;
            }
            if (n + 1 + STUB > MAX_OUT) return 0;
            out[n++] = inv;                                    
        } else if (n & 1) {
            if (n + 1 > MAX_OUT) return 0;
            out[n++] = NOP_I;                                  
        }
        if (n + STUB > MAX_OUT) return 0;

        const uint64_t q = (uint64_t)to;
        out[n++] = LDRX16_8;
        out[n++] = isBl ? BLRX16 : BRX16;
        out[n++] = (uint32_t)(q & 0xFFFFFFFFull);
        out[n++] = (uint32_t)(q >> 32);
    }
    return n;
}

 
static void* exec_mem(size_t sz, bool& rwx) {
    void* m = ::mmap(nullptr, sz, PROT_READ | PROT_WRITE | PROT_EXEC,
                     MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (m != MAP_FAILED) { rwx = true; return m; }
    m = ::mmap(nullptr, sz, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    rwx = false;
    return (m == MAP_FAILED) ? nullptr : m;
}

 











static void* install(uintptr_t target, void* replacement) {
    if (!target || !replacement) return nullptr;
    const uintptr_t base = g_libBase.load();
    const uintptr_t end  = g_libEnd.load();
    if (!base || target < base || target + 32 > end) return nullptr;
    if (g_count >= MAX_HOOKS) return nullptr;
    if (!mem::readable(target, 64)) { ++g_fail; return nullptr; }

    const uint32_t* code = reinterpret_cast<const uint32_t*>(target);

    uint32_t out[MAX_OUT + 2];
    int n = relocate(code, target, out);
    if (n <= 0) { ++g_fail; return nullptr; }
    if (n & 1) out[n++] = NOP_I;               

    const size_t tsz = (size_t)n * 4 + 16;
    bool rwx = false;
    uint32_t* t = reinterpret_cast<uint32_t*>(exec_mem(tsz, rwx));
    if (!t) { ++g_fail; return nullptr; }

    ::memcpy(t, out, (size_t)n * 4);
    t[n + 0] = LDRX16_8;
    t[n + 1] = BRX16;
    {
        const uint64_t q = (uint64_t)(target + 16);
        t[n + 2] = (uint32_t)(q & 0xFFFFFFFFull);
        t[n + 3] = (uint32_t)(q >> 32);
    }
    __builtin___clear_cache((char*)t, (char*)t + tsz);
    if (!rwx) ::mprotect(t, tsz, PROT_READ | PROT_EXEC);   

    const uintptr_t page = target & ~(uintptr_t)0xFFF;
    const uintptr_t pend = (target + 16 + 0xFFFu) & ~(uintptr_t)0xFFF;
    const size_t    psz  = (size_t)(pend - page);

    if (::mprotect((void*)page, psz, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        ++g_fail;
        ::munmap(t, tsz);
        return nullptr;
    }
    uint32_t* patch = reinterpret_cast<uint32_t*>(target);
    patch[0] = LDRX16_8;
    patch[1] = BRX16;
    {
        const uint64_t q = (uint64_t)replacement;
        patch[2] = (uint32_t)(q & 0xFFFFFFFFull);
        patch[3] = (uint32_t)(q >> 32);
    }
    __sync_synchronize();
    ::mprotect((void*)page, psz, PROT_READ | PROT_EXEC);
    __builtin___clear_cache((char*)page, (char*)page + psz);

    g_entries[g_count].target = target;
    g_entries[g_count].tramp  = t;
    g_entries[g_count].insns  = n;
    ++g_count;
    g_ok.fetch_add(1);
    LOGI("hook ok  %p -> %p  (tramp %d slots @%p)", (void*)target, replacement, n, t);
    return t;
}

 
static bool installed(uintptr_t target) {
    for (int i = 0; i < g_count; ++i) {
        if (g_entries[i].target == target) return true;
    }
    return false;
}

}  
























using A8 = uint64_t;
using D8 = double;
#define ONYX_A8 D8 d0, D8 d1, D8 d2, D8 d3, D8 d4, D8 d5, D8 d6, D8 d7

using Orig8F = float    (*)(A8,A8,A8,A8,A8,A8,A8,A8, ONYX_A8);
using Orig8B = bool     (*)(A8,A8,A8,A8,A8,A8,A8,A8, ONYX_A8);
using Orig8U = uint64_t (*)(A8,A8,A8,A8,A8,A8,A8,A8, ONYX_A8);
#define ONYX_A0 A8 a0, A8 a1, A8 a2, A8 a3, A8 a4, A8 a5, A8 a6, A8 a7
#define ONYX_FWD a0,a1,a2,a3,a4,a5,a6,a7, d0,d1,d2,d3,d4,d5,d6,d7


static Orig8F                g_origGetFov = nullptr;
static std::atomic<bool>     g_fovOn{false};
static std::atomic<int>      g_fovVal{90};
static std::atomic<unsigned> g_fovCalls{0};

static float hk_getFov(ONYX_A0, ONYX_A8) {
    g_fovCalls.fetch_add(1);
    if (g_fovOn.load()) {
        int w = g_fovVal.load();
        if (w < 30)  w = 30;
        if (w > 160) w = 160;
        return (float)w;
    }
    return g_origGetFov ? g_origGetFov(ONYX_FWD) : 70.0f;
}



static Orig8U                g_origSetupFog = nullptr;
static std::atomic<bool>     g_fbOn{false};
static std::atomic<unsigned> g_fbCalls{0};
static std::atomic<unsigned> g_fbSkips{0};

static void hk_setupFog(ONYX_A0, ONYX_A8) {
    g_fbCalls.fetch_add(1);
    if (g_fbOn.load()) { g_fbSkips.fetch_add(1); return; }   
    if (g_origSetupFog) g_origSetupFog(ONYX_FWD);
}



static Orig8U                g_origCamAvoid = nullptr;
static std::atomic<bool>     g_freecamOn{false};
static std::atomic<unsigned> g_camCalls{0};
static std::atomic<unsigned> g_camSkips{0};

static void hk_camAvoid(ONYX_A0, ONYX_A8) {
    g_camCalls.fetch_add(1);
    if (g_freecamOn.load()) { g_camSkips.fetch_add(1); return; }  
    if (g_origCamAvoid) g_origCamAvoid(ONYX_FWD);
}





static Orig8U                g_origSend = nullptr;
static std::atomic<bool>     g_ping{false};       
static std::atomic<unsigned> g_pktTotal{0};
static std::atomic<unsigned> g_pktWindow{0};
static std::atomic<int>      g_pktPps{0};
static std::atomic<int>      g_pktJitter{0};
static uint64_t              g_pktLastAt = 0;

static uint64_t hk_send(ONYX_A0, ONYX_A8) {
    g_pktTotal.fetch_add(1);
    g_pktWindow.fetch_add(1);
    const uint64_t now = g_nowMs.load();
    if (now) {
        if (g_pktLastAt && now > g_pktLastAt) {
            const uint64_t gap = now - g_pktLastAt;
            if (gap < 5000) g_pktJitter.store((int)gap);
        }
        g_pktLastAt = now;
    }
    return g_origSend ? g_origSend(ONYX_FWD) : (uint64_t)0;
}





static Orig8U                g_origNormalTick = nullptr;
static std::atomic<unsigned> g_ntTotal{0};
static std::atomic<unsigned> g_ntWindow{0};
static std::atomic<int>      g_tpsNow{0};

static void hk_normalTick(ONYX_A0, ONYX_A8) {
    g_ntTotal.fetch_add(1);
    g_ntWindow.fetch_add(1);
    g_nowMs.store(now_ms());
    if (a0) {
        g_player.store(a0);
        g_selfCtx.store(a0 + (uintptr_t)off::ACTOR_ENTITY_CONTEXT);
        g_selfSeen.store(true);
    }
    if (g_origNormalTick) g_origNormalTick(ONYX_FWD);
}









static Orig8F                g_origRangeSqr = nullptr;
static std::atomic<bool>     g_reachOn{false};
static std::atomic<double>   g_reachVal{6.0};
static std::atomic<unsigned> g_reachCalls{0};

static float hk_rangeSqr(ONYX_A0, ONYX_A8) {
    g_reachCalls.fetch_add(1);
    if (g_reachOn.load()) {
        double v = g_reachVal.load();
        if (v < 2.0)  v = 2.0;
        if (v > 16.0) v = 16.0;
        return (float)(v * v);
    }
    return g_origRangeSqr ? g_origRangeSqr(ONYX_FWD) : 9.0f;
}











static Orig8F                g_origRange = nullptr;
static std::atomic<unsigned> g_reachRangeCalls{0};   

static float hk_range(ONYX_A0, ONYX_A8) {
    g_reachRangeCalls.fetch_add(1);
    if (g_reachOn.load()) {
        double v = g_reachVal.load();
        if (v < 2.0)  v = 2.0;
        if (v > 16.0) v = 16.0;
        return (float)v;
    }
    return g_origRange ? g_origRange(ONYX_FWD) : 7.0f;
}






static Orig8B                g_origOnGround = nullptr;
static std::atomic<bool>      g_nofallOn{false};
static std::atomic<bool>      g_critOn{false};
static std::atomic<uint64_t>  g_critUntil{0};
static std::atomic<unsigned>  g_noFallHits{0};
static std::atomic<unsigned>  g_critHits{0};

static bool hk_onGround(ONYX_A0, ONYX_A8) {
    const uintptr_t c = (uintptr_t)a0;          
    if (c && c == g_selfCtx.load()) {
        if (g_nofallOn.load()) { g_noFallHits.fetch_add(1); return true; }
        if (g_critOn.load() && g_nowMs.load() < g_critUntil.load()) {
            g_critHits.fetch_add(1);
            return false;
        }
    }
    return g_origOnGround ? g_origOnGround(ONYX_FWD) : false;
}

static std::atomic<bool> g_hooksTried{false};

 
static std::atomic<unsigned> g_hookSkipped{0};

 








static bool pl_is_code(uintptr_t addr) {
    const uintptr_t b = g_libBase.load();
    const uintptr_t e = g_libEnd.load();
    if (!b || !e || addr < b || addr >= e) return false;
    uint32_t w = 0;
    if (!mem::safe_copy(&w, addr, sizeof(w))) return false;
    return w != 0u && w != 0xFFFFFFFFu;
}

 











static void ensure_hooks() {
    const uintptr_t base = g_libBase.load();
    if (!base) return;

    
    
    
    
    
    
    
    
    
    
    
    
    
    
#define ONYX_ALWAYS_HOOK(rvaName, fn, slot)                                       \
    do {                                                                          \
        const uintptr_t tgt_ = base + rva::rvaName;                               \
        if (!hk::installed(tgt_)) {                                               \
            if (!pl_is_code(tgt_)) {                                              \
                g_hookSkipped.fetch_add(1);                                       \
                LOGW("hook skipped %s @0x%llX: not executable (.so mismatch?)",   \
                     #rvaName, (unsigned long long)rva::rvaName);                 \
            } else {                                                              \
                (slot) = reinterpret_cast<decltype(slot)>(                        \
                        hk::install(tgt_, (void*)(fn)));                          \
            }                                                                     \
        }                                                                         \
    } while (0)

    ONYX_ALWAYS_HOOK(FN_LOCALPLAYER_NORMALTICK,      hk_normalTick, g_origNormalTick);
    ONYX_ALWAYS_HOOK(FN_LEVELRENDERER_GETFOV,        hk_getFov,     g_origGetFov);
    ONYX_ALWAYS_HOOK(FN_LEVELRENDERER_SETUPFOG,      hk_setupFog,   g_origSetupFog);
    ONYX_ALWAYS_HOOK(FN_CAMERA_AVOIDANCE_TICK,       hk_camAvoid,   g_origCamAvoid);
    ONYX_ALWAYS_HOOK(FN_LOOPBACK_SENDTOSERVER,       hk_send,       g_origSend);
    ONYX_ALWAYS_HOOK(FN_PLAYER_GET_ATTACK_RANGE_SQR, hk_rangeSqr,   g_origRangeSqr);
    ONYX_ALWAYS_HOOK(FN_PLAYER_GET_ATTACK_RANGE,     hk_range,      g_origRange);
    ONYX_ALWAYS_HOOK(FN_ACTOR_ISONGROUND,            hk_onGround,   g_origOnGround);

#undef ONYX_ALWAYS_HOOK

    if (!g_hooksTried.exchange(true)) {
        LOGI("ensure_hooks: ok=%d fail=%d count=%d (常装)", hk::g_ok.load(),
             hk::g_fail.load(), hk::g_count);
    }
}

 


static int hook_mask() {
    int m = 0;
    if (g_origNormalTick) m |= 0x01;
    if (g_origGetFov)     m |= 0x02;
    if (g_origSetupFog)   m |= 0x04;
    if (g_origCamAvoid)   m |= 0x08;
    if (g_origSend)       m |= 0x10;
    if (g_origRangeSqr)   m |= 0x20;
    if (g_origOnGround)   m |= 0x40;
    if (g_origRange)      m |= 0x80;
    return m;
}


static std::atomic<bool>     g_velocity{false};
static std::atomic<double>   g_velThresh{0.45};
static std::atomic<unsigned> g_velBlocks{0};

static std::atomic<bool>     g_speed{false};
static std::atomic<double>   g_speedDist{0.30};
static std::atomic<unsigned> g_speedSteps{0};

static std::atomic<bool>     g_sprint{false};
static std::atomic<double>   g_sprintDist{0.80};
static std::atomic<unsigned> g_sprintSteps{0};

static std::atomic<bool>     g_autotool{false};
static std::atomic<int>      g_atSlot{-1};
static std::atomic<unsigned> g_atSwitches{0};
static int                   g_invOff = 0;        

static std::atomic<bool>     g_xray{false};
static std::atomic<int>      g_xrayRange{8};
static std::atomic<unsigned> g_xrayRare{0};
static std::atomic<int>      g_xrayProgress{0};
static std::mutex            g_xrayMtx;
static std::vector<BPos>     g_xrayPos;

static std::atomic<bool>     g_respawn{false};
static std::atomic<unsigned> g_respawns{0};

static std::atomic<bool>     g_tps{false};
static std::atomic<bool>     g_nametags{false};



static inline bool in_module(uintptr_t p) {
    const uintptr_t b = g_libBase.load();
    return b != 0 && p >= b && p < g_libEnd.load();
}

 
















static bool read_actor_pos_ex(uintptr_t actor, float& x, float& y, float& z, bool hot) {
    if (!sane_ptr(actor)) return false;
    const uintptr_t pp = hot ? mem::rd_hot<uintptr_t>(actor + off::ACTOR_POS_PTR)
                             : rd<uintptr_t>(actor + off::ACTOR_POS_PTR);
    if (!sane_ptr(pp)) return false;

    float v[3];
    const bool ok = hot ? mem::safe_copy_pin(v, pp, sizeof(v))
                        : mem::safe_copy(v, pp, sizeof(v));
    if (!ok) return false;
    if (!sane_float(v[0]) || !sane_float(v[1]) || !sane_float(v[2])) return false;
    if (std::fabs(v[0]) > 3.0e7f || std::fabs(v[1]) > 3.0e7f || std::fabs(v[2]) > 3.0e7f) return false;
    
    if (v[1] < -1000.0f || v[1] > 4000.0f) return false;

    x = v[0]; y = v[1]; z = v[2];
    return true;
}

 
static inline bool read_actor_pos(uintptr_t actor, float& x, float& y, float& z) {
    return read_actor_pos_ex(actor, x, y, z, true);
}

 
static inline bool read_actor_pos_safe(uintptr_t actor, float& x, float& y, float& z) {
    return read_actor_pos_ex(actor, x, y, z, false);
}

 
static bool read_vec3(uintptr_t actor, int  , float& x, float& y, float& z) {
    return read_actor_pos(actor, x, y, z);
}

 
static bool read_aabb(uintptr_t actor, float out[6]) {
    if (!sane_ptr(actor)) return false;
    const uintptr_t ap = rd<uintptr_t>(actor + off::ACTOR_AABB);
    if (!sane_ptr(ap) || !readable(ap, 24)) return false;
    for (int i = 0; i < 6; ++i) {
        out[i] = rd<float>(ap + i * 4);
        if (!sane_float(out[i])) return false;
    }
    if (!(out[0] <= out[3] && out[1] <= out[4] && out[2] <= out[5])) return false;
    
    if (out[3] - out[0] > 32.0f) return false;
    if (out[4] - out[1] > 32.0f) return false;
    if (out[5] - out[2] > 32.0f) return false;
    return true;
}

 
static bool read_feet_block(uintptr_t actor, BPos& out) {
    float x, y, z;
    if (!read_actor_pos(actor, x, y, z)) return false;
    const int32_t bx = (int32_t)std::floor(x);
    const int32_t by = (int32_t)std::floor(y);
    const int32_t bz = (int32_t)std::floor(z);
    if (bx < -30000000 || bx > 30000000) return false;
    if (bz < -30000000 || bz > 30000000) return false;
    if (by < -512 || by > 2048) return false;
    out.x = bx; out.y = by; out.z = bz;
    return true;
}

 
static bool read_blockpos(uintptr_t actor, int  , BPos& out) {
    return read_feet_block(actor, out);
}

 












static bool is_actor_ex(uintptr_t o, bool hot) {
    if (!sane_ptr(o)) return false;
    const uintptr_t vtv = hot ? mem::rd_hot<uintptr_t>(o) : rd<uintptr_t>(o);
    if (!in_module(vtv)) return false;
    const uintptr_t ott = hot ? mem::rd_hot<uintptr_t>(vtv - 0x10) : rd<uintptr_t>(vtv - 0x10);
    if (ott != 0) return false;
    const uintptr_t ti = hot ? mem::rd_hot<uintptr_t>(vtv - 0x08) : rd<uintptr_t>(vtv - 0x08);
    if (!in_module(ti)) return false;
    float x, y, z;
    return read_actor_pos_ex(o, x, y, z, hot);
}

 
static inline bool is_actor(uintptr_t o) { return is_actor_ex(o, true); }

 
static inline bool is_actor_safe(uintptr_t o) { return is_actor_ex(o, false); }

 
static bool is_actor_light_ex(uintptr_t o, bool hot) {
    if (!sane_ptr(o)) return false;
    const uintptr_t vtv = hot ? mem::rd_hot<uintptr_t>(o) : rd<uintptr_t>(o);
    if (!in_module(vtv)) return false;
    const uintptr_t ott = hot ? mem::rd_hot<uintptr_t>(vtv - 0x10) : rd<uintptr_t>(vtv - 0x10);
    if (ott != 0) return false;
    const uintptr_t ti = hot ? mem::rd_hot<uintptr_t>(vtv - 0x08) : rd<uintptr_t>(vtv - 0x08);
    return in_module(ti);
}

static inline bool is_actor_light(uintptr_t o) { return is_actor_light_ex(o, true); }

















 
static inline bool is_player_entity(uintptr_t e) {
    if (!sane_ptr(e)) return false;
    const uintptr_t gm = mem::rd_hot<uintptr_t>(e + (uintptr_t)off::PLAYER_GM);
    if (!sane_ptr(gm)) return false;
    if (mem::rd_hot<uintptr_t>(gm + (uintptr_t)off::GM_PLAYER) != e) return false;
    
    return in_module(mem::rd_hot<uintptr_t>(gm));
}

 

static inline bool is_living_entity(uintptr_t e) { return !is_player_entity(e); }

 

static inline bool target_match(uintptr_t e, int target) {
    if (target == 0) return is_living_entity(e);
    if (target == 1) return is_player_entity(e);
    return true;
}










static std::atomic<int> g_gmOff{0};

 
















 























static inline bool is_gamemode_vt(uintptr_t v) {
    const uintptr_t base = g_libBase.load();
    if (!base || !in_module(v)) return false;
    return v == base + vt::GAMEMODE || v == base + vt::GAMEMODE_SURVIVAL;
}

 
static inline bool gm_valid(uintptr_t gm, uintptr_t self) {
    if (!sane_ptr(gm)) return false;
    if (!is_gamemode_vt(mem::rd_hot<uintptr_t>(gm))) return false;
    const uintptr_t back = mem::rd_hot<uintptr_t>(gm + (uintptr_t)off::GM_PLAYER);
    if (!sane_ptr(back)) return false;              
    if (self != 0 && back == self) return true;     
    return is_player_entity(back);                  
}
















static inline bool is_player_entity_safe(uintptr_t e) {
    if (!sane_ptr(e)) return false;
    const uintptr_t gm = rd<uintptr_t>(e + (uintptr_t)off::PLAYER_GM);
    if (!sane_ptr(gm)) return false;
    if (rd<uintptr_t>(gm + (uintptr_t)off::GM_PLAYER) != e) return false;
    return in_module(rd<uintptr_t>(gm));
}

static inline bool gm_valid_safe(uintptr_t gm, uintptr_t self) {
    if (!sane_ptr(gm)) return false;
    if (!is_gamemode_vt(rd<uintptr_t>(gm))) return false;
    const uintptr_t back = rd<uintptr_t>(gm + (uintptr_t)off::GM_PLAYER);
    if (!sane_ptr(back)) return false;
    if (self != 0 && back == self) return true;
    return is_player_entity_safe(back);
}

 

















static inline uintptr_t gm_self(uintptr_t gm) {
    if (!sane_ptr(gm)) return 0;
    const uintptr_t p = mem::rd_hot<uintptr_t>(gm + (uintptr_t)off::GM_PLAYER);
    return sane_ptr(p) ? p : 0;
}

static uintptr_t find_gamemode(uintptr_t self) {
    const uintptr_t base = g_libBase.load();
    if (!base || !sane_ptr(self)) return 0;

    
    
    
    
    
    
    
    
    
    
    
    
    {
        const uintptr_t g = rd<uintptr_t>(self + (uintptr_t)off::PLAYER_GM);
        if (gm_valid_safe(g, self)) {
            g_gmOff.store((int)off::PLAYER_GM);
            return g;
        }
    }

    
    auto probe = [&](int o) -> uintptr_t {
        const uintptr_t p = rd<uintptr_t>(self + (uintptr_t)o);
        return gm_valid_safe(p, self) ? p : 0;
    };

    
    {
        const int cached = g_gmOff.load();
        if (cached > 0) {
            const uintptr_t p = probe(cached);
            if (p) return p;
            g_gmOff.store(0);                     
        }
    }

    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    for (int o = off::PLAYER_BODY_SCAN_LO; o + 8 <= off::PLAYER_BODY_SCAN_HI; o += 8) {
        const uintptr_t p = probe(o);
        if (p) {
            g_gmOff.store(o);
            LOGI("gamemode found: Player+0x%X -> %p (Player+0x%X -> %p)",
                 o, (void*)p, (int)off::PLAYER_GM,
                 (void*)rd<uintptr_t>(self + (uintptr_t)off::PLAYER_GM));
            return p;
        }
    }
    return 0;
}

























static constexpr uintptr_t VT_SCAN_LO = 0x12400000;   
static constexpr uintptr_t VT_SCAN_HI = 0x12D00000;   



static constexpr int       VT_LEARN_PER_ROUND = 48;   




static constexpr size_t VT_BITS = (VT_SCAN_HI - VT_SCAN_LO) / 8;      
static constexpr size_t VT_BYTES = (VT_BITS + 7) / 8;
static uint8_t g_actorVtBits[VT_BYTES];
static std::atomic<unsigned> g_actorVtCount{0};

static inline bool vt_bit_test(uintptr_t rva) {
    const size_t idx = (rva - VT_SCAN_LO) >> 3;          
    return (g_actorVtBits[idx >> 3] >> (idx & 7)) & 1;
}
static inline void vt_bit_set(uintptr_t rva) {
    const size_t idx = (rva - VT_SCAN_LO) >> 3;
    g_actorVtBits[idx >> 3] |= (uint8_t)(1u << (idx & 7));
}

 
static inline bool actor_vt_known(uintptr_t rva) {
    return rva >= VT_SCAN_LO && rva < VT_SCAN_HI && vt_bit_test(rva);
}

 
static bool actor_vt_learn(uintptr_t v, uintptr_t obj) {
    
    
    
    
    
    
    
    
    if (rd<uintptr_t>(v - 0x10) != 0) return false;
    
    if (!in_module(rd<uintptr_t>(v - 0x08))) return false;
    
    
    
    
    
    
    
    if (rd<uintptr_t>(obj) != v) return false;
    if (!is_actor_ex(obj, false)) return false;

    vt_bit_set(v - g_libBase.load());
    const unsigned n = g_actorVtCount.fetch_add(1) + 1;
    LOGI("actor-vt learned: %p rva=0x%llX total=%u",
         (void*)v, (unsigned long long)(v - g_libBase.load()), n);
    return true;
}

static void scan_once() {
    uint64_t t0 = now_ms();

    const Base b = game_module();
    if (!b.lo) {
        LOGW("scan: libminecraftpe.so 还没映射进来");
        return;
    }
    g_libBase.store(b.lo);
    g_libEnd.store(b.hi);

    const uintptr_t B = b.lo;
    std::vector<Range> rs = writable_ranges();

    
    
    
    
    
    
    
    if (b.lo && b.hi > b.lo) {
        const uintptr_t mLo = b.lo, mHi = b.hi;
        std::stable_sort(rs.begin(), rs.end(), [mLo, mHi](const Range& a, const Range& c) {
            const bool ina = (a.s >= mLo && a.e <= mHi);
            const bool inc = (c.s >= mLo && c.e <= mHi);
            return ina > inc;                       
        });
    }

    uintptr_t lp = 0;
    uintptr_t gmFast = 0;         
    
    uintptr_t triedVt[VT_LEARN_PER_ROUND];
    int nTried = 0;
    
    
    
    
    
    
    uintptr_t pairP[8] = {0};
    uintptr_t pairG[8] = {0};
    int npair = 0;
    int rej = 0;
    bool doneEarly = false;       

    std::vector<Ent> ents;
    ents.reserve(256);

    
    
    
    
    
    
    
    static constexpr size_t CHUNK = 1 << 16;
    static std::vector<uint8_t> shadow;
    if (shadow.size() != CHUNK) shadow.resize(CHUNK);

    uint64_t readBytes = 0;
    
    
    
    const uint64_t BUDGET = 96ull << 20;

    
    
    static size_t    curRange = 0;
    static uintptr_t curOff   = 0;

    const size_t nR = rs.size();
    bool over = false;

    for (size_t k = 0; k < nR && !over; ++k) {
        const Range& r = rs[(curRange + k) % nR];
        uintptr_t p = (k == 0 && curOff) ? curOff : ((r.s + 7) & ~(uintptr_t)7);

        while (p + 8 <= r.e) {
            const size_t want = (size_t)std::min<uintptr_t>(CHUNK, r.e - p) & ~(size_t)7;
            if (want < 8) break;

            
            
            
            
            const size_t got = mem::safe_copy_n(shadow.data(), p, want);
            if (got < 8) { p += (size_t)CHUNK; continue; }   

            readBytes += got;
            const size_t n = got & ~(size_t)7;

            const uint8_t* buf = shadow.data();
            for (size_t o = 0; o + 8 <= n; o += 8) {
                uintptr_t v;
                ::memcpy(&v, buf + o, 8);
                if (v < B || v >= b.hi) continue;     
                const uintptr_t rva = v - B;
                const uintptr_t obj = p + o;          

                if (rva == vt::LOCALPLAYER) {
                    if (!lp) {
                        lp = obj;
                        
                        
                        
                        const uintptr_t c = rd<uintptr_t>(obj + (uintptr_t)off::PLAYER_GM);
                        if (gm_valid_safe(c, obj)) {
                            gmFast = c;
                            doneEarly = true;
                            over = true;
                            break;
                        }
                    }
                    continue;
                }
                
                
                
                
                
                
                bool isActorObj = false;
                if (rva == vt::ACTOR || rva == vt::MOB || rva == vt::PLAYER) {
                    isActorObj = true;
                } else if (rva >= VT_SCAN_LO && rva < VT_SCAN_HI) {
                    if (vt_bit_test(rva)) {
                        isActorObj = true;
                    } else if (nTried < VT_LEARN_PER_ROUND) {
                        bool dup = false;
                        for (int i = 0; i < nTried; ++i) {
                            if (triedVt[i] == rva) { dup = true; break; }
                        }
                        if (!dup) {
                            triedVt[nTried++] = rva;
                            if (actor_vt_learn(v, obj)) isActorObj = true;
                        }
                    }
                }
                if (isActorObj) {
                    float x, y, z;
                    if (!read_actor_pos_safe(obj, x, y, z)) { ++rej; continue; }
                    if (ents.size() < 512) {
                        Ent e{};
                        e.p = obj; e.x = x; e.y = y; e.z = z;
                        e.src = 0;                      
                        
                        
                        
                        
                        
                        
                        
                        
                        e.isPlayer = g_kaura.load() && is_player_entity_safe(obj);
                        ents.push_back(e);
                    }
                    continue;
                }
                if (rva == vt::GAMEMODE || rva == vt::GAMEMODE_SURVIVAL) {
                    
                    
                    
                    
                    
                    if (gm_valid_safe(obj, lp)) {
                        const uintptr_t pl = rd<uintptr_t>(obj + (uintptr_t)off::GM_PLAYER);
                        if (npair < 8) { pairP[npair] = pl; pairG[npair] = obj; ++npair; }
                    }
                    continue;
                }
            }

            p += n;
            if (over) break;                                
            if (readBytes >= BUDGET) { over = true; break; }
        }

        
        if (over && !doneEarly) { curRange = (curRange + k) % nR; curOff = p; }
    }
    if (!over && nR) { curRange = 0; curOff = 0; }   

    
    uintptr_t gm = gmFast;        

    
    
    if (!gm && lp) {
        const uintptr_t cand = rd<uintptr_t>(lp + (uintptr_t)off::PLAYER_GM);
        if (gm_valid_safe(cand, lp)) gm = cand;
    }
    
    if (!gm && npair > 0) {
        for (int i = 0; i < npair; ++i) {
            if (lp == 0 || pairP[i] == lp) {
                lp = pairP[i];
                gm = pairG[i];
                break;
            }
        }
        if (!gm) { lp = pairP[0]; gm = pairG[0]; }
    }
    
    
    
    
    

    
    float sx = 0, sy = 0, sz = 0;
    const bool haveSelf = lp != 0 && read_actor_pos_safe(lp, sx, sy, sz);
    if (haveSelf) {
        std::vector<Ent> keep;
        keep.reserve(ents.size());
        for (const auto& e : ents) {
            if (e.p == lp) continue;
            const float dx = e.x - sx, dy = e.y - sy, dz = e.z - sz;
            if (dx * dx + dy * dy + dz * dz > 96.0f * 96.0f) continue;   
            keep.push_back(e);
        }
        ents.swap(keep);
    }

    {
        std::lock_guard<std::mutex> lk(g_entMtx);
        g_ents.swap(ents);
        g_entCount.store((int)g_ents.size());
    }

    
    
    
    
    
    
    
    
    if (lp && !g_selfSeen.load() && haveSelf) g_player.store(lp);
    if (gm) g_gamemode.store(gm);
    g_rejected.store(rej);
    g_scanKB.store((unsigned)(readBytes >> 10));
    g_scanMs.store((int)(now_ms() - t0));
    g_scans.fetch_add(1);

    LOGI("scan #%d done: %dms | ranges=%zu player=%p gm=%p ents=%zu pair=%d rej=%d",
         (int)g_scans.load(), (int)g_scanMs.load(), rs.size(),
         (void*)lp, (void*)gm, g_ents.size(), npair, rej);
}



static void killaura_fire() {
    if (!g_kaura.load()) return;
    if (rva::FN_GAMEMODE_ATTACK == 0) return;

    const uintptr_t base = g_libBase.load();
    const uintptr_t gm = g_gamemode.load();
    if (!base || !gm) { g_kaNoGm.fetch_add(1); return; }

    
    
    
    
    
    
    
    
    
    
    
    
    
    uintptr_t self = gm_self(gm);
    if (!sane_ptr(self)) self = g_player.load();
    if (!sane_ptr(self)) { g_kaNoGm.fetch_add(1); return; }

    float sx, sy, sz;
    if (!read_vec3(self, 0, sx, sy, sz)) return;

    double range = g_kauraRange.load();
    if (range < 1.0) range = 1.0;
    if (range > 12.0) range = 12.0;
    const float r2 = (float)(range * range);

    
    
    const int kTarget = g_kauraTarget.load();
    std::vector<std::pair<float, uintptr_t>> cand;
    cand.reserve(16);
    {
        std::lock_guard<std::mutex> lk(g_entMtx);
        for (const auto& e : g_ents) {
            if (e.p == self) continue;
            
            
            
            
            if (!is_actor(e.p)) continue;
            if (!target_match(e.p, kTarget)) continue;
            float x, y, z;
            if (!read_vec3(e.p, 0, x, y, z)) continue;
            const float dx = x - sx, dy = y - sy, dz = z - sz;
            const float d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > r2) continue;
            cand.push_back({d2, e.p});
        }
    }
    g_seen.store((int)cand.size());
    if (cand.empty()) return;

    const int mode = g_kauraMode.load();
    int delay = g_kauraDelay.load();
    if (delay < 20) delay = 20;
    if (delay > 2000) delay = 2000;

    static uint64_t lastAtk = 0;
    const uint64_t now = now_ms();
    if (now - lastAtk < (uint64_t)delay) return;
    lastAtk = now;

    
    
    
    if (g_critOn.load()) g_critUntil.store(g_nowMs.load() + 150);

    
    using AtkFn = void (*)(void*, void*);
    const AtkFn fn = reinterpret_cast<AtkFn>(base + rva::FN_GAMEMODE_ATTACK);

    if (mode == 1) {                                      
        for (const auto& c : cand) {
            fn(reinterpret_cast<void*>(gm), reinterpret_cast<void*>(c.second));
            g_attacks.fetch_add(1);
        }
        LOGI("attack(Multi) -> %zu targets seen=%zu total=%u",
             cand.size(), cand.size(), g_attacks.load());
    } else {                                              
        float bestD2 = cand[0].first;
        uintptr_t best = cand[0].second;
        for (const auto& c : cand) {
            if (c.first < bestD2) { bestD2 = c.first; best = c.second; }
        }
        fn(reinterpret_cast<void*>(gm), reinterpret_cast<void*>(best));
        g_attacks.fetch_add(1);
        LOGI("attack(Single) -> target=%p dist2=%.2f seen=%zu total=%u",
             (void*)best, (double)bestD2, cand.size(), g_attacks.load());
    }
}



 















static void scaffold_tick() {
    if (!g_scaffold.load()) return;
    if (rva::FN_GAMEMODE_BUILD == 0) return;

    const uintptr_t base = g_libBase.load();
    const uintptr_t gm = g_gamemode.load();
    if (!base || !gm) { g_scNoGm.fetch_add(1); return; }

    
    uintptr_t self = gm_self(gm);
    if (!sane_ptr(self)) self = g_player.load();
    if (!sane_ptr(self)) { g_scNoGm.fetch_add(1); return; }

    BPos pp{0, 0, 0};
    if (!read_blockpos(self, 0, pp)) {
        
        float x, y, z;
        if (!read_vec3(self, 0, x, y, z)) return;
        pp.x = (int32_t)std::floor(x);
        pp.y = (int32_t)std::floor(y);
        pp.z = (int32_t)std::floor(z);
        g_bpFallback.fetch_add(1);
    }

    int delay = g_scaffoldDelay.load();
    if (delay < 20) delay = 20;
    if (delay > 1000) delay = 1000;
    static uint64_t lastAt = 0;
    const uint64_t now = now_ms();
    if (now - lastAt < (uint64_t)delay) return;
    lastAt = now;

    BPos click{pp.x, pp.y - 2, pp.z};

    using BuildFn = bool (*)(void*, void*, unsigned char, void*);
    const BuildFn fn = reinterpret_cast<BuildFn>(base + rva::FN_GAMEMODE_BUILD);
    const bool ok = fn(reinterpret_cast<void*>(gm), &click, 1  , nullptr);

    g_builds.fetch_add(1);
    if (ok) {
        g_buildOk.fetch_add(1);
        LOGI("scaffold place at (%d,%d,%d) click=(%d,%d,%d) ok=%u/%u",
             (int)pp.x, (int)(pp.y - 1), (int)pp.z,
             (int)click.x, (int)click.y, (int)click.z,
             g_buildOk.load(), g_builds.load());
    }
}



 








static bool read_view_angles(uintptr_t player, float& yaw, float& pitch) {
    const uintptr_t base = g_libBase.load();
    if (!base || !sane_ptr(player)) return false;

    const uintptr_t vt = rd<uintptr_t>(player);
    if (!in_module(vt)) return false;

    const uintptr_t fa = rd<uintptr_t>(vt + (uintptr_t)rva::VT_YAW_SLOT * 8);
    const uintptr_t fb = rd<uintptr_t>(vt + (uintptr_t)rva::VT_PITCH_SLOT * 8);
    if (!in_module(fa) || !in_module(fb)) return false;

    using AngleFn = float (*)(void*, float);
    const float y = reinterpret_cast<AngleFn>(fa)(reinterpret_cast<void*>(player), 1.0f);
    const float p = reinterpret_cast<AngleFn>(fb)(reinterpret_cast<void*>(player), 1.0f);
    if (!sane_float(y) || !sane_float(p)) return false;
    if (std::fabs(y) > 100000.0f || std::fabs(p) > 100000.0f) return false;

    yaw = y;
    pitch = p;
    return true;
}

 
struct Cam {
    float ex, ey, ez;
    float fx, fy, fz;      
    float rx, ry, rz;      
    float ux, uy, uz;      
    float focal;           
    float sw, sh;
};

static void cam_build(Cam& c, float px, float py, float pz,
                      float yaw, float pitch, float sw, float sh, float fovDeg) {
    const float PI = 3.14159265358979f;
    const float ry = yaw * PI / 180.0f;
    const float rp = pitch * PI / 180.0f;
    const float cp = std::cos(rp), sp = std::sin(rp);
    const float cy = std::cos(ry), sy = std::sin(ry);

    c.ex = px;
    c.ey = py + 1.62f;                       
    c.ez = pz;

    c.fx = -sy * cp; c.fy = -sp;  c.fz =  cy * cp;
    c.rx =  cy;      c.ry = 0.0f; c.rz =  sy;

    
    c.ux = c.ry * c.fz - c.rz * c.fy;
    c.uy = c.rz * c.fx - c.rx * c.fz;
    c.uz = c.rx * c.fy - c.ry * c.fx;

    float fov = fovDeg;
    if (fov < 30.0f) fov = 30.0f;
    if (fov > 120.0f) fov = 120.0f;
    c.focal = (sh * 0.5f) / std::tan(fov * 0.5f * PI / 180.0f);

    c.sw = sw;
    c.sh = sh;
}

 
static bool cam_project(const Cam& c, float x, float y, float z, float& ox, float& oy) {
    const float dx = x - c.ex, dy = y - c.ey, dz = z - c.ez;
    const float depth = dx * c.fx + dy * c.fy + dz * c.fz;
    if (depth < 0.15f) return false;
    const float sx = dx * c.rx + dy * c.ry + dz * c.rz;
    const float sy = dx * c.ux + dy * c.uy + dz * c.uz;
    ox = c.sw * 0.5f + sx * c.focal / depth;
    oy = c.sh * 0.5f - sy * c.focal / depth;
    return true;
}

 
static bool push_box(std::vector<float>& out, const Cam& cam,
                     float cx, float cy, float cz, float hw, float hh) {
    float ax, ay, bx, by, gx, gy, dx, dy;
    if (!cam_project(cam, cx - cam.rx * hw, cy,      cz - cam.rz * hw, ax, ay)) return false;
    if (!cam_project(cam, cx + cam.rx * hw, cy,      cz + cam.rz * hw, bx, by)) return false;
    if (!cam_project(cam, cx - cam.rx * hw, cy + hh, cz - cam.rz * hw, gx, gy)) return false;
    if (!cam_project(cam, cx + cam.rx * hw, cy + hh, cz + cam.rz * hw, dx, dy)) return false;

    const float x0 = std::fmin(std::fmin(ax, bx), std::fmin(gx, dx));
    const float x1 = std::fmax(std::fmax(ax, bx), std::fmax(gx, dx));
    const float y0 = std::fmin(std::fmin(ay, by), std::fmin(gy, dy));
    const float y1 = std::fmax(std::fmax(ay, by), std::fmax(gy, dy));

    const float nx0 = x0 / cam.sw, ny0 = y0 / cam.sh;
    const float nx1 = x1 / cam.sw, ny1 = y1 / cam.sh;
    if (nx1 < 0.0f || ny1 < 0.0f || nx0 > 1.0f || ny0 > 1.0f) return false;

    out.push_back(nx0); out.push_back(ny0);
    out.push_back(nx1); out.push_back(ny1);
    return true;
}

 
static void esp_build(std::vector<float>& out, float sw, float sh, float fovDeg, double range) {
    out.clear();
    if (sw < 2.0f || sh < 2.0f) return;

    const uintptr_t self = g_player.load();
    if (!sane_ptr(self)) { g_espFail.fetch_add(1); return; }

    float px, py, pz;
    if (!read_actor_pos(self, px, py, pz)) { g_espFail.fetch_add(1); return; }

    float yaw, pitch;
    if (!read_view_angles(self, yaw, pitch)) { g_espFail.fetch_add(1); return; }

    Cam cam;
    cam_build(cam, px, py, pz, yaw, pitch, sw, sh, fovDeg);

    const float r2 = static_cast<float>(range * range);
    int n = 0;

    
    if (g_esp.load()) {
        std::lock_guard<std::mutex> lk(g_entMtx);
        for (const auto& e : g_ents) {
            if (e.p == self) continue;
            const float dx = e.x - px, dy = e.y - py, dz = e.z - pz;
            if (dx * dx + dy * dy + dz * dz > r2) continue;
            
            if (push_box(out, cam, e.x, e.y, e.z, 0.35f, 1.8f)) {
                if (++n >= 64) break;
            }
        }
    }
    g_espDrawn.store(static_cast<unsigned>(n));

    
    if (g_chest.load()) {
        std::vector<BPos> cs;
        {
            std::lock_guard<std::mutex> lk(g_chestMtx);
            cs = g_chestPos;
        }
        for (const auto& p : cs) {
            const float cx = static_cast<float>(p.x) + 0.5f;
            const float cy = static_cast<float>(p.y);
            const float cz = static_cast<float>(p.z) + 0.5f;
            const float dx = cx - px, dy = cy - py, dz = cz - pz;
            if (dx * dx + dy * dy + dz * dz > r2) continue;
            if (push_box(out, cam, cx, cy, cz, 0.5f, 1.0f)) {
                if (++n >= 96) break;
            }
        }
    }

    
    if (g_xray.load()) {
        std::vector<BPos> xs;
        {
            std::lock_guard<std::mutex> lk(g_xrayMtx);
            xs = g_xrayPos;
        }
        for (const auto& p : xs) {
            const float cx = static_cast<float>(p.x) + 0.5f;
            const float cy = static_cast<float>(p.y);
            const float cz = static_cast<float>(p.z) + 0.5f;
            const float dx = cx - px, dy = cy - py, dz = cz - pz;
            if (dx * dx + dy * dy + dz * dz > r2) continue;
            if (push_box(out, cam, cx, cy, cz, 0.5f, 0.5f)) {
                if (++n >= 320) break;
            }
        }
    }
}








 
template <typename F>
static inline F fn_at(uintptr_t rva) {
    const uintptr_t b = g_libBase.load();
    if (!b || rva == 0 || rva >= 0x14000000) return nullptr;
    return reinterpret_cast<F>(b + rva);
}

 
static inline bool gm_ok(uintptr_t gm, uintptr_t self) {
    return gm != 0 && self != 0 && rd<uintptr_t>(gm + off::GM_PLAYER) == self;
}

 
static uintptr_t self_blocksource(uintptr_t self) {
    using GetBsFn = uintptr_t (*)(void*);
    GetBsFn getBs = fn_at<GetBsFn>(rva::FN_ACTOR_GET_DIM_BLOCKSOURCE);
    if (!getBs) return 0;
    return getBs(reinterpret_cast<void*>(self));
}

 






static bool block_is_air(uintptr_t bs, const BPos& p) {
    using GetBlockFn = uintptr_t (*)(void*, const void*);
    using IsAirFn    = bool (*)(const void*);
    GetBlockFn getBlock = fn_at<GetBlockFn>(rva::FN_BLOCKSOURCE_GETBLOCK);
    IsAirFn    isAir    = fn_at<IsAirFn>(rva::FN_BLOCK_ISAIR);
    if (!bs || !getBlock || !isAir) return true;

    const uintptr_t blk = getBlock(reinterpret_cast<void*>(bs), &p);
    if (!sane_ptr(blk)) return true;
    if (!sane_ptr(mem::rd_hot<uintptr_t>(blk + 0x90))) return true;   
    return isAir(reinterpret_cast<const void*>(blk));
}

 









static bool try_break(uintptr_t gm, const BPos& p) {
    using DestroyFn = bool (*)(void*, const void*, unsigned, bool, bool);
    DestroyFn destroy = fn_at<DestroyFn>(rva::FN_GAMEMODE_DESTROY);
    if (!destroy) return false;
    g_breaks.fetch_add(1);
    const bool ok = destroy(reinterpret_cast<void*>(gm), &p, 1u, true, true);
    if (ok) g_breakOk.fetch_add(1);
    return ok;
}



 














static void fly_tick() {
    const bool want = g_fly.load();
    if (!want && g_flyRead.load() == 0) return;      

    const uintptr_t self = g_player.load();
    if (!self) return;

    using GetAbFn = void* (*)(void*);
    using SetAbFn = void (*)(void*, int, bool);
    using IsFlyFn = bool (*)(const void*);
    GetAbFn getAb = fn_at<GetAbFn>(rva::FN_PLAYER_GET_ABILITIES);
    SetAbFn setAb = fn_at<SetAbFn>(rva::FN_ABILITIES_SETABILITY);
    if (!getAb || !setAb) return;

    void* ab = getAb(reinterpret_cast<void*>(self));
    if (!sane_ptr(reinterpret_cast<uintptr_t>(ab))) return;

    setAb(ab, rva::ABILITY_MAYFLY, want);
    setAb(ab, rva::ABILITY_FLYING, want);
    g_flyWrites.fetch_add(1);

    IsFlyFn isFly = fn_at<IsFlyFn>(rva::FN_LOCALPLAYER_ISFLYING);
    if (isFly) g_flyRead.store(isFly(reinterpret_cast<const void*>(self)) ? 1 : 0);
}



 








static void autojump_tick() {
    if (!g_autojump.load()) return;
    const uintptr_t self = g_player.load();
    if (!self) return;

    int delay = g_jumpDelay.load();
    if (delay < 50) delay = 50;
    if (delay > 2000) delay = 2000;

    static uint64_t lastAt = 0;
    const uint64_t now = now_ms();
    if (now - lastAt < static_cast<uint64_t>(delay)) return;

    using OnGroundFn = bool (*)(const void*);
    using JumpFn     = bool (*)(void*);
    OnGroundFn onGround = fn_at<OnGroundFn>(rva::FN_ACTOR_ISONGROUND);
    JumpFn     jump     = fn_at<JumpFn>(rva::FN_MOB_JUMPFROMGROUND);
    if (!onGround || !jump) return;

    
    if (!onGround(reinterpret_cast<const void*>(self + off::ACTOR_ENTITY_CONTEXT))) return;

    lastAt = now;
    if (jump(reinterpret_cast<void*>(self))) {
        g_jumps.fetch_add(1);
        LOGI("autojump ok total=%u", g_jumps.load());
    }
}




























static void tp_abs(uintptr_t self, float tx, float ty, float tz) {
    using TeleportFn = void (*)(void*, const void*, bool, unsigned, unsigned, bool);
    TeleportFn tp = fn_at<TeleportFn>(rva::FN_ACTOR_TELEPORT);
    if (!tp || !self) return;

    static std::atomic<float> s_delta{0.0f};   
    float delta = s_delta.load();

    
    using YOffFn = float (*)(void*);
    if (YOffFn getY = fn_at<YOffFn>(rva::FN_ACTOR_TELEPORT_YOFF)) {
        const float d = getY(reinterpret_cast<void*>(self + (uintptr_t)off::ACTOR_ENTITY_CONTEXT));
        if (sane_float(d) && d > -4.0f && d < 4.0f) {
            delta = d;
            s_delta.store(d);
        }
    }

    const float inY  = ty - delta;
    const float pos[3] = { tx, inY, tz };
    tp(reinterpret_cast<void*>(self), pos, false, 0u, 0u, true);

    
    float gx, gy, gz;
    if (read_actor_pos(self, gx, gy, gz) && sane_float(gy)) {
        const float real = gy - ty;            
        if (real > -4.0f && real < 4.0f) {
            if (delta == 0.0f) {
                if (std::fabs(real) > 0.02f) s_delta.store(real);      
            } else if (std::fabs(real) > 0.05f) {
                s_delta.store(delta + real);                           
            }
        }
    }
}



 








static void teleport_tick() {
    if (!g_tpOn.load()) return;

    
    static uint64_t lastAt = 0;
    const uint64_t now = now_ms();
    if (now - lastAt < 600) return;

    const uintptr_t self = g_player.load();
    if (!self) return;

    float x, y, z;
    if (!read_actor_pos(self, x, y, z)) return;

    double d = g_tpDist.load();
    if (d < 0.5) d = 0.5;
    if (d > 16.0) d = 16.0;

    float nx = x, ny = y, nz = z;
    if (g_tpMode.load() == 1) {                       
        float yaw, pitch;
        if (!read_view_angles(self, yaw, pitch)) return;
        const float PI = 3.14159265358979f;
        const float ry = yaw * PI / 180.0f;
        nx = x - std::sin(ry) * static_cast<float>(d);
        nz = z + std::cos(ry) * static_cast<float>(d);
    } else {                                          
        ny = y + static_cast<float>(d);
    }

    lastAt = now;
    
    
    tp_abs(self, nx, ny, nz);
    g_tpCount.fetch_add(1);
    LOGI("teleport mode=%d %.1f -> (%.2f,%.2f,%.2f) total=%u",
         g_tpMode.load(), d, nx, ny, nz, g_tpCount.load());
}



 













static void hitbox_tick() {
    if (!g_hitbox.load()) return;
    const uintptr_t self = g_player.load();
    if (!self) return;

    using SetSizeFn = void (*)(void*, const void*);
    SetSizeFn setSize = fn_at<SetSizeFn>(rva::FN_ACTOR_SET_SIZE);
    if (!setSize) return;

    float sz[2];
    sz[0] = static_cast<float>(g_hbW.load());
    sz[1] = static_cast<float>(g_hbH.load());
    if (sz[0] < 0.2f) sz[0] = 0.2f;  if (sz[0] > 6.0f) sz[0] = 6.0f;
    if (sz[1] < 0.4f) sz[1] = 0.4f;  if (sz[1] > 8.0f) sz[1] = 8.0f;

    
    setSize(reinterpret_cast<void*>(self), sz);
    g_hbWrites.fetch_add(1);

    const int target = g_hbTarget.load();          
    if (target == 0) return;

    
    
    
    float sx, sy, szz;
    if (!read_actor_pos(self, sx, sy, szz)) return;

    int hit = 0;
    std::lock_guard<std::mutex> lk(g_entMtx);
    for (const auto& e : g_ents) {
        if (hit >= 16) break;
        if (e.p == self) continue;
        const float dx = e.x - sx, dy = e.y - sy, dz = e.z - szz;
        if (dx * dx + dy * dy + dz * dz > 32.0f * 32.0f) continue;
        
        if (target == 1 && !is_living_entity(e.p))  continue;
        if (target == 2 && !is_player_entity(e.p))  continue;
        setSize(reinterpret_cast<void*>(e.p), sz);
        ++hit;
    }
}



 








static void autobreak_tick() {
    if (!g_autobreak.load()) return;
    const uintptr_t gm = g_gamemode.load();
    
    uintptr_t self = gm_self(gm);
    if (!sane_ptr(self)) self = g_player.load();
    if (!gm_ok(gm, self)) return;

    static uint64_t lastAt = 0;
    const uint64_t now = now_ms();
    if (now - lastAt < 120) return;

    const uintptr_t bs = self_blocksource(self);
    if (!bs) return;

    float x, y, z, yaw, pitch;
    if (!read_actor_pos(self, x, y, z)) return;
    if (!read_view_angles(self, yaw, pitch)) return;

    const float PI = 3.14159265358979f;
    const float ry = yaw * PI / 180.0f;
    const float rp = pitch * PI / 180.0f;
    const float cp = std::cos(rp);
    const float dx = -std::sin(ry) * cp;
    const float dy = -std::sin(rp);
    const float dz =  std::cos(ry) * cp;

    const float ex = x, ey = y + 1.62f, ez = z;
    const BPos feet{static_cast<int32_t>(std::floor(x)),
                    static_cast<int32_t>(std::floor(y)),
                    static_cast<int32_t>(std::floor(z))};

    for (float t = 0.5f; t <= 5.0f; t += 0.25f) {
        const BPos p{static_cast<int32_t>(std::floor(ex + dx * t)),
                     static_cast<int32_t>(std::floor(ey + dy * t)),
                     static_cast<int32_t>(std::floor(ez + dz * t))};
        if (p.y < -64) continue;
        
        if (p.x == feet.x && p.z == feet.z && (p.y == feet.y || p.y == feet.y + 1)) continue;
        if (block_is_air(bs, p)) continue;
        lastAt = now;
        try_break(gm, p);
        return;
    }
}



 





static void nuker_tick() {
    if (!g_nuker.load()) return;
    const uintptr_t gm = g_gamemode.load();
    
    uintptr_t self = gm_self(gm);
    if (!sane_ptr(self)) self = g_player.load();
    if (!gm_ok(gm, self)) return;

    static uint64_t lastAt = 0;
    const uint64_t now = now_ms();
    if (now - lastAt < 50) return;
    lastAt = now;

    const uintptr_t bs = self_blocksource(self);
    if (!bs) return;

    BPos c{0, 0, 0};
    if (!read_blockpos(self, 0, c)) {
        float x, y, z;
        if (!read_actor_pos(self, x, y, z)) return;
        c.x = static_cast<int32_t>(std::floor(x));
        c.y = static_cast<int32_t>(std::floor(y));
        c.z = static_cast<int32_t>(std::floor(z));
    }

    int R = g_nukerRange.load();
    if (R < 1) R = 1;
    if (R > 5) R = 5;

    const int side = 2 * R + 1;
    const int total = side * side * side;
    if (g_nukerCursor >= total || g_nukerCursor < 0) g_nukerCursor = 0;

    
    
    int budget = 16;
    while (budget-- > 0) {
        const int i = g_nukerCursor;
        g_nukerCursor = (g_nukerCursor + 1) % total;

        const int ax = i % side;
        const int ay = (i / side) % side;
        const int az = i / (side * side);
        const BPos p{c.x + ax - R, c.y + ay - R, c.z + az - R};
        if (p.y < -64) continue;

        
        if (p.x == c.x && p.z == c.z && (p.y == c.y || p.y == c.y + 1)) continue;
        if (block_is_air(bs, p)) continue;

        if (try_break(gm, p)) return;      
    }
}



 



static std::atomic<bool> g_gmDirty{false};

static void gamemode_tick() {
    if (!g_gmDirty.exchange(false)) return;

    const int want = g_gametype.load();
    if (want < 0 || want > 2) return;

    const uintptr_t self = g_player.load();
    if (!self) { g_gmDirty.store(true); return; }     

    using SetGameTypeFn = void (*)(void*, unsigned);
    SetGameTypeFn fn = fn_at<SetGameTypeFn>(rva::FN_PLAYER_SET_GAMETYPE);
    if (!fn) return;

    fn(reinterpret_cast<void*>(self), static_cast<unsigned>(want));
    g_gmWrites.fetch_add(1);
    LOGI("setPlayerGameType(%d) total=%u", want, g_gmWrites.load());
}



 




static void chest_scan_step() {
    const uintptr_t self = g_player.load();
    if (!self) return;

    using GetBeFn = uintptr_t (*)(void*, const void*);
    GetBeFn getBe = fn_at<GetBeFn>(rva::FN_BLOCKSOURCE_GETBLOCKENTITY);
    if (!getBe) return;

    const uintptr_t bs = self_blocksource(self);
    if (!bs) return;

    BPos c{0, 0, 0};
    if (!read_blockpos(self, 0, c)) {
        float x, y, z;
        if (!read_actor_pos(self, x, y, z)) return;
        c.x = static_cast<int32_t>(std::floor(x));
        c.y = static_cast<int32_t>(std::floor(y));
        c.z = static_cast<int32_t>(std::floor(z));
    }

    int R = g_chestRange.load();
    if (R < 2) R = 2;
    if (R > 24) R = 24;

    const int side = 2 * R + 1;
    const int total = side * side * side;
    int i = g_chestProgress.load();
    if (i >= total || i < 0) i = 0;

    std::vector<BPos> found;
    found.reserve(32);

    
    int budget = 300;
    while (budget-- > 0 && i < total) {
        const int ax = i % side;
        const int ay = (i / side) % side;
        const int az = i / (side * side);
        ++i;
        const BPos p{c.x + ax - R, c.y + ay - R, c.z + az - R};
        if (p.y < -64 || p.y > 400) continue;
        if (getBe(reinterpret_cast<void*>(bs), &p)) {
            found.push_back(p);
            if (found.size() >= 64) break;
        }
    }

    const bool wrapped = (i >= total);
    g_chestProgress.store(wrapped ? 0 : i);

    if (!wrapped && found.empty()) return;

    std::lock_guard<std::mutex> lk(g_chestMtx);
    if (wrapped) g_chestPos.clear();
    for (const auto& p : found) {
        if (g_chestPos.size() >= 256) break;
        bool dup = false;
        for (const auto& q : g_chestPos) {
            if (q.x == p.x && q.y == p.y && q.z == p.z) { dup = true; break; }
        }
        if (!dup) g_chestPos.push_back(p);
    }
    g_chestHits.store(static_cast<unsigned>(g_chestPos.size()));
}







struct DimCtx {
    std::vector<Ent>* out;
    uintptr_t self;
    int added;
    int rejected;
};

 
static bool dim_ent_cb(void* ctxv, void* p) {
    DimCtx* c = reinterpret_cast<DimCtx*>(ctxv);
    const uintptr_t a = reinterpret_cast<uintptr_t>(p);
    if (!a || a == c->self) return true;
    if (!is_actor(a)) { c->rejected++; return true; }

    float x, y, z;
    if (!read_actor_pos(a, x, y, z)) { c->rejected++; return true; }

    if (c->out->size() < 512) {
        
        
        Ent e{};
        e.p = a; e.x = x; e.y = y; e.z = z;
        e.src = 1;                                          
        e.isPlayer = is_player_entity(a);
        c->out->push_back(e);
    }
    c->added++;
    return true;
}

static void dim_refresh_entities() {
    const uintptr_t self = g_player.load();
    if (!self) return;

    using GetDimFn  = uintptr_t (*)(void*);
    using ForEachFn = uintptr_t (*)(void*, void*, void*);
    GetDimFn  getDim  = fn_at<GetDimFn>(rva::FN_ACTOR_GET_DIMENSION);
    ForEachFn forEach = fn_at<ForEachFn>(rva::FN_DIM_FOREACHPLAYER);
    if (!getDim || !forEach) return;

    const uintptr_t dim = getDim(reinterpret_cast<void*>(self));
    if (!dim) { g_dimFails.fetch_add(1); return; }

    std::vector<Ent> found;
    found.reserve(256);
    DimCtx ctx{&found, self, 0, 0};
    forEach(reinterpret_cast<void*>(dim), reinterpret_cast<void*>(dim_ent_cb), &ctx);

    g_dimPlayers.store(ctx.added);
    g_dimSweeps.fetch_add(1);
    if (found.empty()) return;

    std::lock_guard<std::mutex> lk(g_entMtx);

    
    
    
    
    
    
    
    
    
    
    g_ents.erase(std::remove_if(g_ents.begin(), g_ents.end(),
                                [](const Ent& e) { return e.src == 1; }),
                 g_ents.end());

    for (const auto& e : found) {
        if (g_ents.size() >= 512) break;
        bool dup = false;
        for (const auto& q : g_ents) { if (q.p == e.p) { dup = true; break; } }
        if (!dup) g_ents.push_back(e);
    }
    g_entCount.store(static_cast<int>(g_ents.size()));
}











 


static void nudge_forward(double dist, std::atomic<unsigned>& counter) {
    const uintptr_t self = g_player.load();
    if (!self) return;

    float x, y, z, yaw, pitch;
    if (!read_actor_pos(self, x, y, z)) return;
    if (!read_view_angles(self, yaw, pitch)) return;

    const float PI = 3.14159265358979f;
    const float ry = yaw * PI / 180.0f;
    const float d  = static_cast<float>(dist);
    
    tp_abs(self, x - std::sin(ry) * d, y, z + std::cos(ry) * d);
    counter.fetch_add(1);
}

 
static void speed_tick() {
    if (!g_speed.load()) return;
    static uint64_t lastAt = 0;
    const uint64_t now = now_ms();
    if (now - lastAt < 50) return;
    lastAt = now;
    nudge_forward(g_speedDist.load(), g_speedSteps);
}

 
static void sprint_tick() {
    if (!g_sprint.load()) return;
    static uint64_t lastAt = 0;
    const uint64_t now = now_ms();
    if (now - lastAt < 100) return;
    lastAt = now;
    nudge_forward(g_sprintDist.load(), g_sprintSteps);
}

 









static void velocity_tick() {
    static float lx = 0, ly = 0, lz = 0;
    static bool  valid = false;

    const uintptr_t self = g_player.load();
    if (!self || !g_velocity.load()) { valid = false; return; }

    float x, y, z;
    if (!read_vec3(self, 0, x, y, z)) { valid = false; return; }
    if (!valid) { lx = x; ly = y; lz = z; valid = true; return; }

    const float dx = x - lx, dy = y - ly, dz = z - lz;
    const float d2 = dx * dx + dy * dy + dz * dz;

    double th = g_velThresh.load();
    if (th < 0.20) th = 0.20;
    if (th > 1.50) th = 1.50;

    if (d2 > (float)(th * th)) {
        
        
        
        tp_abs(self, lx, ly, lz);
        g_velBlocks.fetch_add(1);
        return;                       
    }
    lx = x; ly = y; lz = z;
}

 



static uintptr_t player_inventory(uintptr_t self) {
    const uintptr_t base = g_libBase.load();
    if (!base || !sane_ptr(self)) return 0;
    const uintptr_t want = base + vt::PLAYERINVENTORY;

    
    
    
    if (g_invOff) {                                     
        const uintptr_t p = mem::rd_hot<uintptr_t>(self + (uintptr_t)g_invOff);
        if (sane_ptr(p) && mem::rd_hot<uintptr_t>(p) == want) return p;
        g_invOff = 0;                                   
    }

    
    
    {
        const uintptr_t p = mem::rd_hot<uintptr_t>(self + (uintptr_t)off::PLAYER_INV_HINT);
        if (sane_ptr(p) && mem::rd_hot<uintptr_t>(p) == want) {
            g_invOff = off::PLAYER_INV_HINT;
            return p;
        }
    }

    
    static uint64_t lastFail = 0;
    const uint64_t now = now_ms();
    if (lastFail && now - lastFail < 3000) return 0;

    
    
    
    
    for (int o = off::PLAYER_BODY_SCAN_LO; o + 8 <= off::PLAYER_BODY_SCAN_HI; o += 8) {
        const uintptr_t p = mem::rd_hot<uintptr_t>(self + (uintptr_t)o);
        if (!sane_ptr(p)) continue;
        if (mem::rd_hot<uintptr_t>(p) == want) { g_invOff = o; lastFail = 0; return p; }
    }
    lastFail = now;
    return 0;
}

 









static void autotool_tick() {
    if (!g_autotool.load()) { g_atSlot.store(-1); return; }
    const uintptr_t self = g_player.load();
    if (!self) return;

    using CarriedFn = uintptr_t (*)(void*);
    using IsBlockFn = bool (*)(const void*);
    using SelectFn  = bool (*)(void*, int, bool);

    CarriedFn carried = fn_at<CarriedFn>(rva::FN_PLAYER_GET_CARRIED);
    IsBlockFn isBlock = fn_at<IsBlockFn>(rva::FN_ITEMSTACK_ISBLOCK);
    SelectFn  sel     = fn_at<SelectFn>(rva::FN_INV_SELECT_SLOT);
    if (!carried || !isBlock || !sel) return;

    const uintptr_t item = carried(reinterpret_cast<void*>(self));
    if (sane_ptr(item) && isBlock(reinterpret_cast<const void*>(item))) {
        g_atSlot.store(-1);                        
        return;
    }

    static uint64_t lastAt = 0;
    const uint64_t now = now_ms();
    if (now - lastAt < 250) return;
    lastAt = now;

    const uintptr_t inv = player_inventory(self);
    if (!inv) return;

    int s = g_atSlot.load();
    if (s < 0 || s > 8) s = 0;
    sel(reinterpret_cast<void*>(inv), s, false);
    g_atSwitches.fetch_add(1);
    g_atSlot.store(s >= 8 ? 0 : s + 1);
}

 









static void xray_scan_step() {
    const uintptr_t self = g_player.load();
    if (!self) return;

    using GetBlockFn = uintptr_t (*)(void*, const void*);
    using IsAirFn    = bool (*)(const void*);
    GetBlockFn getBlock = fn_at<GetBlockFn>(rva::FN_BLOCKSOURCE_GETBLOCK);
    IsAirFn    isAir    = fn_at<IsAirFn>(rva::FN_BLOCK_ISAIR);
    if (!getBlock || !isAir) return;

    const uintptr_t bs = self_blocksource(self);
    if (!bs) return;

    BPos c{0, 0, 0};
    if (!read_blockpos(self, 0, c)) {
        float x, y, z;
        if (!read_actor_pos(self, x, y, z)) return;
        c.x = static_cast<int32_t>(std::floor(x));
        c.y = static_cast<int32_t>(std::floor(y));
        c.z = static_cast<int32_t>(std::floor(z));
    }

    int R = g_xrayRange.load();
    if (R < 2)  R = 2;
    if (R > 16) R = 16;

    const int side  = 2 * R + 1;
    const int total = side * side * side;

    int i = g_xrayProgress.load();
    if (i >= total || i < 0) i = 0;

    
    static uintptr_t sDesc[64];
    static int       sCnt[64];
    static int       sKinds = 0;
    static std::vector<std::pair<uintptr_t, BPos>> sSeen;

    if (i == 0) { sKinds = 0; sSeen.clear(); }

    
    
    int budget = 200;
    while (budget-- > 0 && i < total) {
        const int ax = i % side;
        const int ay = (i / side) % side;
        const int az = i / (side * side);
        ++i;

        const BPos p{ c.x + ax - R, c.y + ay - R, c.z + az - R };
        if (p.y < -64 || p.y > 400) continue;

        const uintptr_t blk = getBlock(reinterpret_cast<void*>(bs), &p);
        if (!sane_ptr(blk)) continue;
        const uintptr_t desc = mem::rd_hot<uintptr_t>(blk + 0x90);
        if (!sane_ptr(desc)) continue;
        if (isAir(reinterpret_cast<const void*>(blk))) continue;

        int k = -1;
        for (int j = 0; j < sKinds; ++j) { if (sDesc[j] == desc) { k = j; break; } }
        if (k < 0) {
            if (sKinds >= 64) continue;
            k = sKinds++;
            sDesc[k] = desc;
            sCnt[k]  = 0;
        }
        ++sCnt[k];
        if (sSeen.size() < 4096) sSeen.emplace_back(desc, p);
    }

    const bool wrapped = (i >= total);
    g_xrayProgress.store(wrapped ? 0 : i);
    if (!wrapped) return;                       

    int maxCnt = 1;
    for (int j = 0; j < sKinds; ++j) if (sCnt[j] > maxCnt) maxCnt = sCnt[j];
    const int cut = std::max(3, (int)(maxCnt * 0.12f));

    std::vector<BPos> rare;
    rare.reserve(256);
    for (const auto& kv : sSeen) {
        int k = -1;
        for (int j = 0; j < sKinds; ++j) { if (sDesc[j] == kv.first) { k = j; break; } }
        if (k < 0 || sCnt[k] > cut) continue;   
        rare.push_back(kv.second);
        if (rare.size() >= 256) break;
    }

    {
        std::lock_guard<std::mutex> lk(g_xrayMtx);
        g_xrayPos.swap(rare);
    }
    g_xrayRare.store(static_cast<unsigned>(g_xrayPos.size()));
    sKinds = 0;
    sSeen.clear();
}

 








static void respawn_tick() {
    static float sx = 0, sy = 0, sz = 0;
    static bool  have = false;
    static uintptr_t lastPtr = 0;

    if (!g_respawn.load()) { have = false; lastPtr = g_player.load(); return; }

    const uintptr_t self = g_player.load();
    if (!self) return;

    float x, y, z;
    if (!read_actor_pos(self, x, y, z)) return;

    const uintptr_t prev = lastPtr;
    lastPtr = self;

    if (!have) { sx = x; sy = y; sz = z; have = true; return; }

    const bool newPlayer = (prev != 0 && prev != self);
    const bool bigDrop   = (y < sy - 40.0f);

    if (newPlayer || bigDrop) {
        
        tp_abs(self, sx, sy, sz);
        g_respawns.fetch_add(1);
        sx = x; sy = y; sz = z;
        return;
    }
    if (y >= sy - 2.0f) { sx = x; sy = y; sz = z; }
}

 
static void net_rate_tick() {
    static uint64_t lastAt = 0;
    const uint64_t now = now_ms();
    if (now - lastAt < 1000) return;
    lastAt = now;

    g_pktPps.store((int)g_pktWindow.exchange(0));
    g_tpsNow.store((int)g_ntWindow.exchange(0));
}



static void scan_loop() {
    LOGI("scan loop up (tid=%d)", (int)::gettid());

    
    
    
    
    
    
    mem::mark_scan_thread();

    
    
    
    ::setpriority(PRIO_PROCESS, 0, 10);

    for (;;) {
        const Base b = game_module();
        if (!b.lo) {
            ::usleep(500 * 1000);
            continue;
        }
        g_libBase.store(b.lo);
        g_libEnd.store(b.hi);

        
        
        
        
        
        
        
        
        static int stableRounds = 0;
        if (stableRounds < 3) ++stableRounds;
        if (stableRounds >= 3) ensure_hooks();

        
        
        
        {
            const uintptr_t self = g_player.load();
            if (self && g_gamemode.load() == 0) {
                const uintptr_t gm = find_gamemode(self);
                if (gm) {
                    g_gamemode.store(gm);
                    LOGI("gamemode ready %p (Player+0x%X)", (void*)gm, g_gmOff.load());
                }
            }
        }

        
        
        
        const bool needEnt = g_esp.load() || g_chest.load() || g_xray.load()
                             || g_kaura.load() || g_hitbox.load();

        
        
        
        
        
        
        
        
        
        const bool needSel = (g_player.load() == 0) || (g_gamemode.load() == 0);

        
        
        
        
        
        
        
        
        
        
        
        
        static int missRounds = 0;
        if (needEnt || g_player.load() || g_gamemode.load()) missRounds = 0;
        else if (missRounds < 1000) ++missRounds;

        if (needSel || !g_selfSeen.load() || needEnt) {
            scan_once();
            int ms;
            if (needEnt) {
                ms = 2000;
            } else if (needSel) {
                
                
                
                
                
                
                
                
                
                
                
                const int step = missRounds / 3;
                ms = step == 0 ? 400
                   : (step == 1 ? 800
                   : (step == 2 ? 1600
                   : (step == 3 ? 3000
                   : (step == 4 ? 5000 : 8000))));
            } else {
                ms = 6000;
            }
            ::usleep((useconds_t)(ms * 1000));
        } else {
            ::usleep(3000 * 1000);
        }
    }
}



static jstring mk(JNIEnv* env, const char* s) {
    return env->NewStringUTF(s == nullptr ? "" : s);
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_onyx_clickgui_feature_Native_nInit(JNIEnv*, jclass) {
    if (!g_threadUp.exchange(true)) {
        try {
            std::thread(scan_loop).detach();
        } catch (...) {
            LOGE("起扫描线程失败");
            g_threadUp.store(false);
            return JNI_FALSE;
        }
    }
    const Base b = game_module();
    if (b.lo) {
        g_libBase.store(b.lo);
        g_libEnd.store(b.hi);
        LOGI("libminecraftpe.so @ %p - %p (size %.1f MB)", (void*)b.lo, (void*)b.hi,
             (double)(b.hi - b.lo) / 1048576.0);
    } else {
        LOGW("init: libminecraftpe.so 尚未载入，等扫描线程重试");
    }
    
    
    
    
    
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_onyx_clickgui_feature_Native_nStatus(JNIEnv* env, jclass) {
    char buf[1600];
    ::snprintf(buf, sizeof(buf),
               "base=%p player=%p svt=0x%X gmp=%p inv=%d ents=%d avt=%u scans=%d seen=%d ms=%d rej=%d self=%c "
               "gmoff=0x%X vm=%d rk=%u pos=%u/%u | "
               "dim:players=%d sweeps=%u fail=%d | "
               "hook=%d hset=0x%02x hsk=%u hf=%u fb=%u/%u fov=%d/%u cam=%u/%u nt=%u pkt=%u | "
               "aura=%d/%u r=%.1f/%dms/m%d/t%d NG=%u mtick=%u | "
               "scaffold=%d/%dms place=%u ok=%u fb=%u NG=%u | "
               "esp=%d n=%u fail=%d | "
               "fly=%d w=%u read=%d | jump=%d/%dms n=%u | "
               "tp=%d m=%d d=%.1f n=%u | hb=%d/t%d %.2fx%.2f n=%u | "
               "break=%d/%u/%u | nuker=%d r=%d | gm=%d w=%u | chest=%d %u/%d%% | "
               "reach=%d/%u+%u r=%.1f | crit=%d/%u | nofall=%d/%u | "
               "vel=%d/%u th=%.2f | spd=%d/%u d=%.2f | sprt=%d/%u d=%.2f | "
               "tool=%d/%u off=0x%x | xray=%d rare=%u r=%d %d%% | respawn=%d/%u | "
               "net=%d/%d pps=%d jit=%dms tps=%d | tags=%d",
               (void*)g_libBase.load(), (void*)g_player.load(),
               
               
               (unsigned)(g_player.load() ? (mem::rd_hot<uintptr_t>(g_player.load()) - g_libBase.load()) : 0),
               (void*)g_gamemode.load(),
               g_invOff,
               (int)g_entCount.load(), g_actorVtCount.load(), (int)g_scans.load(), (int)g_seen.load(),
               (int)g_scanMs.load(), (int)g_rejected.load(),
               g_selfSeen.load() ? 'H' : (g_player.load() ? 'S' : '-'),
               g_gmOff.load(), mem::vm_state(), g_scanKB.load(),
               g_posOk.load(), g_posFail.load(),
               (int)g_dimPlayers.load(), g_dimSweeps.load(), (int)g_dimFails.load(),
               hk::g_count, hook_mask(), g_hookSkipped.load(),
               mem::detail::g_hotFaults.load(),
               g_fbCalls.load(), g_fbSkips.load(),
               (int)g_fovOn.load(), g_fovCalls.load(),
               g_camCalls.load(), g_camSkips.load(),
               g_ntTotal.load(), g_pktTotal.load(),
               (int)g_kaura.load(), g_attacks.load(),
               (double)g_kauraRange.load(), (int)g_kauraDelay.load(), (int)g_kauraMode.load(),
               (int)g_kauraTarget.load(),
               g_kaNoGm.load(),
               g_mainTicks.load(),
               (int)g_scaffold.load(), (int)g_scaffoldDelay.load(),
               g_builds.load(), g_buildOk.load(), g_bpFallback.load(), g_scNoGm.load(),
               (int)g_esp.load(), g_espDrawn.load(), (int)g_espFail.load(),
               (int)g_fly.load(), g_flyWrites.load(), (int)g_flyRead.load(),
               (int)g_autojump.load(), (int)g_jumpDelay.load(), g_jumps.load(),
               (int)g_tpOn.load(), (int)g_tpMode.load(), (double)g_tpDist.load(), g_tpCount.load(),
               (int)g_hitbox.load(), (int)g_hbTarget.load(), (double)g_hbW.load(), (double)g_hbH.load(), g_hbWrites.load(),
               (int)g_autobreak.load(), g_breakOk.load(), g_breaks.load(),
               (int)g_nuker.load(), (int)g_nukerRange.load(),
               (int)g_gametype.load(), g_gmWrites.load(),
               (int)g_chest.load(), g_chestHits.load(), (int)g_chestProgress.load(),
               (int)g_reachOn.load(), g_reachCalls.load(), g_reachRangeCalls.load(), (double)g_reachVal.load(),
               (int)g_critOn.load(), g_critHits.load(),
               (int)g_nofallOn.load(), g_noFallHits.load(),
               (int)g_velocity.load(), g_velBlocks.load(), (double)g_velThresh.load(),
               (int)g_speed.load(), g_speedSteps.load(), (double)g_speedDist.load(),
               (int)g_sprint.load(), g_sprintSteps.load(), (double)g_sprintDist.load(),
               (int)g_autotool.load(), g_atSwitches.load(), g_invOff,
               (int)g_xray.load(), g_xrayRare.load(), (int)g_xrayRange.load(),
               (int)g_xrayProgress.load(),
               (int)g_respawn.load(), g_respawns.load(),
               (int)g_ping.load(), (int)g_tps.load(), (int)g_pktPps.load(), (int)g_pktJitter.load(),
               (int)g_tpsNow.load(),
               (int)g_nametags.load());
    return mk(env, buf);
}

 





JNIEXPORT jstring JNICALL
Java_com_onyx_clickgui_feature_Native_nPlayerPos(JNIEnv* env, jclass) {
    
    uintptr_t self = g_player.load();
    if (!self) {
        const uintptr_t gm = g_gamemode.load();
        if (gm) self = mem::rd_hot<uintptr_t>(gm + (uintptr_t)off::GM_PLAYER);
    }
    if (!self) return mk(env, "");

    BPos pp{0, 0, 0};
    if (read_blockpos(self, 0, pp)) {
        char buf[64];
        ::snprintf(buf, sizeof(buf), "%d / %d / %d", (int)pp.x, (int)pp.y, (int)pp.z);
        return mk(env, buf);
    }

    float x, y, z;
    if (read_vec3(self, 0, x, y, z)) {
        char buf[64];
        ::snprintf(buf, sizeof(buf), "%d / %d / %d",
                   (int)std::floor(x), (int)std::floor(y), (int)std::floor(z));
        return mk(env, buf);
    }
    return mk(env, "");
}

JNIEXPORT jlong JNICALL
Java_com_onyx_clickgui_feature_Native_nGameBase(JNIEnv*, jclass) {    uintptr_t b = g_libBase.load();
    if (!b) {
        const Base m = game_module();
        if (m.lo) {
            g_libBase.store(m.lo);
            g_libEnd.store(m.hi);
            b = m.lo;
        }
    }
    return (jlong)b;
}

JNIEXPORT jboolean JNICALL
Java_com_onyx_clickgui_feature_Native_nAvailable(JNIEnv* env, jclass, jstring name) {
    if (name == nullptr) return JNI_FALSE;
    const char* c = env->GetStringUTFChars(name, nullptr);
    if (c == nullptr) return JNI_FALSE;
    const bool ok = feature_available(c);
    env->ReleaseStringUTFChars(name, c);
    return ok ? JNI_TRUE : JNI_FALSE;
}

 




static int feat_prev(const char* c, int v, bool* known) {
    static std::string names[64];
    static int         vals[64];
    static int         n = 0;
    for (int i = 0; i < n; ++i) {
        if (names[i] == c) {
            *known = true;
            const int p = vals[i];
            vals[i] = v;
            return p;
        }
    }
    *known = false;
    if (n < 64) { names[n] = c; vals[n] = v; ++n; }
    return -1;
}

JNIEXPORT void JNICALL
Java_com_onyx_clickgui_feature_Native_nSetFeature(JNIEnv* env, jclass, jstring name, jboolean on) {
    if (name == nullptr) return;
    const char* c = env->GetStringUTFChars(name, nullptr);
    if (c == nullptr) return;
    const bool v = (on == JNI_TRUE);
    if (::strcmp(c, "killaura") == 0)       g_kaura.store(v);
    else if (::strcmp(c, "scaffold") == 0)  g_scaffold.store(v);
    else if (::strcmp(c, "esp") == 0)       g_esp.store(v);
    else if (::strcmp(c, "fly") == 0)       g_fly.store(v);
    else if (::strcmp(c, "autojump") == 0)  g_autojump.store(v);
    else if (::strcmp(c, "hitbox") == 0)    g_hitbox.store(v);
    else if (::strcmp(c, "autobreak") == 0) g_autobreak.store(v);
    else if (::strcmp(c, "nuker") == 0)     g_nuker.store(v);
    else if (::strcmp(c, "chestesp") == 0) {
        g_chest.store(v);
        if (!v) {
            std::lock_guard<std::mutex> lk(g_chestMtx);
            g_chestPos.clear();
            g_chestHits.store(0);
            g_chestProgress.store(0);
        }
    } else if (::strcmp(c, "teleport") == 0) {
        g_tpOn.store(v);
    } else if (::strcmp(c, "gamemode") == 0) {
        const bool now = (v != 0);
        const bool was = g_gamemodeOn.exchange(now);
        
        
        if (now && !was) g_gmDirty.store(true);
        if (!now)        g_gmDirty.store(false);      
    }
    
    else if (::strcmp(c, "fov") == 0)        g_fovOn.store(v);
    else if (::strcmp(c, "fullbright") == 0) g_fbOn.store(v);
    else if (::strcmp(c, "freecam") == 0)    g_freecamOn.store(v);
    else if (::strcmp(c, "reach") == 0)      g_reachOn.store(v);
    else if (::strcmp(c, "criticals") == 0)  g_critOn.store(v);
    else if (::strcmp(c, "nofall") == 0)     g_nofallOn.store(v);
    else if (::strcmp(c, "ping") == 0)       g_ping.store(v);
    else if (::strcmp(c, "tps") == 0)        g_tps.store(v);
    else if (::strcmp(c, "nametags") == 0)   g_nametags.store(v);
    else if (::strcmp(c, "velocity") == 0)   g_velocity.store(v);
    else if (::strcmp(c, "speed") == 0)      g_speed.store(v);
    else if (::strcmp(c, "sprint") == 0)     g_sprint.store(v);
    else if (::strcmp(c, "respawn") == 0)    g_respawn.store(v);
    else if (::strcmp(c, "autotool") == 0) {
        g_autotool.store(v);
        if (!v) g_atSlot.store(-1);
    } else if (::strcmp(c, "xray") == 0) {
        g_xray.store(v);
        if (!v) {
            g_xrayProgress.store(0);
            std::lock_guard<std::mutex> lk(g_xrayMtx);
            g_xrayPos.clear();
            g_xrayRare.store(0);
        }
    }
    
    
    
    
    bool known = false;
    const int prev = feat_prev(c, (int)v, &known);
    if (!known || prev != (int)v) LOGI("setFeature %s = %d", c, (int)v);
    env->ReleaseStringUTFChars(name, c);
}

JNIEXPORT void JNICALL
Java_com_onyx_clickgui_feature_Native_nSetNum(JNIEnv* env, jclass, jstring name,
                                              jstring key, jdouble v) {
    if (name == nullptr || key == nullptr) return;
    const char* n = env->GetStringUTFChars(name, nullptr);
    const char* k = env->GetStringUTFChars(key, nullptr);
    if (n && k) {
        if (::strcmp(n, "killaura") == 0 && ::strcmp(k, "range") == 0) {
            g_kauraRange.store((double)v);
        } else if (::strcmp(n, "killaura") == 0 && ::strcmp(k, "delay") == 0) {
            g_kauraDelay.store((int)v);
        } else if (::strcmp(n, "scaffold") == 0 && ::strcmp(k, "delay") == 0) {
            g_scaffoldDelay.store((int)v);
        } else if (::strcmp(n, "esp") == 0 && ::strcmp(k, "range") == 0) {
            g_espRange.store((double)v);
        } else if (::strcmp(n, "autojump") == 0 && ::strcmp(k, "delay") == 0) {
            g_jumpDelay.store((int)v);
        } else if (::strcmp(n, "teleport") == 0 && ::strcmp(k, "dist") == 0) {
            g_tpDist.store((double)v);
        } else if (::strcmp(n, "hitbox") == 0 && ::strcmp(k, "width") == 0) {
            g_hbW.store((double)v);
        } else if (::strcmp(n, "hitbox") == 0 && ::strcmp(k, "height") == 0) {
            g_hbH.store((double)v);
        } else if (::strcmp(n, "nuker") == 0 && ::strcmp(k, "range") == 0) {
            g_nukerRange.store((int)v);
        } else if (::strcmp(n, "chestesp") == 0 && ::strcmp(k, "range") == 0) {
            g_chestRange.store((int)v);
        } else if (::strcmp(n, "gamemode") == 0 && ::strcmp(k, "type") == 0) {
            
            
            const int t = (int)v;
            if (g_gametype.load() != t) {
                g_gametype.store(t);
                if (g_gamemodeOn.load()) g_gmDirty.store(true);   
            }
        }
        
        else if (::strcmp(n, "fov") == 0 && ::strcmp(k, "value") == 0) {
            g_fovVal.store((int)v);
        } else if (::strcmp(n, "reach") == 0 && ::strcmp(k, "value") == 0) {
            g_reachVal.store((double)v);
        } else if (::strcmp(n, "velocity") == 0 && ::strcmp(k, "threshold") == 0) {
            g_velThresh.store((double)v);
        } else if (::strcmp(n, "speed") == 0 && ::strcmp(k, "dist") == 0) {
            g_speedDist.store((double)v);
        } else if (::strcmp(n, "sprint") == 0 && ::strcmp(k, "dist") == 0) {
            g_sprintDist.store((double)v);
        } else if (::strcmp(n, "xray") == 0 && ::strcmp(k, "range") == 0) {
            g_xrayRange.store((int)v);
        }
    }
    if (n) env->ReleaseStringUTFChars(name, n);
    if (k) env->ReleaseStringUTFChars(key, k);
}

JNIEXPORT void JNICALL
Java_com_onyx_clickgui_feature_Native_nSetMode(JNIEnv* env, jclass, jstring name,
                                               jstring key, jint idx) {
    if (name == nullptr) return;
    const char* n = env->GetStringUTFChars(name, nullptr);
    const char* k = (key != nullptr) ? env->GetStringUTFChars(key, nullptr) : nullptr;
    if (n) {
        if (::strcmp(n, "killaura") == 0) {
            
            
            if (k && ::strcmp(k, "target") == 0) g_kauraTarget.store((int)idx);
            else                                 g_kauraMode.store((int)idx);
        }
        else if (::strcmp(n, "hitbox") == 0 && k && ::strcmp(k, "target") == 0) {
            g_hbTarget.store((int)idx);
        }
        else if (::strcmp(n, "esp") == 0) g_espMode.store((int)idx);
        else if (::strcmp(n, "teleport") == 0) g_tpMode.store((int)idx);
    }
    if (n) env->ReleaseStringUTFChars(name, n);
    if (k) env->ReleaseStringUTFChars(key, k);
}

 





JNIEXPORT void JNICALL
Java_com_onyx_clickgui_feature_Native_nTick(JNIEnv*, jclass) {
    g_mainTicks.fetch_add(1);

    
    const uint64_t now = now_ms();
    if (!g_nowMs.load()) g_nowMs.store(now);

    
    
    const uintptr_t self = g_player.load();
    g_selfCtx.store(self ? self + (uintptr_t)off::ACTOR_ENTITY_CONTEXT : 0);

    
    
    
    
    if (self) {
        const uintptr_t cur = g_gamemode.load();
        
        
        
        
        
        
        
        if (cur != 0 && !gm_valid(cur, self)) {
            g_gamemode.store(0);          
        }
    }

    
    
    
    {
        static uint64_t lastPos = 0;
        if (self && now - lastPos >= 500) {
            lastPos = now;
            float px, py, pz;
            if (read_actor_pos(self, px, py, pz)) g_posOk.fetch_add(1);
            else                                  g_posFail.fetch_add(1);
        }
    }

    
    killaura_fire();
    scaffold_tick();
    fly_tick();
    autojump_tick();
    teleport_tick();
    hitbox_tick();
    speed_tick();
    sprint_tick();
    velocity_tick();
    respawn_tick();
    
    autobreak_tick();
    nuker_tick();
    gamemode_tick();
    autotool_tick();

    
    static uint64_t lastDim = 0;
    if (now - lastDim >= 500) { lastDim = now; dim_refresh_entities(); }

    
    if (g_chest.load()) {
        static uint64_t lastChest = 0;
        if (now - lastChest >= 100) { lastChest = now; chest_scan_step(); }
    }

    
    if (g_xray.load()) {
        static uint64_t lastXray = 0;
        if (now - lastXray >= 60) { lastXray = now; xray_scan_step(); }
    } else if (g_xrayProgress.load() != 0) {
        g_xrayProgress.store(0);
        std::lock_guard<std::mutex> lk(g_xrayMtx);
        g_xrayPos.clear();
        g_xrayRare.store(0);
    }

    
    net_rate_tick();
}

JNIEXPORT jint JNICALL
Java_com_onyx_clickgui_feature_Native_nMainTicks(JNIEnv*, jclass) {
    return (jint)g_mainTicks.load();
}

 




JNIEXPORT jfloatArray JNICALL
Java_com_onyx_clickgui_feature_Native_nEsp(JNIEnv* env, jclass,
                                           jint sw, jint sh, jfloat fov) {
    if (!g_esp.load() && !g_chest.load() && !g_xray.load()) return nullptr;
    std::vector<float> v;
    v.reserve(256);
    esp_build(v, (float)sw, (float)sh, (float)fov, g_espRange.load());
    if (v.empty()) return nullptr;
    jfloatArray a = env->NewFloatArray((jsize)v.size());
    if (a == nullptr) return nullptr;
    env->SetFloatArrayRegion(a, 0, (jsize)v.size(), v.data());
    return a;
}

 





JNIEXPORT jfloatArray JNICALL
Java_com_onyx_clickgui_feature_Native_nTags(JNIEnv* env, jclass,
                                            jint sw, jint sh, jfloat fov) {
    if (!g_nametags.load()) return nullptr;
    const float fsw = (float)sw, fsh = (float)sh;
    if (fsw < 2.0f || fsh < 2.0f) return nullptr;

    const uintptr_t self = g_player.load();
    if (!sane_ptr(self)) return nullptr;

    float px, py, pz, yaw, pitch;
    if (!read_actor_pos(self, px, py, pz)) return nullptr;
    if (!read_view_angles(self, yaw, pitch)) return nullptr;

    Cam cam;
    cam_build(cam, px, py, pz, yaw, pitch, fsw, fsh, fov);

    std::vector<float> v;
    v.reserve(192);
    int n = 0;

    std::lock_guard<std::mutex> lk(g_entMtx);
    for (const auto& e : g_ents) {
        if (e.p == self) continue;
        float ox, oy;
        
        if (!cam_project(cam, e.x, e.y + 2.0f, e.z, ox, oy)) continue;
        if (ox < 0.0f || oy < 0.0f || ox > fsw || oy > fsh) continue;

        const float dx = e.x - px, dy = e.y - py, dz = e.z - pz;
        const float dist = std::sqrt(dx * dx + dy * dy + dz * dz);
        if (dist > 96.0f) continue;

        v.push_back(ox / fsw);
        v.push_back(oy / fsh);
        v.push_back(dist);
        if (++n >= 64) break;
    }
    if (v.empty()) return nullptr;

    jfloatArray a = env->NewFloatArray((jsize)v.size());
    if (a == nullptr) return nullptr;
    env->SetFloatArrayRegion(a, 0, (jsize)v.size(), v.data());
    return a;
}

 




JNIEXPORT jfloatArray JNICALL
Java_com_onyx_clickgui_feature_Native_nNetStats(JNIEnv* env, jclass) {
    float v[3];
    v[0] = (float)g_pktJitter.load();
    v[1] = (float)g_pktPps.load();
    v[2] = (float)g_tpsNow.load();
    jfloatArray a = env->NewFloatArray(3);
    if (a == nullptr) return nullptr;
    env->SetFloatArrayRegion(a, 0, 3, v);
    return a;
}

}  
