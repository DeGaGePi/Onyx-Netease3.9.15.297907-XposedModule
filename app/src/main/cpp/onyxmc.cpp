// ══════════════════════════════════════════════════════════════════════════
//  onyxmc.cpp —— 网易版《我的世界》Android ARM64 模块 native 侧
//
//  分工（踩过坑之后定下来的，别改回去）：
//    · 扫描线程：只做「堆扫」，找 LocalPlayer / GameMode / 附近实体。
//      2GB 级堆扫很吃 CPU，绝不能放主线程。
//    · 主线程 tick（Java Handler 每 50ms 调 nTick）：只做「选目标 + 开火」。
//      GameMode::attack 内部是「构造闭包 → 丢任务队列」，只能在游戏主线程调，
//      从后台线程调必踩线程局部状态（UWP 侧同款坑）。
//
//  一切都要「先校验再解引用」，读不到就放弃 —— 宁可功能不生效，也绝不把游戏带崩。
// ══════════════════════════════════════════════════════════════════════════

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
#include <sys/resource.h>   // setpriority —— 扫描线程降级，别跟 Houdini 渲染抢 CPU

#include "mem.hpp"
#include "offsets.hpp"

using namespace mem;

// ═══════════════════════════════════ 状态 ═══════════════════════════════════

static std::atomic<uintptr_t> g_libBase{0};
static std::atomic<uintptr_t> g_libEnd{0};
static std::atomic<uintptr_t> g_player{0};
static std::atomic<uintptr_t> g_gamemode{0};
static std::atomic<uintptr_t> g_selfCtx{0};   // = g_player + off::ACTOR_ENTITY_CONTEXT
static std::atomic<bool>      g_selfSeen{false};  // normalTick hook 是否已经给过 self

static std::atomic<int>  g_scans{0};
static std::atomic<int>  g_scanMs{-1};
static std::atomic<int>  g_rejected{0};
static std::atomic<int>  g_entCount{0};
static std::atomic<unsigned> g_scanKB{0};     // 上一轮真正读到多少 KB（诊断：0 = 读路径全废）

static std::atomic<bool> g_kaura{false};
static std::atomic<bool> g_scaffold{false};
static std::atomic<double> g_kauraRange{3.5};
static std::atomic<int>  g_kauraMode{0};      // 0 Single / 1 Multi
static std::atomic<int>  g_kauraDelay{100};   // ms，两次攻击的最小间隔
// ★★ 2.4.5：Target 下拉 —— 0 Living(只打生物) / 1 Players(只打玩家) / 2 Both(全打)
//   默认 2 = 与 Java 侧 `.en("Target", {"Living","Players","Both"}, 2)` 对齐，
//   也等于老行为（不过滤）。FeatureBus 每次状态变化都会全量下发，这里只是兜底。
static std::atomic<int>  g_kauraTarget{2};

static std::atomic<int>  g_scaffoldDelay{100};  // ms，两次放置的最小间隔
// ★ 2.4.2 诊断：因「GameMode 还没探到」而整条跳过的次数。
//   用户开了功能没反应时，看状态串里的 NG 就能区分是"没就绪（等）"还是"调了但游戏没执行"。
// ★ 2.4.4 诊断：主线程位置读取成功率（Teleport / Speed / Scaffold / ESP 全靠这条链）
static std::atomic<unsigned> g_posOk{0};
static std::atomic<unsigned> g_posFail{0};
static std::atomic<unsigned> g_kaNoGm{0};
static std::atomic<unsigned> g_scNoGm{0};
static std::atomic<unsigned> g_builds{0};       // scaffold 调用 buildBlock 次数
static std::atomic<unsigned> g_buildOk{0};      // 其中返回 true 的次数
static std::atomic<unsigned> g_bpFallback{0};   // BlockPos 读不到、退回 Vec3 取整的次数

static std::atomic<unsigned> g_attacks{0};
static std::atomic<unsigned> g_mainTicks{0};
static std::atomic<int>  g_seen{0};
static std::atomic<bool> g_threadUp{false};

// ── ESP ──
static std::atomic<bool>   g_esp{false};
static std::atomic<double> g_espRange{32.0};
static std::atomic<int>    g_espMode{0};        // 0 Box / 1 Outline
static std::atomic<bool>   g_espTeam{false};
static std::atomic<unsigned> g_espDrawn{0};
static std::atomic<int>    g_espFail{0};        // 位置/朝向读失败次数（诊断用）

struct Ent {
    uintptr_t p;
    float x, y, z;
    bool  isPlayer;      // ★★ 2.4.5：KillAura / Hitbox 的 Target 下拉按它分流
    int   src;           // ★★ 2.4.8：0=堆扫（scan_once 整体重建）1=dim 回调（每轮重建）
};

/** 基岩版 BlockPos = 3 × int32 */
struct BPos {
    int32_t x, y, z;
};

static std::mutex g_entMtx;
static std::vector<Ent> g_ents;

// ══════════════════ 新增功能状态（第二批：由已验证函数驱动） ══════════════════

// ── Fly ── LocalPlayer::isFlying(0xDC34D50) / Abilities::setAbility(0xDC2B6C8)
static std::atomic<bool>     g_fly{false};
static std::atomic<unsigned> g_flyWrites{0};
static std::atomic<int>      g_flyRead{-1};     // -1 未知 / 0 关 / 1 开（调完 isFlying 回读自检）

// ── Auto Jump ── Actor::isOnGround(0xDC216B4) + Mob::jumpFromGround(0xCEF9270)
static std::atomic<bool>     g_autojump{false};
static std::atomic<int>      g_jumpDelay{300};
static std::atomic<unsigned> g_jumps{0};

// ── Teleport ── Actor::teleportTo(0xDC31E84)
//   ⚠ 只做「同区块内」的位移：a4/a5 语义未定案，跨区块可能落错地方。
static std::atomic<bool>     g_tpOn{false};
static std::atomic<int>      g_tpMode{0};       // 0 = 上升 / 1 = 前移
static std::atomic<double>   g_tpDist{4.0};
static std::atomic<unsigned> g_tpCount{0};   // 瞬移次数（别跟 TPS 的开关 g_tps 混）

// ── Hitbox ── Actor::setSize(0xD8E8CB8)
static std::atomic<bool>     g_hitbox{false};
static std::atomic<double>   g_hbW{0.9};
static std::atomic<double>   g_hbH{2.2};
static std::atomic<unsigned> g_hbWrites{0};
// ★★ 2.4.5：Target 下拉 —— 0 Living(只对生物) / 1 Players(只对玩家) / 2 Both(都生效)
//   语义是「**谁的判定框变大**」：默认 Both = 只改自己（本地玩家）的箱子，
//   这是老行为；选 Living/Players 时改成「改所有匹配目标实体」——
//   注意：改别人的碰撞箱是客户端本地效果，只影响你本机的射线判定（打得更准），
//   不会同步到服务端。这正是 Hitbox 类功能的常规用法。
static std::atomic<int>      g_hbTarget{0};

// ── Auto Break / Nuker ── destroyBlock(0xDAC3388) + getBlockSource(0xD8DDE34) + getBlock(0xDD7B3FC)
static std::atomic<bool>     g_autobreak{false};
static std::atomic<bool>     g_nuker{false};
static std::atomic<int>      g_nukerRange{3};
static std::atomic<unsigned> g_breaks{0};
static std::atomic<unsigned> g_breakOk{0};
static int                   g_nukerCursor = 0;   // 轮转游标，每 tick 只处理一小批

// ── Game Mode ── Player::setPlayerGameType(0xDC4F3B0)
static std::atomic<int>      g_gametype{-1};      // -1 = 不动作；0/1/2 = 生存/创造/冒险
// ★★ 2.4.4：Game Mode 的模块开关。用户反馈「开关没打开，只在下拉里改 Type 就生效了」——
//   因为旧代码在 setNum("gamemode","type") 里无条件 `g_gmDirty=true`，完全绕过了模块开关。
//   Type 只是"改成哪个模式"的参数，**开关才是执行意图**，必须门控。
static std::atomic<bool>     g_gamemodeOn{false};
static std::atomic<unsigned> g_gmWrites{0};

// ── Chest ESP ── BlockSource::getBlockEntity(0xDD7B660)
static std::atomic<bool>     g_chest{false};
static std::atomic<int>      g_chestRange{16};
static std::atomic<unsigned> g_chestHits{0};
static std::atomic<int>      g_chestProgress{0};
static std::mutex            g_chestMtx;
static std::vector<BPos>     g_chestPos;

// ── 实体枚举（Dimension::forEachPlayer 0xE72E390）──
static std::atomic<int>      g_dimPlayers{0};
static std::atomic<int>      g_dimFails{0};
static std::atomic<unsigned> g_dimSweeps{0};

// ══════════════ 第三批：AArch64 inline hook 引擎 + 7 个 Hook 点 ══════════════
//
//  Hook 目标全部取自「垃圾橘子」已经验证过的 Hook 表（12 个地址 Hook / 11 个
//  唯一游戏 RVA）+《函数合集》里的 33 函数表 —— 每个地址都是实测的函数起点，
//  不是靠特征码猜的。
//
//  为什么要 inline hook 而不是改虚表槽：
//    · 虚表在 .rodata / RELRO 段，改它要动模块的只读数据；
//    · 而且这些函数是**非虚**的（getFov / getAttackRangeSqr / isOnGround），
//      根本没有槽位可改。
//    · inline hook 只把目标所在**页**临时改成可写，写完立刻改回 RX ——
//      不给新映射加 PROT_EXEC，绕开 Android 10+ 的 execmem 限制。
//
//  ★ 装不上也不会崩：install() 任何一步失败就返回 nullptr，对应功能只是没效果，
//    nStatus 里的 `hook=n/7` 一眼就能看出装了几个。

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

/** 这条 AArch64 指令是不是 PC 相对寻址 —— 是的话原样搬到 trampoline 就错位 */
static bool pcrel(uint32_t in) {
    if ((in & 0xFC000000u) == 0x14000000u) return true;   // B
    if ((in & 0xFC000000u) == 0x94000000u) return true;   // BL
    if ((in & 0xFF000010u) == 0x54000000u) return true;   // B.cond
    if ((in & 0x7E000000u) == 0x34000000u) return true;   // CBZ / CBNZ
    if ((in & 0x7E000000u) == 0x36000000u) return true;   // TBZ / TBNZ
    if ((in & 0x1F000000u) == 0x10000000u) return true;   // ADR / ADRP
    if ((in & 0x3B000000u) == 0x18000000u) return true;   // LDR (literal)
    if ((in & 0x3B000000u) == 0x98000000u) return true;   // LDRSW (literal)
    if ((in & 0xFF000000u) == 0xD8000000u) return true;   // PRFM (literal)
    return false;
}

/** 符号扩展 bits 位有符号数 */
static inline int64_t sx(uint64_t v, int bits) {
    const uint64_t m = 1ull << (bits - 1);
    return (int64_t)((v ^ m) - m);
}

static constexpr uint32_t NOP_I    = 0xD503201Fu;
static constexpr uint32_t LDRX16_8 = 0x58000050u;   // LDR X16, #8
static constexpr uint32_t BRX16    = 0xD61F0200u;   // BR  X16
static constexpr uint32_t BLRX16   = 0xD63F0200u;   // BLR X16
static constexpr int      STUB     = 4;             // LDR / BR / .quad = 4 槽
static constexpr int      MAX_OUT  = 26;            // 最坏 4 条全是「取反+桩」

/**
 * 重定位被打断的 4 条指令。
 *
 * ★ 为什么必须重定位而不能「往后多抄几条躲开」：
 *   16 字节补丁正好盖掉 4 条指令。如果第 3 条就是条件分支（实测
 *   GameMode::getAttackRangeSqr @0xDAC72F0 的 +12 就是 B.LE），那么无论
 *   往后抄到第 8 条还是第 16 条，那条分支都还在被盖的范围内 —— 旧算法
 *   必然放弃这次 hook，功能静默失效。
 *
 * 逐条改写的规则（改完都不再依赖自身地址）：
 *   普通指令        → 原样搬运（1 槽）
 *   B / BL          → LDR X16,#8 / BR|BLR X16 / .quad 目标   （4 槽）
 *   B.cond / CBZ    → 条件取反跳出 + 上面那个绝对桩            （5 槽）
 *   CBNZ / TBZ/TBNZ
 *   ADR / ADRP /    → 不搬，直接放弃这个 hook（宁可装不上，也不改错反被坑）
 *   LDR(literal) /
 *   PRFM(literal)
 *
 * 绝对桩走 LDR/BR 而非直接 B，所以**不受 ±128MB 距离限制**。
 * .quad 一律对齐到 8 字节边界（LDR literal 的取数地址）。
 *
 * 返回写入 out[] 的槽数；0 = 放弃。
 */
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

        if ((in & 0xFF000010u) == 0x54000000u) {              // B.cond
            to   = (uintptr_t)((int64_t)s + sx((in >> 5) & 0x7FFFFull, 19) * 4);
            inv  = 0x54000000u | (5u << 5) | ((in & 0xFu) ^ 1u);
            cond = true;
        } else if ((in & 0x7E000000u) == 0x34000000u) {        // CBZ / CBNZ
            to   = (uintptr_t)((int64_t)s + sx((in >> 5) & 0x7FFFFull, 19) * 4);
            inv  = ((in ^ 0x01000000u) & ~0x00FFFFE0u) | (5u << 5);
            cond = true;
        } else if ((in & 0x7E000000u) == 0x36000000u) {        // TBZ / TBNZ
            to   = (uintptr_t)((int64_t)s + sx((in >> 5) & 0x3FFFull, 14) * 4);
            inv  = ((in ^ 0x01000000u) & ~0x0007FFE0u) | (5u << 5);
            cond = true;
        } else if ((in & 0xFC000000u) == 0x14000000u ||        // B
                   (in & 0xFC000000u) == 0x94000000u) {        // BL
            to = (uintptr_t)((int64_t)s + sx(in & 0x03FFFFFFull, 26) * 4);
        } else {
            return 0;                                          // ADR/ADRP/LDR(lit)… 不硬来
        }

        const bool isBl = (in & 0xFC000000u) == 0x94000000u;

        if (cond) {
            // ★ 让 inv 落**奇数**槽，这样紧跟其后的 LDR X16,#8 落偶数槽，
            //   桩里的 .quad 才是 8 字节对齐（LDR literal 的取数地址）。
            if ((n & 1) == 0) {
                if (n + 1 > MAX_OUT) return 0;
                out[n++] = NOP_I;
            }
            if (n + 1 + STUB > MAX_OUT) return 0;
            out[n++] = inv;                                    // imm=5 → 跳过 1+STUB
        } else if (n & 1) {
            if (n + 1 > MAX_OUT) return 0;
            out[n++] = NOP_I;                                  // 同上，LDR 要落偶数槽
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

/** 拿一块内存放 trampoline：先试 RWX，被 W^X 拦了退成 RW（调用方写完再 seal） */
static void* exec_mem(size_t sz, bool& rwx) {
    void* m = ::mmap(nullptr, sz, PROT_READ | PROT_WRITE | PROT_EXEC,
                     MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (m != MAP_FAILED) { rwx = true; return m; }
    m = ::mmap(nullptr, sz, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    rwx = false;
    return (m == MAP_FAILED) ? nullptr : m;
}

/**
 * 装一个 inline hook，返回 trampoline（拿它调原函数）；失败返回 nullptr。
 *
 * 打进去的补丁（16 字节，绝对跳转，不吃 ±128MB 距离限制）：
 *      LDR X16, #8       58000050
 *      BR  X16           D61F0200
 *      .quad replacement
 * trampoline：
 *      [被盖掉的前 4 条指令，重定位后的版本]
 *      LDR X16, #8 / BR X16
 *      .quad target + 16         跳回去接着执行被打断的那条
 */
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
    if (n & 1) out[n++] = NOP_I;               // 尾桩的 .quad 也要 8 字节对齐

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
    if (!rwx) ::mprotect(t, tsz, PROT_READ | PROT_EXEC);   // 变不成 RX 也留着，不致命

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

/** 这个地址是不是已经装过（懒装载用，避免重复打补丁） */
static bool installed(uintptr_t target) {
    for (int i = 0; i < g_count; ++i) {
        if (g_entries[i].target == target) return true;
    }
    return false;
}

}  // namespace hk

// ═══════════════════════════════════════════════════════════════════════════
//  ★★ 寄存器保真转发 —— 2.3.1 黑屏的根因就在这，别改回去
//
//  AArch64 AAPCS64 规定：
//    · x0–x7 = 前 8 个整型/指针参数
//    · v0–v7 = 前 8 个浮点参数（float 只占低 32 位）
//
//  旧代码把每个 wrapper 都声明成「真实用到的那几个参数」（比如 setupFog
//  只写了 3 个），于是编译器把 x4–x7 当普通 caller-saved 寄存器随便用掉。
//  wrapper 转调原函数时，setupFog / getFov 的第 4、5 个参数就是**垃圾**，
//  雾参数与视野角错乱 → 画面全黑，几秒后连渲染线程一起带崩。
//
//  修法：wrapper 一律声明成「8 个 u64 + 8 个 double」共 16 个参数，原样转发。
//  16 个参数正好全落在 x0–x7 / v0–v7 里，**一个寄存器都不丢**；而且我们只
//  做「原样传递」不解释其值，所以哪怕某个槽实际是 float/指针/引用，
//  位模式也能 100% 保真。
//
//  返回类型要按真实函数选，因为浮点返回走 s0/v0、整型/指针走 x0：
//    Orig8F = 返回 float      → getFov / rangeSqr
//    Orig8B = 返回 bool       → onGround（实际也是 x0/w0）
//    Orig8U = 返回 void/其它  → setupFog / camAvoid / normalTick / send
// ═══════════════════════════════════════════════════════════════════════════

using A8 = uint64_t;
using D8 = double;
#define ONYX_A8 D8 d0, D8 d1, D8 d2, D8 d3, D8 d4, D8 d5, D8 d6, D8 d7

using Orig8F = float    (*)(A8,A8,A8,A8,A8,A8,A8,A8, ONYX_A8);
using Orig8B = bool     (*)(A8,A8,A8,A8,A8,A8,A8,A8, ONYX_A8);
using Orig8U = uint64_t (*)(A8,A8,A8,A8,A8,A8,A8,A8, ONYX_A8);
#define ONYX_A0 A8 a0, A8 a1, A8 a2, A8 a3, A8 a4, A8 a5, A8 a6, A8 a7
#define ONYX_FWD a0,a1,a2,a3,a4,a5,a6,a7, d0,d1,d2,d3,d4,d5,d6,d7

// ── Hook 1 ── LevelRendererPlayer::getFov  0x7BA80A4  →  FOV Changer ─────────
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

// ── Hook 2 ── LevelRendererCamera::setupFog  0x7B86B94  →  Full Bright ──────
//    直接**不设雾**就是全亮（原版夜里/洞里那片黑就是雾渲染出来的）。
static Orig8U                g_origSetupFog = nullptr;
static std::atomic<bool>     g_fbOn{false};
static std::atomic<unsigned> g_fbCalls{0};
static std::atomic<unsigned> g_fbSkips{0};

static void hk_setupFog(ONYX_A0, ONYX_A8) {
    g_fbCalls.fetch_add(1);
    if (g_fbOn.load()) { g_fbSkips.fetch_add(1); return; }   // 不设雾 = 全亮
    if (g_origSetupFog) g_origSetupFog(ONYX_FWD);
}

// ── Hook 3 ── CameraAvoidanceSystem::tick adapter  0x81421A0  →  Freecam ────
//    这个系统负责「相机被方块挡住时把镜头推近」——跳过它就等于相机能穿墙。
static Orig8U                g_origCamAvoid = nullptr;
static std::atomic<bool>     g_freecamOn{false};
static std::atomic<unsigned> g_camCalls{0};
static std::atomic<unsigned> g_camSkips{0};

static void hk_camAvoid(ONYX_A0, ONYX_A8) {
    g_camCalls.fetch_add(1);
    if (g_freecamOn.load()) { g_camSkips.fetch_add(1); return; }  // 跳过遮挡规避 = 相机能穿墙
    if (g_origCamAvoid) g_origCamAvoid(ONYX_FWD);
}

// ── Hook 4 ── LoopbackPacketSender::sendToServer  0xA7878A4  →  Ping 数据源 ─
//    ★ 客户端能观测到的只有「发包节奏」。真正的 RTT 要看收包路径，那条路径
//      在这一版没有可用地址，所以这里给的是**包速率 + 相邻包间隔抖动**，
//      面板上如实标成 NET/Δ。不编造一个假的 ms 数。
static Orig8U                g_origSend = nullptr;
static std::atomic<bool>     g_ping{false};       // Ping 功能开关（hook 4 的数据源）
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

// ── Hook 5 ── LocalPlayer::normalTick  0x74C79FC  →  self 来源 + TPS ────────
//    ★ 第一参就是这个 LocalPlayer 自己。这是**最可靠的 self 来源**：
//      堆扫在 ART GC / Houdini 下既慢又会踩失效映射（上一版就是崩在这），
//      而这里天然拿得到、只跑在游戏主线程、零线程竞争。
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

// ── Hook 6 ── Player::getAttackRangeSqr  0xDAC72F0  →  Reach ───────────────
//    ★ 注意是 **Player::** 不是 GameMode::（前一版注释写错了）。
//      反汇编：`LDR X0,[X0,#8]; LDR S0,[X0,#0x18B4]; ...` → 先解引用 Player+8。
//    游戏判定命中用的是「距离平方 <= 攻击距离平方」，所以把这个 getter 顶大
//    就等于延长了判定距离（服务端另算，这是客户端侧的判定）。
//    ⚠ 实机证据：这个函数**整库只有 1 个代码调用点**（0xEDEEC40）。
//      用户实测「Reach 只增加挖方块/放方块距离、攻击距离没变」——
//      说明那 1 个调用点走的是方块交互路径。实体攻击走 getAttackRange(见 Hook 6b)。
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

// ── Hook 6b ── Player::getAttackRange  0xDAC72BC  →  Reach（★ 主路径，2.4.5 新增）──
//    ★★ 这是 Reach「加了没用」的答案：本库对这个函数有 **12 个**调用点
//       （getAttackRangeSqr 只有 1 个），实体攻击的射程判定几乎都在这儿。
//    反汇编（size 0x34，逐条）：
//        LDR  X0, [X0,#8]        ; ★ 是**解引用**：X0 = *(Player + 8)（实体上下文数据）
//        LDR  S0, [X0,#0x18B4]   ; 覆盖值 float —— 正是 off::OFF_PLAYER_ATTACK_RANGE
//        FCMP S0, #0.0
//        B.GT ret                ; >0 直接返回（游戏自己的「额外距离」机制）
//        否则 BL sub_D91045C → FCSEL 7.0 / 12.0
//    ⇒ 直接顶掉返回值，生存/创造一律按 Reach 值算。
static Orig8F                g_origRange = nullptr;
static std::atomic<unsigned> g_reachRangeCalls{0};   // ★ 0xDAC72BC 被调次数（实体攻击路径）

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

// ── Hook 7 ── Actor::isOnGround(EntityContext*)  0xDC216B4  →  NoFall / Crit ─
//    ★ 只对**本地玩家自己的 EntityContext** 下手（ctx == self+0x08），
//      不碰别的实体 —— 否则会把整个物理系统带歪。
//    · No Fall : 恒返回 true → 客户端累积的摔落距离每 tick 被清零 → 不摔伤
//    · Criticals: 攻击窗口内返回 false → 这一击按"下落中"判定（暴击）
static Orig8B                g_origOnGround = nullptr;
static std::atomic<bool>      g_nofallOn{false};
static std::atomic<bool>      g_critOn{false};
static std::atomic<uint64_t>  g_critUntil{0};
static std::atomic<unsigned>  g_noFallHits{0};
static std::atomic<unsigned>  g_critHits{0};

static bool hk_onGround(ONYX_A0, ONYX_A8) {
    const uintptr_t c = (uintptr_t)a0;          // 第一参就是 EntityContext*
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

/** 因「目标地址不是可执行代码」而被跳过的 hook 数（>0 说明 .so 版本不匹配） */
static std::atomic<unsigned> g_hookSkipped{0};

/**
 * 装 inline hook 前的最后一道闸。
 *
 * 版本对不上时，预置 RVA 会指到数据段或空洞 —— 往那儿 mprotect(RWX) 再改字节
 * 是**必炸**的。这里只做两条最保守的检查（落点在模块内 + 不是空白内存），
 * 不猜指令语义，所以不会误伤：
 *   · 同版本 .so → 目标必然在模块内且字节非零 → 照装不误；
 *   · 异版本 .so → 大概率落空 → 跳过并记数。宁可少一个 hook，也不要崩。
 */
static bool pl_is_code(uintptr_t addr) {
    const uintptr_t b = g_libBase.load();
    const uintptr_t e = g_libEnd.load();
    if (!b || !e || addr < b || addr >= e) return false;
    uint32_t w = 0;
    if (!mem::safe_copy(&w, addr, sizeof(w))) return false;
    return w != 0u && w != 0xFFFFFFFFu;
}

/**
 * 懒装载（必须在 g_libBase 就绪之后），而且**按需装**。
 *
 * ★ 上一版是一上来就把 7 个全装上。问题是那 7 个 wrapper 的签名全靠猜，
 *   转调原函数时丢寄存器参数 → setupFog / getFov 拿到垃圾 → 全屏黑。
 *   现在：
 *     · normalTick 常装 —— 它是 self 的来源，只有 1 个参数，转发绝对安全；
 *     · 其余的「哪个功能开着才装哪个」。默认全关 = 游戏里只有一个 hook，
 *       风险面从 7 个缩到 1 个。
 *   已经装上去的**不卸载**（wrapper 里 feature off 时会原样转发），
 *   所以反复开关功能不会重复打补丁。
 */
static void ensure_hooks() {
    const uintptr_t base = g_libBase.load();
    if (!base) return;

    // ★★★ 2.4.5：**全部改回「常装」** —— 这是 FOV「之前能用、突然用不了」的根因。
    //
    //   2.4.2 为了缩小风险面，把 hook 改成按需装载（`功能开着才打补丁`）。
    //   那套在 Houdini 上是错的：
    //     · MuMu 用 Houdini 跑 ARM64 guest，**热函数会被翻译成 x86_64 并缓存**；
    //     · 缓存生效后，`mprotect(RWX) + 改 guest 字节 + __builtin___clear_cache`
    //       **不会让翻译层的缓存失效**；
    //     · 结果：hook 装上了、不崩、不报错、hk::installed() 返回 true，
    //       但那段代码**永远不会执行**（g_origGetFov 拿到了，hk_getFov 从没被调过）。
    //   2.3.x 是一上来全装，函数还是冷的，所以 FOV 能用；
    //   2.4.2~2.4.4 按需装 → 用户开 FOV 时游戏已经跑了几分钟 → 必然失效。
    //
    //   ⇒ 一律在模块加载后**第一时间**装。已装的不重复打补丁；
    //     wrapper 内部 `if (!flag) 原样转发`，功能开关依然立即生效。
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

/** 哪些 hook 已经装上（状态串里用 hset=0x.. 一眼看）
 *   0x01 normalTick · 0x02 getFov · 0x04 setupFog · 0x08 camAvoid
 *   0x10 sendToServer · 0x20 rangeSqr · 0x40 isOnGround · 0x80 getAttackRange */
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

// ── 第三批功能状态 ──
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
static int                   g_invOff = 0;        // 动态探到的 PlayerInventory 偏移

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

// ═══════════════════════════════ 基础读取 ═══════════════════════════════════

static inline bool in_module(uintptr_t p) {
    const uintptr_t b = g_libBase.load();
    return b != 0 && p >= b && p < g_libEnd.load();
}

/**
 * Actor 的浮点位置 —— 唯一权威链路（★本次 IDA 定案）：
 *
 *   Actor 虚表[17] = sub_D8E96F0 : `return **(float **)(a1 + 648);`   // 648 = 0x288
 *   sub_D8E1C5C(a1)            : `return *(_QWORD *)(a1 + 648);`
 *   Player::teleportTo / GameMode::buildBlock 内部都用 sub_D8E1C5C 取位置
 *   ⇒ `*(actor + 0x288)` 是 **float***，解引用就是 X，+4 = Y，+8 = Z。
 *
 * ⚠ 旧实现读 `0x218`（UWP 旧偏移）**永远读不到**，这是 ESP/KillAura/Scaffold/
 *   Speed/Teleport 全体静默失效的根因（实机 espFail=157751）。
 *
 * ★★ hot 参数决定走哪条读路径（Houdini 下 syscall ~145us，进程内 memcpy 是纳秒级）：
 *     hot = true  → 主线程热路径。地址是从**已知对象**里顺出来的（actor → *(actor+0x288)），
 *                   生命周期内不会消失，走进程内 memcpy 安全且快三个数量级。
 *     hot = false → 堆扫路径。actor 是从影子缓冲里**猜**出来的野地址，快照可能已过期，
 *                   必须走 process_vm_readv 让内核做检查（读不到返回 EFAULT，零信号风险）。
 */
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
    // MC 世界 y 的合理区间（含 mod 空间）；xyz 全 0 且非原点附近的情况交给上层
    if (v[1] < -1000.0f || v[1] > 4000.0f) return false;

    x = v[0]; y = v[1]; z = v[2];
    return true;
}

/** 主线程调用（对已知对象读位置）—— 走进程内 memcpy */
static inline bool read_actor_pos(uintptr_t actor, float& x, float& y, float& z) {
    return read_actor_pos_ex(actor, x, y, z, true);
}

/** 堆扫调用（地址是猜的）—— 走 process_vm_readv，零信号风险 */
static inline bool read_actor_pos_safe(uintptr_t actor, float& x, float& y, float& z) {
    return read_actor_pos_ex(actor, x, y, z, false);
}

/** 兼容旧调用点：poff 参数已无意义（位置只有这一条链路），一律走 read_actor_pos */
static bool read_vec3(uintptr_t actor, int /*poff*/, float& x, float& y, float& z) {
    return read_actor_pos(actor, x, y, z);
}

/** 读 Actor 的 AABB（6 float：minX,minY,minZ,maxX,maxY,maxZ） */
static bool read_aabb(uintptr_t actor, float out[6]) {
    if (!sane_ptr(actor)) return false;
    const uintptr_t ap = rd<uintptr_t>(actor + off::ACTOR_AABB);
    if (!sane_ptr(ap) || !readable(ap, 24)) return false;
    for (int i = 0; i < 6; ++i) {
        out[i] = rd<float>(ap + i * 4);
        if (!sane_float(out[i])) return false;
    }
    if (!(out[0] <= out[3] && out[1] <= out[4] && out[2] <= out[5])) return false;
    // 尺寸合理性：任何一维超过 32 格都不是正常实体
    if (out[3] - out[0] > 32.0f) return false;
    if (out[4] - out[1] > 32.0f) return false;
    if (out[5] - out[2] > 32.0f) return false;
    return true;
}

/** 浮点位置 → 所在方块坐标（floor）。原来的 read_blockpos 走 0x288 当整数读，是错的。 */
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

/** 保留旧名字，内部改走浮点→floor */
static bool read_blockpos(uintptr_t actor, int /*poff*/, BPos& out) {
    return read_feet_block(actor, out);
}

/**
 * 对象是不是 Actor 系实体 —— 防止把野指针丢进 attack 的关键闸门。
 *
 * ★ 不再用「虚表白名单」：RemotePlayer / Monster / Animal / ItemEntity …
 *   每个子类都有自己的虚表和 RTTI 名字，白名单必然漏 —— 远程玩家就是这么被漏掉的
 *   （症状：Dimension::forEachPlayer 回调里 added 恒为 0）。
 *   改用 Itanium RTTI 的**结构特征 + 位置链**，对所有 Actor 子类一律成立：
 *     ① 首字是模块内的指针（vptr）
 *     ② vptr-0x10 = offset-to-top，正常对象必须为 0
 *     ③ vptr-0x08 = typeinfo 指针，也必须在模块内
 *     ④ `actor + 0x288` 的位置链读得出来且数值合理
 *   普通数据几乎不可能同时满足 ①②③；④ 再保证它真是实体而不是碰巧撞上的结构。
 */
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

/** 主线程用（对象来自游戏正规入口） */
static inline bool is_actor(uintptr_t o) { return is_actor_ex(o, true); }

/** 堆扫用（对象是从影子缓冲里猜出来的野地址） */
static inline bool is_actor_safe(uintptr_t o) { return is_actor_ex(o, false); }

/** 只判虚表结构，不读位置 —— 需要「便宜的类型闸门」时用这个 */
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

// ═══════════════ Actor 分类：玩家 / 生物（给 KillAura 与 Hitbox 的 Target 用） ═══════════════
//
//  ★ 不用虚表白名单 —— RemotePlayer / ServerPlayer / 各种 Mob 子类各有一张虚表，
//    白名单必漏（memory 里已经踩过一次）。
//
//  判据用 **双向往返**：`Player + 0xA78 = GameMode*`，而 `GameMode + 0x08 = Player*`。
//    · `Player+0xA78` 有两个独立证据：
//        ① 垃圾橘子方法表 `Player::getGameMode`；IDA 里它被编成一条 8 字节 getter
//           `sub_635C634: LDR X0,[X0,#0xA78]; RET`（0x635C634，整函数只有这条指令）
//        ② Windows 1.21.120 定位报告：`0x142B04F0B: _attack(*(player+0xA78), actor, 0)`
//    · `GameMode+0x08 = Player*` 是构造函数实锤（见 offsets.hpp 里 GAMEMODE 的注释）。
//
//  ⇒ 一个对象是玩家 ⟺ `*(*(e+0xA78)+8) == e`。任何 Player 子类都成立，
//    随机数据要同时满足「+0xA78 是个合法对象指针」+「那个对象 +8 反指回来」，
//    概率≈0。而且只读两个 qword，hot 版纳秒级。

/** 这个 Object 是不是玩家（含 LocalPlayer / RemotePlayer / ServerPlayer / 任何子类） */
static inline bool is_player_entity(uintptr_t e) {
    if (!sane_ptr(e)) return false;
    const uintptr_t gm = mem::rd_hot<uintptr_t>(e + (uintptr_t)off::PLAYER_GM);
    if (!sane_ptr(gm)) return false;
    if (mem::rd_hot<uintptr_t>(gm + (uintptr_t)off::GM_PLAYER) != e) return false;
    // 再验一层：那个 GameMode 的 vptr 得是 GameMode 族
    return in_module(mem::rd_hot<uintptr_t>(gm));
}

/** 生物 = 是 Actor 但不是玩家。ItemEntity / 掉落物 / 船 也算「非玩家实体」，
 *  但它们是 Actor，所以 Both/Living 都能打到 —— 与原版「左键能打什么」一致。 */
static inline bool is_living_entity(uintptr_t e) { return !is_player_entity(e); }

/** Target 枚举（KillAura / Hitbox 共用，与 Java 侧下拉逐字对齐）
 *    Living=0 只对生物（非玩家）   Players=1 只对玩家   Both=2 全部 */
static inline bool target_match(uintptr_t e, int target) {
    if (target == 0) return is_living_entity(e);
    if (target == 1) return is_player_entity(e);
    return true;
}

// ═══════════════════ GameMode 定位（不依赖堆扫） ═══════════════════
//
//  ★ 堆扫在实机上恒为空（scans 涨但 pair=0/lp=0），GameMode 因此永远拿不到，
//    于是 KillAura / Scaffold / AutoBreak / Nuker / Game Mode 全部静默失效。
//    这里改成**直接在 Player 对象 body 里探针**：我们要找的那个指针满足
//      ① `*(p) == base + GameMode 虚表`
//      ② `*(p + 0x08) == self`   ← GM_PLAYER，GameMode::attack 内部同款偏移取 Player
//    这是「不猜偏移」的做法：偏移由探针实测并缓存（状态串 gmoff=0x..）。

static std::atomic<int> g_gmOff{0};

/**
 * 在 Player body 里探 GameMode。
 *
 * ★★ 2.4.1 重写：**一次 syscall，其余全在本地缓冲上扫**。
 *
 *   旧实现在主线程每 300ms 跑一次「for o in 0x100..0x1400 step 8: rd(self+o)」，
 *   每个 rd 都是一次 process_vm_readv。486 轮 × 最多 3 次 = 1000+ 次 syscall，
 *   在 Houdini 下单次 ~145us ⇒ **单次调用 ~150ms**，主线程一半时间卡死
 *   → 游戏弹「应用无响应」，点等待后过一会儿又弹。这就是那个 ANR。
 *
 *   现在：把 [self+LO, self+HI) **整块一次拷进 thread_local 缓冲**（hot 版 = 进程内
 *   memcpy，纳秒级，不是 syscall），再在缓冲上线性扫；只有命中候选时才去读
 *   `*(p)` / `*(p+8)`（也用 hot 版）。整个函数 < 1ms。
 *
 *   安全性：hot 版由 mem 的 SIGSEGV 保护窗口兜底（guarded_copy），地址坏了只是返回 false。
 *   这里读的是 Player 自己的 body + 从 body 里顺出来的指针，不是堆里猜的，可以放心用 hot。
 */
/**
 * ★★★ 2.4.5 —— GameMode 判据彻底重做（本轮实机崩溃的根因就在这里）
 *
 *  【旧判据为什么错】
 *    老代码认为「对象首字 == base + vt::GAMEMODE(0x12AAB340)」就是 GameMode。
 *    IDA 全虚表枚举证明这条**必然命中尸体**：
 *      · 0x12AAB340 = 抽象基类 GameMode 的虚表
 *      · 0x12AAB3F8 = SurvivalMode 的虚表 ← 本地玩家实际用的那个
 *      · ~SurvivalMode(0xDAD73C4) 在 0xDAD7414 处 `ADRL X8,off_12AAB340; STR X8,[X19]`
 *        —— 对象析构时**把 vptr 改回基类虚表**，然后内存被释放/复用
 *    ⇒ 堆里那些「首字==0x12AAB340」的 qword，绝大多数是**已死对象**。
 *
 *  【实机铁证】
 *    gmp=0x779c00126dc0，而 `*(gmp+8) = 0x6B4` —— 根本不是 Player*。
 *    Scaffold → buildBlock → `LDR X0,[gm,#8]`=0x6B4 → getCarriedItem(0x6B4)
 *    → `LDR X8,[0x6B4+0xC18]=[0x12CC]` → SIGSEGV @ pc=base+0xDC3B6FC。
 *
 *  【新判据】三条同时成立才认：
 *    ① vptr 是 GameMode 族（基类 **或** SurvivalMode）—— 挡掉完全无关的对象
 *    ② `*(gm + 0x08)` 必须是个合法对象（挡掉 0x6B4 这种残骸）
 *    ③ **双向往返**：`*(gm+8) == self`（最强）
 *                 或 `is_player_entity(*(gm+8))`（`*(back+0xA78) → +8 == back`）
 *   ③ 是关键 —— 它对任何 Player 子类都成立，而只有**活的** GameMode 满足。
 */
static inline bool is_gamemode_vt(uintptr_t v) {
    const uintptr_t base = g_libBase.load();
    if (!base || !in_module(v)) return false;
    return v == base + vt::GAMEMODE || v == base + vt::GAMEMODE_SURVIVAL;
}

/** 候选 gm 是否可信。self 已知时优先走最强判据。 */
static inline bool gm_valid(uintptr_t gm, uintptr_t self) {
    if (!sane_ptr(gm)) return false;
    if (!is_gamemode_vt(mem::rd_hot<uintptr_t>(gm))) return false;
    const uintptr_t back = mem::rd_hot<uintptr_t>(gm + (uintptr_t)off::GM_PLAYER);
    if (!sane_ptr(back)) return false;              // ② 0x6B4 之类当场拒绝
    if (self != 0 && back == self) return true;     // ③a 最强
    return is_player_entity(back);                  // ③b 双向往返
}

// ═══════ safe 变体：专供「从堆里猜出来的地址」（scan_once 里的 obj / cand / gm） ═══════
//
//  ★★ 为什么必须分开 —— 这是 2.4.7 实机「注入后过一会闪退」的根因：
//
//     hot 版走**进程内 memcpy + SIGSEGV 兜底**，而兜底是靠 `siglongjmp`
//     从信号处理函数里跳出来。我们的 memcpy 是 ARM64 guest 代码，由 Houdini
//     翻译成 x86_64 执行 —— 翻译/执行期间 **Houdini 持有自己的翻译缓存锁**。
//     SIGSEGV 一跳出，那个锁**永远不会被释放** →
//     此后任何线程要翻译新代码都**死等** → 游戏卡死、被系统杀掉。
//
//     症状为什么是「过一会」而不是立刻崩：要等游戏跑到**需要新翻译的代码路径**。
//     扫描线程每轮对几百个野地址做 hot 读 = 每轮几百次信号，必然踩到。
//
//  ⇒ 判据回到铁律：**从别的对象里顺出来的（hot），还是从堆里猜出来的（safe）？**
//     scan_once 里所有候选地址都是后者 → 一律 safe（process_vm_readv，零信号）。
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

/**
 * 从 GameMode 反推「本地玩家 Player*」。
 *
 * ★★ 2.4.5 优先级翻转：**`*(gm+8)` 现在比 `g_player` 更可信**。
 *   · gm 本身必须通过 gm_valid()：vptr 是两种 GameMode 虚表之一，且
 *     `*(gm+8)` 能双向往返（`*(back+0xA78) == gm`）⇒ 这个 Player* 是**被证过的**。
 *   · g_player 有两个来源：hook 的 normalTick this（权威）和堆扫的 lp
 *     （只靠「首字 == vt::LOCALPLAYER」特征匹配，会认错对象）。
 *
 *   2.4.3 之所以反过来（g_player 优先），依据是「能用的是 Fly/AutoJump/GameMode ——
 *   它们用 g_player；不能用的是 KillAura/Scaffold/AutoBreak/Nuker —— 它们用 *(gm+8)
 *   ⇒ 所以 GM_PLAYER=0x08 不可靠」。**那个推断是错的**，现在有 IDA 铁证：
 *       · GameMode::GameMode(0xDAC1C48): `STP X8, X1, [X0]`  → [+0]=vtable [+8]=Player*
 *       · GameMode::buildBlock(0xDAC4764): 首句 `LDR X0,[X0,#8]`
 *       · Player::getGameMode(sub_635C634): `LDR X0,[X0,#0xA78]; RET`
 *   当时「不能用」的真因是 **gm 是个析构尸体**（`*(gmp+8)` = 0x6B4），
 *   偏移从来没错。现在 gm_valid() 已把那类对象挡在门外 → `*(gm+8)` 可信。
 */
static inline uintptr_t gm_self(uintptr_t gm) {
    if (!sane_ptr(gm)) return 0;
    const uintptr_t p = mem::rd_hot<uintptr_t>(gm + (uintptr_t)off::GM_PLAYER);
    return sane_ptr(p) ? p : 0;
}

static uintptr_t find_gamemode(uintptr_t self) {
    const uintptr_t base = g_libBase.load();
    if (!base || !sane_ptr(self)) return 0;

    // ── 路径 ⓪（★ 2.4.5 新增）──────────────────────────────────────────
    //   直接问 Player 要：`Player + 0xA78` 就是 GameMode*。
    //     证据①：垃圾橘子方法表有 `Player::getGameMode`；IDA 里它被编成
    //            sub_635C634: `LDR X0,[X0,#0xA78] ; RET`（整函数只有这一条）
    //     证据②：Windows 1.21.120 定位报告 `_attack(*(player+0xA78), actor, 0)`
    //   就算这个偏移在本作不对，读的也是 Player 对象体内的合法地址，
    //   只是验不过 → 返回 0，不会崩。
    //
    // ★★★ 2.4.14：**本函数的唯一调用方是扫描线程**（scan_loop → find_gamemode），
    //   所以整条链路必须 safe（process_vm_readv），一律不用 rd_hot / gm_valid。
    //   代价：路径②全 body 扫 486 个偏移 = 486 次 syscall ≈ 70ms，且只在
    //   「还没拿到 gm」时跑一次，拿到后缓存偏移（g_gmOff）→ 之后 2 次读。
    {
        const uintptr_t g = rd<uintptr_t>(self + (uintptr_t)off::PLAYER_GM);
        if (gm_valid_safe(g, self)) {
            g_gmOff.store((int)off::PLAYER_GM);
            return g;
        }
    }

    // 单点探针 —— 只认 gm_valid_safe，别的都不要
    auto probe = [&](int o) -> uintptr_t {
        const uintptr_t p = rd<uintptr_t>(self + (uintptr_t)o);
        return gm_valid_safe(p, self) ? p : 0;
    };

    // ── 路径 ①：缓存偏移（命中就是 1 次读，省掉整轮全扫） ──
    {
        const int cached = g_gmOff.load();
        if (cached > 0) {
            const uintptr_t p = probe(cached);
            if (p) return p;
            g_gmOff.store(0);                     // 失效 → 重探
        }
    }

    // ── 路径 ②：逐 8 字节进程内读，全 body 扫 ──
    //
    // ★★ 2.4.2 关键修复：**不再整块 memcpy body**。
    //
    //   上一版这里是：
    //       static thread_local uint8_t body[0x1300];
    //       const size_t gotBody = mem::safe_copy_hot(body, self + 0x100, 0x1300);
    //       for (...) { if (gotBody != sizeof(body)) { if (o >= LO + gotBody) break; } ... }
    //
    //   而 safe_copy_hot 内部是**一句 memcpy**（guarded_copy，全有或全无）：
    //   Player 的 body 有 4864 字节、**必然跨页**（页 4096），只要尾巴跨出可读页
    //   （对象住在堆尾、后面紧跟守护页时必然如此），整块返回 0 →
    //   `o >= LO + 0` 立刻 break → 函数返回 0 → g_gamemode 永远是 0 →
    //   KillAura / Scaffold / AutoBreak / Nuker / Game Mode **全部在第一道闸门 return**。
    //   这就是「自动搭路和杀戮开了没反应」的头号原因。
    //
    //   逐 8 字节读每次只碰一个 qword，几乎不可能跨页；486 次加起来 < 100us。
    //   本函数现在只在扫描线程跑，每轮一次，代价可以忽略。
    //
    // ★★★ 2.4.5：**不再有「弱命中」兜底**。
    //   旧代码在这一轮里 `if (!weak) weak = probe(o,false)` 把第一个 vptr 撞对的
    //   对象记下来直接返回 —— 那正是那个死对象。现在只有 gm_valid 能通过，
    //   全 body 扫不到就老老实实返回 0（功能不工作，但绝不崩）。
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

// ═══════════════════════════════ 堆扫 ═══════════════════════════════════

// ───────────── Actor 虚表「自动学习」表（★★ 2.4.5 新增）─────────────
//
//  问题：旧堆扫只认三张**基类**虚表 —— vt::ACTOR / vt::MOB / vt::PLAYER。
//        可游戏里真正跑着的全是**子类**：Zombie / Skeleton / Creeper / Cow / Pig /
//        ItemEntity / Arrow … 每张虚表都不一样。
//        ⇒ 堆扫几乎收不到实体 → ESP 只框得住玩家、KillAura 只能打玩家，
//          "只攻击生物"这个下拉等于摆设。
//
//  方案：不猜类名、不硬编码虚表清单，改成**按 vptr 自动学习**。
//        Itanium RTTI 的结构特征对**所有** C++ 对象一致，所以：
//          ① `*(v - 0x10) == 0`          （offset-to-top，正常对象必须为 0）
//          ② `*(v - 0x08)` 在模块内       （typeinfo 指针）
//          ③ 持有它的对象位置链读得出来    （obj+0x288 → float3，数值合理）
//        三条都过 ⇒ 这个 vptr 就是某张 Actor 系虚表，**记进集合，之后零成本**。
//
//  为什么安全：①② 读的是**虚表本身**（模块只读段，一定可读）→ 用 hot 读零风险；
//        ③ 只在 ①② 都过时才做（这类 vptr 少），且用 hot 读 + 位置合法性强校验。
//        每轮限量学习（VT_LEARN_PER_ROUND），最坏情况是多扫几轮才认全，
//        绝不会让单轮卡死。
//
//  为什么不用「扫虚表区里所有 vptr 逐个试」：虚表区命中量太大，
//        但先做 **RVA 区间比较**（一次整数比较）就能滤掉 97% 的 qword。
static constexpr uintptr_t VT_SCAN_LO = 0x12400000;   // 虚表区下界（RVA）
static constexpr uintptr_t VT_SCAN_HI = 0x12D00000;   // 虚表区上界（RVA）
//  ★ 2.4.8：128 → 48。学习路径现在是 safe 读（Houdini 下 ≈145µs/次），
//    必须把每轮的信号/系统调用量压住。学全只是晚几轮的事 ——
//    稳态下 vt_bit_test 是 O(1) 位测试，不再有任何额外代价。
static constexpr int       VT_LEARN_PER_ROUND = 48;   // 每轮最多新认多少张虚表

// 已确认的 Actor 系虚表 —— 用 bitmap 存（区间内每个 qword 1 bit）。
//   O(1) 查、无锁写（单线程写 / 多线程读，x86 byte store 本身是原子的）。
//   144KB 的 .bss，换来「稳态下每个 qword 只花一次位测试」。
static constexpr size_t VT_BITS = (VT_SCAN_HI - VT_SCAN_LO) / 8;      // qword 个数
static constexpr size_t VT_BYTES = (VT_BITS + 7) / 8;
static uint8_t g_actorVtBits[VT_BYTES];
static std::atomic<unsigned> g_actorVtCount{0};

static inline bool vt_bit_test(uintptr_t rva) {
    const size_t idx = (rva - VT_SCAN_LO) >> 3;          // 第几个 qword
    return (g_actorVtBits[idx >> 3] >> (idx & 7)) & 1;
}
static inline void vt_bit_set(uintptr_t rva) {
    const size_t idx = (rva - VT_SCAN_LO) >> 3;
    g_actorVtBits[idx >> 3] |= (uint8_t)(1u << (idx & 7));
}

/** 「这个 vptr 是不是 Actor 系虚表」—— 只看 bitmap，不做判定 */
static inline bool actor_vt_known(uintptr_t rva) {
    return rva >= VT_SCAN_LO && rva < VT_SCAN_HI && vt_bit_test(rva);
}

/** 试着把 (vptr, 持有它的对象) 认成 Actor 系虚表；认下返回 true */
static bool actor_vt_learn(uintptr_t v, uintptr_t obj) {
    // ★★★ 2.4.14：①② 也改成 **safe 读**（原为 rd_hot）。
    //   原来注释假设「v 来自虚表区、v-0x10/v-0x08 一定可读」——**不成立**：
    //   v 只是「落进模块地址区间」的一个 qword，`v - 0x10` 可能落在某个映射的
    //   第一页之前 / 段间空洞里 → hot 读立刻 SIGSEGV → guarded_copy 兜底。
    //   实机 09:30:09 的致命崩溃栈就是这条路（actor_vt_learn → ... → mem.hpp:129），
    //   崩在信号恢复时的 `__emutls_get_address`（见 mem.hpp 顶部）。
    //   本函数只在扫描线程调用，全程走 process_vm_readv，零信号。
    // ① offset-to-top 必须为 0
    if (rd<uintptr_t>(v - 0x10) != 0) return false;
    // ② typeinfo 指针也必须在模块内
    if (!in_module(rd<uintptr_t>(v - 0x08))) return false;
    // ③ obj 是**从影子缓冲里扫出来的候选地址**（野地址）→ 全程必须 safe。
    //    ★★ 这里原来写的是 `is_actor_ex(obj, true)` —— hot 读野地址会直接 SIGSEGV，
    //       靠 guarded_copy 的 siglongjmp 兜底；每轮最多 VT_LEARN_PER_ROUND 次。
    //       在 Houdini 下 siglongjmp 会跳过翻译层的解锁路径，把翻译缓存锁永久留下
    //       → 过一会整个进程卡死被杀。这是 2.4.5 引入、2.4.7 首次实机暴露的头号崩溃源。
    //    先用一次 safe 读确认「obj 的 vptr 真的是 v」—— 语义上本就该有这一步，
    //    而且它能把绝大多数候选一次否掉，省下后面几次读。
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

    // ★★ 2.4.2：把「libminecraftpe.so 自己的可写段（.data/.bss）」排到最前面。
    //
    //   游戏进程里那些**全局单例**（Minecraft、Level 等）就住在这几 MB 里，
    //   而 LocalPlayer / GameMode 都是它们持有的对象 —— 先扫这一段，往往第一轮
    //   就能定案 self + GameMode，不用等几十轮堆扫（2GB 堆 × 96MB/轮）。
    //   writable_ranges() 是按 /proc/self/maps 原序返回的，模块段本来就在前面，
    //   但 Houdini 会往 maps 里插一大堆保留区，顺序并不保证 —— 这里显式排一次。
    if (b.lo && b.hi > b.lo) {
        const uintptr_t mLo = b.lo, mHi = b.hi;
        std::stable_sort(rs.begin(), rs.end(), [mLo, mHi](const Range& a, const Range& c) {
            const bool ina = (a.s >= mLo && a.e <= mHi);
            const bool inc = (c.s >= mLo && c.e <= mHi);
            return ina > inc;                       // 模块内可写段优先
        });
    }

    uintptr_t lp = 0;
    uintptr_t gmFast = 0;         // ★ 扫到 LocalPlayer 的瞬间反查到的 GameMode
    // ★★ 2.4.5：本轮已经试认过的 vptr（避免同一个未学 vptr 被反复做 ①②③）
    uintptr_t triedVt[VT_LEARN_PER_ROUND];
    int nTried = 0;
    // ★★ 2.4.5：`gmAny`（「vptr == 基类虚表即认定」）已**整条删除**。
    //   它就是实机 gmp=0x779c00126dc0 / *(gmp+8)=0x6B4 那具尸体的来源：
    //   ~SurvivalMode(0xDAD73C4) 在 0xDAD7414 处 `STR base+0x12AAB340,[x19]`
    //   把 vptr 改回基类虚表，此后这块内存被释放 → 老判据继续命中 → 崩在
    //   GameMode::buildBlock 的 `getCarriedItem(*(gm+8))` 上。
    //   现在只认 gm_valid()（双虚表 + 反指 + 读 [gm]）。
    uintptr_t pairP[8] = {0};
    uintptr_t pairG[8] = {0};
    int npair = 0;
    int rej = 0;
    bool doneEarly = false;       // ★ 提前命中（≠ 预算耗尽，游标不能推进）

    std::vector<Ent> ents;
    ents.reserve(256);

    // ── 分块拷进影子缓冲后再扫 ──────────────────────────────────────────
    //   ★ 上一版就崩在这：直接对 /proc/self/maps 快照里的地址**裸读**。
    //     快照最陈旧 2 秒，而游戏里 ART GC + Houdini 翻译缓存在不停 munmap，
    //     扫到刚被释放的页 = SIGSEGV（tombstone 里 fault addr 正好是段起始页）。
    //   现在：先用 safe_copy（process_vm_readv，内核做访问检查，读不到返回
    //   EFAULT，**绝不产生信号**）整块拷进影子缓冲，再在缓冲上扫 —— 零崩溃风险，
    //   而且一次 syscall 拿 64KB，比逐 8 字节读还快。
    static constexpr size_t CHUNK = 1 << 16;
    static std::vector<uint8_t> shadow;
    if (shadow.size() != CHUNK) shadow.resize(CHUNK);

    uint64_t readBytes = 0;
    // ★ Houdini 下一次 process_vm_readv ≈ 145us（实机实测 891ms / 6144 次）。
    //   一轮 384MB 就是 890ms 满 CPU —— 降到 96MB（≈220ms/轮），其余下一轮接着扫。
    //   轮转游标保证「不会永远只扫前半段」。
    const uint64_t BUDGET = 96ull << 20;

    // 轮转游标：进程堆可能有 1–2 GB，一轮读不完就必须从上次停的地方继续，
    // 否则永远只覆盖前半段，玩家/实体都找不到。
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

            // ★ 按「**实际拷到的字节数**」推进。64KB 里只要尾部跨到一个守护页 /
            //   刚被 munmap 的洞，整块就作废 —— 而进程堆里这种洞遍地都是。
            //   旧代码就是整块丢弃再退 4KB、再丢弃就 p+=4096，于是绝大多数堆
            //   一页都没真正扫到（实机 1805 轮 scans，命中 0）。
            const size_t got = mem::safe_copy_n(shadow.data(), p, want);
            if (got < 8) { p += (size_t)CHUNK; continue; }   // 整块不可读 → 大步跳过

            readBytes += got;
            const size_t n = got & ~(size_t)7;

            const uint8_t* buf = shadow.data();
            for (size_t o = 0; o + 8 <= n; o += 8) {
                uintptr_t v;
                ::memcpy(&v, buf + o, 8);
                if (v < B || v >= b.hi) continue;     // 超廉价过滤：虚表必须在模块内
                const uintptr_t rva = v - B;
                const uintptr_t obj = p + o;          // 「装着这个指针」的对象自身的地址

                if (rva == vt::LOCALPLAYER) {
                    if (!lp) {
                        lp = obj;
                        // ★ 拿到 LocalPlayer 就**立刻反查 GameMode**，命中则整轮收工。
                        //   "还没 self/gm" 时这个堆扫是唯一来源，没必要把 96MB 扫满。
                        //   ★★ 2.4.5：判据换成 gm_valid()（原来只查 == 基类虚表，会命中尸体）。
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
                // ── 实体收集 ────────────────────────────────────────────────
                //   ★★ 2.4.5：不再只认三张基类虚表。
                //     ① 三张已知基类虚表（ACTOR/MOB/PLAYER）→ 直接收，零判定成本；
                //     ② 落在虚表区、且已学过的 vptr → O(1) 位测试后直接收；
                //     ③ 落在虚表区、没学过、本轮还有学习预算 → 试认一次
                //        （同一个 vptr 本轮只试一次，避免重复开销）。
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
                        e.src = 0;                      // 堆扫来源
                        // ★★ 2.4.5：玩家/生物标记（KillAura / Hitbox 的 Target 下拉要用）。
                        //   vt::PLAYER 只覆盖 Player 顶层虚表，本地/远程玩家常是子类
                        //   → 必须靠「Player+0xA78 ↔ GameMode+0x08」双向往返判据。
                        // ★ isPlayer 只有 KillAura / Hitbox 的 Target 下拉在消费。
                        //   KillAura 默认关着 → 一次读都不做，扫描线程零额外开销。
                        //   开着时每个入表实体 3 次 process_vm_readv ≈ 435µs
                        //   （Houdini 单次 ≈145µs），512 个封顶 ≈ 220ms/轮；扫描线程
                        //   priority 10，可以接受。**绝不能用 hot 版** —— 见 gm_valid_safe 注释。
                        e.isPlayer = g_kaura.load() && is_player_entity_safe(obj);
                        ents.push_back(e);
                    }
                    continue;
                }
                if (rva == vt::GAMEMODE || rva == vt::GAMEMODE_SURVIVAL) {
                    // ★★ 2.4.5：**两种虚表都要认**。
                    //   活对象的 vptr 是 SurvivalMode 虚表（0x12AAB3F8），基类虚表
                    //   （0x12AAB340）只出现在「构造中」和「析构后未被复用」的内存上。
                    //   所以：虚表匹配只是初筛，真伪交给 gm_valid() —— 它必须读到
                    //   `*(gm+8)` 是个能双向往返的 Player（0x6B4 之类当场拒掉）。
                    if (gm_valid_safe(obj, lp)) {
                        const uintptr_t pl = rd<uintptr_t>(obj + (uintptr_t)off::GM_PLAYER);
                        if (npair < 8) { pairP[npair] = pl; pairG[npair] = obj; ++npair; }
                    }
                    continue;
                }
            }

            p += n;
            if (over) break;                                // ★ 提前命中 → 本轮收工
            if (readBytes >= BUDGET) { over = true; break; }
        }

        // ★ 只有"预算耗尽"才记游标；"提前命中"下轮还是从头扫（反正马上就停了）
        if (over && !doneEarly) { curRange = (curRange + k) % nR; curOff = p; }
    }
    if (!over && nR) { curRange = 0; curOff = 0; }   // 一轮扫到底，下轮从头再来

    // ── 定案 LocalPlayer / GameMode ──
    uintptr_t gm = gmFast;        // ★ 扫描途中已经定案的优先

    // ① 已知 LocalPlayer → 用 Player+PLAYER_GM 拿 GameMode
    //   ★★ 2.4.5：判据换成 gm_valid()（接受两种虚表 + 反指；不再「vptr 对就行」）
    if (!gm && lp) {
        const uintptr_t cand = rd<uintptr_t>(lp + (uintptr_t)off::PLAYER_GM);
        if (gm_valid_safe(cand, lp)) gm = cand;
    }
    // ② 回退：用互指对里「GameMode+8 指回的那个 Player」当 LocalPlayer
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
    // ③ ★★ 2.4.5：**「任意 GameMode」兜底已删除**。
    //   它不要求任何反指关系，是假对象最后一条入口 ——
    //   实机 `gmp=0x779c00126dc0, *(gmp+8)=0x6B4` 正是从这里漏进来的，
    //   随后 GameMode::buildBlock → getCarriedItem(0x6B4) → SIGSEGV(0xDC3B6FC)。
    //   宁可这一轮拿不到 gm（下一轮 400ms 后再扫），也不能拿一个错的去写内存。

    // ── 实体按距本地玩家的距离过滤（找不到玩家就不过滤，只做数量上限） ──
    float sx = 0, sy = 0, sz = 0;
    const bool haveSelf = lp != 0 && read_actor_pos_safe(lp, sx, sy, sz);
    if (haveSelf) {
        std::vector<Ent> keep;
        keep.reserve(ents.size());
        for (const auto& e : ents) {
            if (e.p == lp) continue;
            const float dx = e.x - sx, dy = e.y - sy, dz = e.z - sz;
            if (dx * dx + dy * dy + dz * dz > 96.0f * 96.0f) continue;   // 96 格外不关心
            keep.push_back(e);
        }
        ents.swap(keep);
    }

    {
        std::lock_guard<std::mutex> lk(g_entMtx);
        g_ents.swap(ents);
        g_entCount.store((int)g_ents.size());
    }

    // ★ 不要用 0 覆盖：self 的权威来源是 normalTick hook，GameMode 的权威来源是
    //   find_gamemode 探针。堆扫只是补充（找实体），拿不到就保持原值，别把好的抹掉。
    // ★★ 2.4.2：**别让堆扫的 lp 顶掉 hook 给的权威 self**。
    //   hook 有效时 g_player 来自 LocalPlayer::normalTick 的 this，是定义性正确的；
    //   而 lp 是靠「对象首字 == vt::LOCALPLAYER」从堆里猜出来的，判据再严也只是特征匹配。
    //   旧代码无条件 `if (lp) g_player.store(lp)` —— 一旦 vt::LOCALPLAYER 认错对象，
    //   g_player 就被带歪，ESP / Coords / nametags 全部跟着错。
    //   现在只在「hook 从来没给过 self」时才让 lp 上位，并且要求它至少读得出合理位置。
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

// ═══════════════════════════ 主线程：KillAura ═══════════════════════════

static void killaura_fire() {
    if (!g_kaura.load()) return;
    if (rva::FN_GAMEMODE_ATTACK == 0) return;

    const uintptr_t base = g_libBase.load();
    const uintptr_t gm = g_gamemode.load();
    if (!base || !gm) { g_kaNoGm.fetch_add(1); return; }

    // ★★ 2.4.2：self **直接问 GameMode 要** —— `GameMode + 0x08` 就是 Player*，
    //   这是 GameMode 自己的权威字段（IDA 实证：attack 第一句就是
    //   `sub_D8E8B88(*(this + 8))` 取 Player）。
    //
    //   不再用 hook 给的 g_player 当闸门：MuMu 的 Houdini 会把
    //   LocalPlayer::normalTick 的翻译结果缓存住，inline hook 打上去是**静默失效**的
    //   （不崩、不报错、g_player 恒为 0）。旧代码 `if (!self) return;` 于是
    //   让整个 KillAura 在第一行就 return —— 用户看到的就是"开了没反应"。
    //   现在只要 g_gamemode 到手就能工作，与 hook 是否生效解耦。
    //
    // ★★ 2.4.5：**self 优先从 gm 反推** —— gm_valid() 已经证明 `*(gm+8)` 是个
    //   双向验证过的 Player*（`*(back+0xA78) == gm`），比堆扫的 g_player 更可信。
    //   g_player 只在 gm 侧读不出来时兜底。
    uintptr_t self = gm_self(gm);
    if (!sane_ptr(self)) self = g_player.load();
    if (!sane_ptr(self)) { g_kaNoGm.fetch_add(1); return; }

    float sx, sy, sz;
    if (!read_vec3(self, 0, sx, sy, sz)) return;

    double range = g_kauraRange.load();
    if (range < 1.0) range = 1.0;
    if (range > 12.0) range = 12.0;
    const float r2 = (float)(range * range);

    // ── 选目标：Single 取最近，Multi 取范围内全部 ──
    //   ★★ 2.4.5：加 Target 筛选（0=Living 只打生物 / 1=Players 只打玩家 / 2=Both 全打）
    const int kTarget = g_kauraTarget.load();
    std::vector<std::pair<float, uintptr_t>> cand;
    cand.reserve(16);
    {
        std::lock_guard<std::mutex> lk(g_entMtx);
        for (const auto& e : g_ents) {
            if (e.p == self) continue;
            // ★ 2.4.2：改 hot 版。g_ents 的条目在入表时已经用 safe 版闸门筛过一遍
            //   （vtable 结构 + 位置链），这里是「已知合法对象」→ 走进程内 memcpy。
            //   旧写法每个实体 3 次 process_vm_readv（Houdini 下 145us/次），
            //   30 个实体 = 90 次 ≈ 13ms/tick —— 又一个卡顿来源。
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

    // ★ 暴击窗口：Criticals 开着时，在发起攻击前把 isOnGround hook 的"强制 false"
    //   窗口打开 150ms —— 客户端这一击就按「下落中」算（暴击判定条件之一）。
    //   窗口必须短，否则会干扰正常物理。
    if (g_critOn.load()) g_critUntil.store(g_nowMs.load() + 150);

    // ★ 只传两个参数 —— manual 由 0xDAC2664 自己设成 1
    using AtkFn = void (*)(void*, void*);
    const AtkFn fn = reinterpret_cast<AtkFn>(base + rva::FN_GAMEMODE_ATTACK);

    if (mode == 1) {                                      // Multi：范围内全部打一遍
        for (const auto& c : cand) {
            fn(reinterpret_cast<void*>(gm), reinterpret_cast<void*>(c.second));
            g_attacks.fetch_add(1);
        }
        LOGI("attack(Multi) -> %zu targets seen=%zu total=%u",
             cand.size(), cand.size(), g_attacks.load());
    } else {                                              // Single：只打最近的
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

// ═══════════════════════════ 主线程：Scaffold ═══════════════════════════

/**
 * 脚底自动放置方块 —— 不需要右键，每个 tick 尝试往玩家脚下补一块。
 *
 * 落点算法（关键，别乱改）：
 *   buildBlock 的语义是「点击某个方块的某个面」，新方块落在 pos + face 方向，
 *   **不是**直接给落点坐标。所以要在「玩家脚下的空气格 (x, y-1, z)」放方块，
 *   得去点它正下方那一格 (x, y-2, z) 的**上表面**（Face::Up = 1）：
 *       点击 (x, y-2, z)  ↑  → 方块生成在 (x, y-1, z) ✔
 *
 * 这套算法自带「不会把玩家卡住」的性质：
 *   · 玩家站在地面上 → (x,y-1,z) 已被自己踩着的方块占着，buildBlock 直接失败，无害；
 *   · 玩家在空中下落 → y 每降一格，就刚好有机会在脚下补上新方块，接住自己。
 *
 * ★ 必须在游戏主线程调（同 attack：buildBlock 内部也是构造闭包 + 入队）。
 * ★ 第 4 个参数恒传 0 —— 与游戏自己的 startBuildBlock 壳完全一致，别自己造 Vec3。
 */
static void scaffold_tick() {
    if (!g_scaffold.load()) return;
    if (rva::FN_GAMEMODE_BUILD == 0) return;

    const uintptr_t base = g_libBase.load();
    const uintptr_t gm = g_gamemode.load();
    if (!base || !gm) { g_scNoGm.fetch_add(1); return; }

    // ★★ 2.4.5：和 killaura_fire 一致 —— self **优先从 gm 反推**，g_player 兜底。
    uintptr_t self = gm_self(gm);
    if (!sane_ptr(self)) self = g_player.load();
    if (!sane_ptr(self)) { g_scNoGm.fetch_add(1); return; }

    BPos pp{0, 0, 0};
    if (!read_blockpos(self, 0, pp)) {
        // 兜底：用浮点坐标向下取整。fail 就这一帧不动，等下一帧
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
    const bool ok = fn(reinterpret_cast<void*>(gm), &click, 1 /*Face::Up*/, nullptr);

    g_builds.fetch_add(1);
    if (ok) {
        g_buildOk.fetch_add(1);
        LOGI("scaffold place at (%d,%d,%d) click=(%d,%d,%d) ok=%u/%u",
             (int)pp.x, (int)(pp.y - 1), (int)pp.z,
             (int)click.x, (int)click.y, (int)click.z,
             g_buildOk.load(), g_builds.load());
    }
}

// ═══════════════════════════ 主线程：ESP ═══════════════════════════

/**
 * 读 LocalPlayer 的 yaw / pitch（度）。
 *
 * 基岩版这两个值**不在 Actor 的固定偏移上**，而是走 ECS 组件表，所以直接借
 * Actor 虚表里两个现成的 getter（签名都是 `(this, float partialTicks) -> float`）：
 *   [18] = 插值 yaw    (sub_D5B3F64，内部 sub_D5ACB5C 取 RotationComponent)
 *   [19] = 插值 pitch  (sub_D5B3F8C，内部 sub_D91019C)
 * 传 partial = 1.0 就是取"当前帧"的值。
 */
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

/** 摄像机（眼位 + 三轴 + 焦距），yaw 0 朝 +Z（南），pitch 负为上 */
struct Cam {
    float ex, ey, ez;
    float fx, fy, fz;      // forward
    float rx, ry, rz;      // right
    float ux, uy, uz;      // up
    float focal;           // 像素焦距
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
    c.ey = py + 1.62f;                       // 眼高，近似即可
    c.ez = pz;

    c.fx = -sy * cp; c.fy = -sp;  c.fz =  cy * cp;
    c.rx =  cy;      c.ry = 0.0f; c.rz =  sy;

    // up = right × forward
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

/** 投影单点；false = 落在相机后方 */
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

/** 把一个世界空间立方体投影成归一化屏幕框追加进 out；出视锥/在身后返回 false */
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

/** 组装 ESP 数据：每 4 个 float 一个框（x0,y0,x1,y1，0..1 归一化，左上为原点） */
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

    // ── 实体框 ──
    if (g_esp.load()) {
        std::lock_guard<std::mutex> lk(g_entMtx);
        for (const auto& e : g_ents) {
            if (e.p == self) continue;
            const float dx = e.x - px, dy = e.y - py, dz = e.z - pz;
            if (dx * dx + dy * dy + dz * dz > r2) continue;
            // 实体按 0.7 宽 / 1.8 高画（与 read_aabb 的量级一致）
            if (push_box(out, cam, e.x, e.y, e.z, 0.35f, 1.8f)) {
                if (++n >= 64) break;
            }
        }
    }
    g_espDrawn.store(static_cast<unsigned>(n));

    // ── 方块实体框（ChestESP）—— 复用同一条通道，Java 侧一起画 ──
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

    // ── 稀有方块框（Xray）—— 同一条通道 ──
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

// ═════════════ 主线程：第二批功能（全部由「已定案」函数驱动） ═════════════
//
//  调用约定铁律（见 offsets.hpp 顶部注释）：
//    · 所有改变游戏状态的调用都只能在游戏主线程做 → 这些函数只在 nTick 里被调；
//    · isOnGround 吃 EntityContext*(Actor+8)，不是 Actor*；
//    · Vec3/Vec2/BlockPos 一律按指针传。

/** 取模块内函数指针；rva 为 0 或基址未就绪 → nullptr（调用方必须判空） */
template <typename F>
static inline F fn_at(uintptr_t rva) {
    const uintptr_t b = g_libBase.load();
    if (!b || rva == 0 || rva >= 0x14000000) return nullptr;
    return reinterpret_cast<F>(b + rva);
}

/** 复核 GameMode 是否还属于当前玩家（换维度 / 重生后旧对象会失效，必须每 tick 查） */
static inline bool gm_ok(uintptr_t gm, uintptr_t self) {
    return gm != 0 && self != 0 && rd<uintptr_t>(gm + off::GM_PLAYER) == self;
}

/** 取玩家所在 BlockSource；失败返回 0 */
static uintptr_t self_blocksource(uintptr_t self) {
    using GetBsFn = uintptr_t (*)(void*);
    GetBsFn getBs = fn_at<GetBsFn>(rva::FN_ACTOR_GET_DIM_BLOCKSOURCE);
    if (!getBs) return 0;
    return getBs(reinterpret_cast<void*>(self));
}

/**
 * 某坐标是不是空气 —— 走 BlockSource::getBlock(0xDD7B3FC) + Block::isAir(0xE575674)。
 * 任何一步读不到都**当成空气**（= 不破坏），保守优先。
 *
 * Block::isAir 的内部实现是 `sub_E436E28(*(block + 0x90))`，
 * 所以描述符指针为空时直接短路，避免把 0 喂进去。
 */
static bool block_is_air(uintptr_t bs, const BPos& p) {
    using GetBlockFn = uintptr_t (*)(void*, const void*);
    using IsAirFn    = bool (*)(const void*);
    GetBlockFn getBlock = fn_at<GetBlockFn>(rva::FN_BLOCKSOURCE_GETBLOCK);
    IsAirFn    isAir    = fn_at<IsAirFn>(rva::FN_BLOCK_ISAIR);
    if (!bs || !getBlock || !isAir) return true;

    const uintptr_t blk = getBlock(reinterpret_cast<void*>(bs), &p);
    if (!sane_ptr(blk)) return true;
    if (!sane_ptr(mem::rd_hot<uintptr_t>(blk + 0x90))) return true;   // 描述符为空
    return isAir(reinterpret_cast<const void*>(blk));
}

/**
 * 破坏一个方块 —— GameMode::destroyBlock(0xDAC3388)，GameMode 虚表[3]。
 *
 * 参数取值说明（来自反编译）：
 *   face(a3)、a4 会被转发进 `sub_DAC323C(gm, pos, face, a4&1)` 做「可否破坏」判定，
 *   a5 只被当成一个布尔位 OR 进最终条件。这里取 face=1（Up，与 buildBlock 同一套
 *   面枚举）、a4=true（首次点击）、a5=true。
 *   ★ 这三个值属于「语义已明、具体取值待实机验证」——所以 status 里会给出
 *     break/ok 计数，实机一看就知道有没有真生效。
 */
static bool try_break(uintptr_t gm, const BPos& p) {
    using DestroyFn = bool (*)(void*, const void*, unsigned, bool, bool);
    DestroyFn destroy = fn_at<DestroyFn>(rva::FN_GAMEMODE_DESTROY);
    if (!destroy) return false;
    g_breaks.fetch_add(1);
    const bool ok = destroy(reinterpret_cast<void*>(gm), &p, 1u, true, true);
    if (ok) g_breakOk.fetch_add(1);
    return ok;
}

// ─────────────────────────── Fly ───────────────────────────

/**
 * Fly —— 打开 Abilities 里的 Flying(9) / MayFly(10)。
 *
 * 链路（三条全已定案）：
 *   ab = Player::getAbilities(player)     0xDC3B9CC
 *   Abilities::setAbility(ab, 9, v)       0xDC2B6C8
 *   LocalPlayer::isFlying(player)         0xDC34D50  ← 回读自检
 *
 * 自洽性证明：setAbility 写的是 12 字节条目 `entry[0]=2; entry[4]=值`，
 * 而 isFlying 读的正是 `entry[0]==2 && entry[4]!=0` —— 同一个条目，
 * 所以写完立刻生效，不需要额外同步。
 *
 * ★ 游戏每 tick 会按玩家自身状态覆写该容器，所以必须持续施加。
 * ★ 关闭时也要再跑一轮把两个位清掉（靠 g_flyRead 记忆上一次读回值）。
 */
static void fly_tick() {
    const bool want = g_fly.load();
    if (!want && g_flyRead.load() == 0) return;      // 关着、上次读回也是关 → 收工

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

// ─────────────────────────── Auto Jump ───────────────────────────

/**
 * Auto Jump —— 着地就跳（Bhop 式）。
 *
 *   Actor::isOnGround(EntityContext*)   0xDC216B4   ← ⚠ 传 self + 0x08
 *   Mob::jumpFromGround(Mob*)           0xCEF9270   ← 传 self 本体
 *
 * jumpFromGround 自带三重守卫（能力位 / 着地 / 可跳），不满足就 return 0，
 * 所以「每 tick 想跳就调」是安全的，不会在空中乱跳。
 */
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

    // ⚠ 关键：EntityContext 在 Actor + 0x08，不是对象头
    if (!onGround(reinterpret_cast<const void*>(self + off::ACTOR_ENTITY_CONTEXT))) return;

    lastAt = now;
    if (jump(reinterpret_cast<void*>(self))) {
        g_jumps.fetch_add(1);
        LOGI("autojump ok total=%u", g_jumps.load());
    }
}

// ══════════════════════ 位移统一入口：ΔY 补偿（2.4.5 核心修复） ══════════════════════
//
//  ★★★ 「上天」四连（Speed / Sprint / Teleport-Forward / Velocity）的**唯一根因**：
//
//  Actor::teleportTo @0xD8F3918 在真正写位置之前，会自己给 Y 加一个偏移 Δ：
//
//      0xD8F3B4C  LDR  X0, [X19,#0x298]
//      0xD8F3B58  BL   sub_A865A28         ; 位置平滑
//      0xD8F3B5C  ADD  X0, X19, #8
//      0xD8F3B60  BL   sub_DC092D8         ; ★ S0 = Δ（参数 = Actor+8，即 EntityContext）
//      0xD8F3B64  LDP  S1, S2, [X20]       ; S1 = pos.x   S2 = pos.y
//      0xD8F3B6C  FADD S0, S2, S0          ; ★ S0 = pos.y + Δ
//      0xD8F3B7C  STP  S1, S0, [SP,#var_14]
//      0xD8F3BA8  BL   sub_9F69940         ; 用「抬高后的 Y」落位置
//
//  我们四个功能全都干同一件事：把 `*(actor+0x288)` 读到的**当前 Y 原样传回去**。
//  ⇒ teleportTo 每次都再抬 Δ：
//       Speed    20 次/秒（50ms 周期）  → 垂直速度 ≈ 20Δ/s
//       Sprint   10 次/秒（100ms 周期） → 垂直速度 ≈ 10Δ/s
//       Teleport Forward 1.7 次/秒      → 缓慢爬升，表现为「选前进却上了天」
//       Velocity 每次回弹再来一次       → 越弹越高
//
//  ⇒ 修法：传 `y - Δ`。teleportTo 内部 `+Δ` 正好抵消，落点就是请求的 y。
//
//  另外加了一层**读回自校准**：teleportTo 是同步写 ECS 组件的（sub_9F69940 直接落），
//  调用完立刻能读到结果位置。若实测落点与请求值仍然差得多（Δ 估计错了），
//  就用实测差重算 Δ。双保险，且随游戏版本/实体类型自适应。
static void tp_abs(uintptr_t self, float tx, float ty, float tz) {
    using TeleportFn = void (*)(void*, const void*, bool, unsigned, unsigned, bool);
    TeleportFn tp = fn_at<TeleportFn>(rva::FN_ACTOR_TELEPORT);
    if (!tp || !self) return;

    static std::atomic<float> s_delta{0.0f};   // 上次学到的 Δ；0 = 未补偿（校准态）
    float delta = s_delta.load();

    // ① 现算 Δ（进程内调用，纳秒级；失败就沿用缓存值）
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

    // ② 读回校准（同一函数内、立刻读；失败不影响主流程）
    float gx, gy, gz;
    if (read_actor_pos(self, gx, gy, gz) && sane_float(gy)) {
        const float real = gy - ty;            // 实际被抬了多少
        if (real > -4.0f && real < 4.0f) {
            if (delta == 0.0f) {
                if (std::fabs(real) > 0.02f) s_delta.store(real);      // 学到真实 Δ
            } else if (std::fabs(real) > 0.05f) {
                s_delta.store(delta + real);                           // 补偿不彻底 → 修正
            }
        }
    }
}

// ─────────────────────────── Teleport ───────────────────────────

/**
 * Teleport —— Actor::teleportTo(0xDC31E84)（Actor 虚表[26]）。
 *
 * ⚠ 只做「同区块内」的位移：
 *   反编译显示 a4/a5（两个 uint32）会被原样转进 `sub_9C03870(a1, a4, a5, ...)`，
 *   语义未定案 —— 很可能是区块坐标或传送类型。为安全起见一律传 0/0，
 *   且位移限定在 16 格内（同区块），避免被送到错误的区块。
 *   a3=0 / a6=1 是有据可依的两个布尔位（a3 与 a6 都进了 sub_D5B3600 的位测试）。
 */
static void teleport_tick() {
    if (!g_tpOn.load()) return;

    // 600ms 一次 —— 太快会连续瞬移，容易被服务端拉回
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
    if (g_tpMode.load() == 1) {                       // 前移
        float yaw, pitch;
        if (!read_view_angles(self, yaw, pitch)) return;
        const float PI = 3.14159265358979f;
        const float ry = yaw * PI / 180.0f;
        nx = x - std::sin(ry) * static_cast<float>(d);
        nz = z + std::cos(ry) * static_cast<float>(d);
    } else {                                          // 上升
        ny = y + static_cast<float>(d);
    }

    lastAt = now;
    // ★★ 2.4.5：改走 tp_abs —— 内部做 ΔY 补偿，否则 teleportTo 会把 Y 再抬一次，
    //   选 Forward 也会持续爬升（「不是前进反而上天」）。
    tp_abs(self, nx, ny, nz);
    g_tpCount.fetch_add(1);
    LOGI("teleport mode=%d %.1f -> (%.2f,%.2f,%.2f) total=%u",
         g_tpMode.load(), d, nx, ny, nz, g_tpCount.load());
}

// ─────────────────────────── Hitbox ───────────────────────────

/**
 * Hitbox —— Actor::setSize(0xD8E8CB8)，参数是 Vec2*（{宽度,高度}）。
 * 反编译确认它写的就是 AABBShapeComponent(+0x290) + 0x18，与 getWidth 同源。
 * ★ 游戏每 tick 会覆写该组件 → 持续施加。
 *
 * ★★ 2.4.5：加 Target 下拉（4 档）。语义 = **额外把哪些实体的箱子一起放大**：
 *     Self(0)     只改自己 —— 老行为，默认
 *     Living(1)   自己 + 周围生物（怪物/动物）
 *     Players(2)  自己 + 周围其他玩家
 *     Both(3)     自己 + 全部
 *   为什么要"改别人"：扩目标的本地 AABB → 本机射线命中判定更容易打中（PvP 常规用法）。
 *   ⚠ 这是**纯客户端本地效果**：只改你本机内存，服务端仍按真实位置/距离校验，
 *     不产生任何服务端可见的异常。
 */
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

    // 自己永远施加 —— 老行为，也是「开了就有反应」的基线
    setSize(reinterpret_cast<void*>(self), sz);
    g_hbWrites.fetch_add(1);

    const int target = g_hbTarget.load();          // 0=Self 1=Living 2=Players 3=Both
    if (target == 0) return;

    // ── 额外对周围实体施加 ──
    //   性能：setSize 走 Houdini 翻译层，20Hz × N 会拖主线程。
    //   只取 g_ents（已按 96 格筛过）+ 32 格内 + 最多 16 个。
    float sx, sy, szz;
    if (!read_actor_pos(self, sx, sy, szz)) return;

    int hit = 0;
    std::lock_guard<std::mutex> lk(g_entMtx);
    for (const auto& e : g_ents) {
        if (hit >= 16) break;
        if (e.p == self) continue;
        const float dx = e.x - sx, dy = e.y - sy, dz = e.z - szz;
        if (dx * dx + dy * dy + dz * dz > 32.0f * 32.0f) continue;
        // target: 1=Living(只生物) 2=Players(只玩家) 3=Both(全都要)
        if (target == 1 && !is_living_entity(e.p))  continue;
        if (target == 2 && !is_player_entity(e.p))  continue;
        setSize(reinterpret_cast<void*>(e.p), sz);
        ++hit;
    }
}

// ─────────────────────── Auto Break（视线挖掘） ───────────────────────

/**
 * Auto Break —— 沿视线做步进射线，破坏碰到的第一个非空气方块。
 *
 * 方向由 yaw/pitch（虚表 [18]/[19]）算：基岩版 yaw 0 朝 +Z，
 * forward = (-sin(yaw)cos(pitch), -sin(pitch), cos(yaw)cos(pitch))。
 * 眼高取 1.62（与 ESP 的相机模型一致）。
 *
 * 自保护：跳过玩家脚下那格和胸口那格 —— 否则会把自己脚下的地面挖掉后掉进虚空。
 */
static void autobreak_tick() {
    if (!g_autobreak.load()) return;
    const uintptr_t gm = g_gamemode.load();
    // ★★ 2.4.5：self 优先从 gm 反推（gm_valid 已证 `*(gm+8)` 可信）；g_player 兜底。
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
        // 不挖自己脚下 / 自己身体所在的两格
        if (p.x == feet.x && p.z == feet.z && (p.y == feet.y || p.y == feet.y + 1)) continue;
        if (block_is_air(bs, p)) continue;
        lastAt = now;
        try_break(gm, p);
        return;
    }
}

// ─────────────────────────── Nuker ───────────────────────────

/**
 * Nuker —— 以玩家为中心，销毁半径 R 立方体内的非空气方块。
 *
 * 用**轮转游标**摊平开销：每 tick 最多检视 40 格、最多真正破坏 1 格，
 * 游标一轮走完就归零。这样无论 R 多大都不会造成单 tick 卡顿。
 */
static void nuker_tick() {
    if (!g_nuker.load()) return;
    const uintptr_t gm = g_gamemode.load();
    // ★★ 2.4.5：self 优先从 gm 反推；g_player 兜底。
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

    // ★ 40 → 16：每个方块都要走一次游戏自己的 getBlock，Houdini 下不便宜。
    //   每 tick 都调，40 次就是每帧几毫秒的主线程占用。
    int budget = 16;
    while (budget-- > 0) {
        const int i = g_nukerCursor;
        g_nukerCursor = (g_nukerCursor + 1) % total;

        const int ax = i % side;
        const int ay = (i / side) % side;
        const int az = i / (side * side);
        const BPos p{c.x + ax - R, c.y + ay - R, c.z + az - R};
        if (p.y < -64) continue;

        // 不碰玩家自身所占的两格
        if (p.x == c.x && p.z == c.z && (p.y == c.y || p.y == c.y + 1)) continue;
        if (block_is_air(bs, p)) continue;

        if (try_break(gm, p)) return;      // 本 tick 成功破一格就收手
    }
}

// ─────────────────────────── Game Mode ───────────────────────────

/**
 * Game Mode —— Player::setPlayerGameType(0xDC4F3B0)，只在用户改下拉时触发一次。
 * 反复施加没有意义（服务端会同步下来），所以用 dirty 标志做一次性动作。
 */
static std::atomic<bool> g_gmDirty{false};

static void gamemode_tick() {
    if (!g_gmDirty.exchange(false)) return;

    const int want = g_gametype.load();
    if (want < 0 || want > 2) return;

    const uintptr_t self = g_player.load();
    if (!self) { g_gmDirty.store(true); return; }     // 玩家还没找到，下 tick 再试

    using SetGameTypeFn = void (*)(void*, unsigned);
    SetGameTypeFn fn = fn_at<SetGameTypeFn>(rva::FN_PLAYER_SET_GAMETYPE);
    if (!fn) return;

    fn(reinterpret_cast<void*>(self), static_cast<unsigned>(want));
    g_gmWrites.fetch_add(1);
    LOGI("setPlayerGameType(%d) total=%u", want, g_gmWrites.load());
}

// ─────────────────── Chest ESP：方块实体扫描（主线程小步走） ───────────────────

/**
 * 分步扫描玩家周围 R 格立方体内的方块实体（箱子/熔炉/潜影盒…）。
 * 每步最多检视 800 格 —— 摊到 tick 上大约 0.3ms，肉眼无感。
 * 扫完一整轮才清空结果集，避免中途闪烁。
 */
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

    // ★ 每批 300（原 800）—— 同上，控制单 tick 的主线程占用
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

// ─────────────── 实体枚举：走游戏正规入口 Dimension::forEachPlayer ───────────────
//
//  比 2GB 堆扫快得多也准得多，但**必须在主线程调用**（它遍历的是游戏的实体表，
//  后台线程撞上主线程改动就是数据竞争）。所以放在 nTick 里 500ms 一次，
//  结果合并进 g_ents（堆扫结果保留，两者互补）。

struct DimCtx {
    std::vector<Ent>* out;
    uintptr_t self;
    int added;
    int rejected;
};

/** 回调返回 true = 继续遍历（游戏侧是 `if ((cb(...) & 1) == 0) break;`） */
static bool dim_ent_cb(void* ctxv, void* p) {
    DimCtx* c = reinterpret_cast<DimCtx*>(ctxv);
    const uintptr_t a = reinterpret_cast<uintptr_t>(p);
    if (!a || a == c->self) return true;
    if (!is_actor(a)) { c->rejected++; return true; }

    float x, y, z;
    if (!read_actor_pos(a, x, y, z)) { c->rejected++; return true; }

    if (c->out->size() < 512) {
        // ★ 2.4.5：isPlayer 必须显式填 —— `Ent{a,x,y,z}` 这种聚合初始化会
        //   把新加的尾部成员值初始化成 false，Target 筛选就全是"非玩家"了。
        Ent e{};
        e.p = a; e.x = x; e.y = y; e.z = z;
        e.src = 1;                                          // dim 回调来源
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

    // ★★ 2.4.8：先把**上一轮**的 dim 来源条目全部清掉，本轮重建。
    //
    //   旧写法只 push_back 从不清理，两个后果：
    //     ① 实体被游戏销毁后条目仍留在表里 → **悬垂指针**。消费端
    //        （KillAura / Hitbox / ESP / nametags）拿它做 hot 读 → SIGSEGV →
    //        在 Houdini 下 siglongjmp 跳过翻译层解锁，把翻译缓存锁永久留下
    //        → 过一会整个进程卡死被杀。**这是和扫描线程并列的第二大信号源。**
    //     ② g_ents 很快顶到 512 上限，之后新实体再也进不来；而且每次刷新都要
    //        在 push 前做 O(n²) 去重（512×512）。
    //   堆扫条目（src=0）保留 —— 它们由 scan_once 每轮整体 swap 重建，本来就新鲜。
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

// ═════════════ 主线程：第三批功能（hook 型 + 位移型） ═════════════
//
//  这一批全部避开「猜偏移」：
//   · FOV / Full Bright / Freecam / Reach / NoFall / Criticals / Ping / TPS
//     靠的是 hk:: 那 7 个 hook（地址都是实测函数起点）；
//   · Speed / Sprint / Velocity / Auto Respawn 靠 Actor::teleportTo（已定案）；
//   · Auto Tool 靠「按虚表特征动态认出 PlayerInventory*」，不硬编码偏移；
//   · Xray 靠 BlockSource::getBlock 扫方块 + 按「方块类型出现频次」判稀有度
//     （石头/泥土出现最多，矿石出现最少）—— 不需要方块名也能找出矿石。

/** 沿视线水平前移 dist 格（Speed / Sprint 共用）
 *  ★★ 2.4.5：改 tp_abs（ΔY 补偿）—— 老代码把当前 y 原样传回，
 *      teleportTo 每次再抬 Δ → Speed 20Hz / Sprint 10Hz 全程垂直爬升。 */
static void nudge_forward(double dist, std::atomic<unsigned>& counter) {
    const uintptr_t self = g_player.load();
    if (!self) return;

    float x, y, z, yaw, pitch;
    if (!read_actor_pos(self, x, y, z)) return;
    if (!read_view_angles(self, yaw, pitch)) return;

    const float PI = 3.14159265358979f;
    const float ry = yaw * PI / 180.0f;
    const float d  = static_cast<float>(dist);
    // 基岩版 yaw=0 朝 +Z：forward = (-sin(yaw), *, cos(yaw))
    tp_abs(self, x - std::sin(ry) * d, y, z + std::cos(ry) * d);
    counter.fetch_add(1);
}

/** Speed —— 每 50ms 沿视线前移 0.30 格（≈6 格/秒，原版步行 4.317） */
static void speed_tick() {
    if (!g_speed.load()) return;
    static uint64_t lastAt = 0;
    const uint64_t now = now_ms();
    if (now - lastAt < 50) return;
    lastAt = now;
    nudge_forward(g_speedDist.load(), g_speedSteps);
}

/** Sprint —— 每 100ms 沿视线前移 0.80 格（自动前进，跑图用） */
static void sprint_tick() {
    if (!g_sprint.load()) return;
    static uint64_t lastAt = 0;
    const uint64_t now = now_ms();
    if (now - lastAt < 100) return;
    lastAt = now;
    nudge_forward(g_sprintDist.load(), g_sprintSteps);
}

/**
 * Velocity —— 击退抑制。
 *
 *  拿不到 Knockback / setMotion 那类函数（函数合集里确实没有），所以走
 *  「位移回弹」：每 tick 记位置，若单 tick 位移超过阈值就判定为被击退，
 *  用 teleportTo 把玩家拉回上一帧位置。
 *    · 原版步行 ≈ 0.21 格/tick，疾跑 ≈ 0.28 格/tick
 *    · 默认阈值 0.45 格/tick —— 明显高于正常移动，又低于典型击退
 *  用 read_vec3（浮点位置）而不是 read_actor_pos，避免整数回退造成假触发。
 */
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
        // ★★ 2.4.5：改 tp_abs（ΔY 补偿）。
        //   老代码直接 tp(back) 把 ly 原样传回 → teleportTo 再抬 Δ →
        //   每次回弹都给玩家加一点 Y，「防击退」反而把人弹上天。
        tp_abs(self, lx, ly, lz);
        g_velBlocks.fetch_add(1);
        return;                       // 基准不动，下一帧继续比
    }
    lx = x; ly = y; lz = z;
}

/**
 * 探出 PlayerInventory* —— 判据是「它指向的对象首字 == PlayerInventory 虚表」，
 * 这是定义性判据，比任何硬编码偏移都可靠。找到一次就把偏移缓存下来。
 */
static uintptr_t player_inventory(uintptr_t self) {
    const uintptr_t base = g_libBase.load();
    if (!base || !sane_ptr(self)) return 0;
    const uintptr_t want = base + vt::PLAYERINVENTORY;

    // ★ 全部走 hot 版（进程内 memcpy）—— 这个函数在主线程被 autotool/scaffold 调，
    //   Houdini 下一次 process_vm_readv ≈ 145us，下面那个 486 轮循环用 rd<> 就是 486 次
    //   syscall ≈ 70ms，每 250ms 一发 → 和 find_gamemode 同款的 ANR 来源。
    if (g_invOff) {                                     // 上次找到过
        const uintptr_t p = mem::rd_hot<uintptr_t>(self + (uintptr_t)g_invOff);
        if (sane_ptr(p) && mem::rd_hot<uintptr_t>(p) == want) return p;
        g_invOff = 0;                                   // 失效 → 重探
    }

    // 首选值快路径 —— 实测就落在 0xC18，交叉证据：Player::getCarriedItem(0xDC3B6FC)
    // 读的正是 `[a1+0xC18]`。这一条命中率高，省掉整轮 body 扫。
    {
        const uintptr_t p = mem::rd_hot<uintptr_t>(self + (uintptr_t)off::PLAYER_INV_HINT);
        if (sane_ptr(p) && mem::rd_hot<uintptr_t>(p) == want) {
            g_invOff = off::PLAYER_INV_HINT;
            return p;
        }
    }

    // 探不到就别每 250ms 都拿 640 个指针去问 /proc/self/maps —— 3 秒内只探一次
    static uint64_t lastFail = 0;
    const uint64_t now = now_ms();
    if (lastFail && now - lastFail < 3000) return 0;

    // ★★ 2.4.2：改成**逐 8 字节进程内读**（原来整块 memcpy 进 body2 再扫）。
    //   整块读只要尾部跨到一个不可读页就整块返回 0（guarded_copy 是全有或全无），
    //   而 Player 的 body 4864 字节必然跨页 → 整条探针永久失效、Auto Tool 从不切槽。
    //   逐 8 字节每次只碰一个 qword，几乎不可能跨页；486 次 ≈ 几十 us，可忽略。
    for (int o = off::PLAYER_BODY_SCAN_LO; o + 8 <= off::PLAYER_BODY_SCAN_HI; o += 8) {
        const uintptr_t p = mem::rd_hot<uintptr_t>(self + (uintptr_t)o);
        if (!sane_ptr(p)) continue;
        if (mem::rd_hot<uintptr_t>(p) == want) { g_invOff = o; lastFail = 0; return p; }
    }
    lastFail = now;
    return 0;
}

/**
 * Auto Tool —— 手上不是方块时，在快捷栏 0..8 里滚一格，直到滚到方块为止。
 *
 *   Player::getCarriedItem(0xDC3B6FC)     读当前手持
 *   ItemStackBase::isBlock(0xE2CFA74)     判断是不是方块
 *   PlayerInventory::selectSlot(0xDC41130) 切槽（第 3 参恒 false —— 反编译里
 *                                          true 分支直接返回全局空物品，不是"切槽"）
 *
 * 每 250ms 切一格，切完立刻停 —— 找到方块就不再动，不会一直抖。
 */
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
        g_atSlot.store(-1);                        // 已经是方块 → 收工
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

/**
 * Xray —— 矿物扫描。
 *
 *   不用方块名也能分类：同一个 BlockSource 里，石头/泥土/空气是**绝对多数**，
 *   矿石是**少数**。所以扫完一轮后统计「每种方块描述符(Block+0x90)出现几次」，
 *   出现次数低于「最多的那种的 12%」的就算稀有 → 画框。
 *   再配合 Full Bright，挖矿时能直接看见矿脉位置。
 *
 *   分步扫（每批 800 格），一轮扫完才整体替换结果集，避免闪烁。
 */
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

    // 描述符 → 出现次数（本轮到目前为止）
    static uintptr_t sDesc[64];
    static int       sCnt[64];
    static int       sKinds = 0;
    static std::vector<std::pair<uintptr_t, BPos>> sSeen;

    if (i == 0) { sKinds = 0; sSeen.clear(); }

    // ★ 每批 200（原 800）。Houdini 下一次 getBlock + 读描述符 ≈ 十几 us，
    //   800 个就是 10ms+，而这里每 60ms 触发一次 —— 主线程被切成碎片。
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
    if (!wrapped) return;                       // 还没扫完，不动结果集

    int maxCnt = 1;
    for (int j = 0; j < sKinds; ++j) if (sCnt[j] > maxCnt) maxCnt = sCnt[j];
    const int cut = std::max(3, (int)(maxCnt * 0.12f));

    std::vector<BPos> rare;
    rare.reserve(256);
    for (const auto& kv : sSeen) {
        int k = -1;
        for (int j = 0; j < sKinds; ++j) { if (sDesc[j] == kv.first) { k = j; break; } }
        if (k < 0 || sCnt[k] > cut) continue;   // 大众方块（石头/泥土）跳过
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

/**
 * Auto Respawn —— 死亡 / 重生后自动回到记录的安全点。
 *
 *   客户端拿不到「重生」事件，但能观测到两个可靠信号：
 *     ① g_player 指针变了（实体被重建 → 玩家对象换了）
 *     ② y 比记录的落点低了 40 格以上（掉进虚空 / 被送上天空后坠落）
 *   命中任一条就 teleportTo 回安全点，并把基准更新到当前位置，避免反复拉。
 *   正常行走时缓慢刷新安全点（只在 y 没明显下降时刷）。
 */
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
        // ★★ 2.4.5：走 tp_abs（ΔY 补偿），与其余位移功能统一。
        tp_abs(self, sx, sy, sz);
        g_respawns.fetch_add(1);
        sx = x; sy = y; sz = z;
        return;
    }
    if (y >= sy - 2.0f) { sx = x; sy = y; sz = z; }
}

/** 每秒结算一次 pps / tps（由 nTick 调） */
static void net_rate_tick() {
    static uint64_t lastAt = 0;
    const uint64_t now = now_ms();
    if (now - lastAt < 1000) return;
    lastAt = now;

    g_pktPps.store((int)g_pktWindow.exchange(0));
    g_tpsNow.store((int)g_ntWindow.exchange(0));
}

// ═══════════════════════════════ 扫描线程 ═══════════════════════════════

static void scan_loop() {
    LOGI("scan loop up (tid=%d)", (int)::gettid());

    // ★★★ 2.4.14：**第一行**就把本线程登记为「扫描线程」。
    //   之后该线程调用任何 hot 读 / guarded_copy 都会被直接拒绝，永不产生信号。
    //   意义：Houdini 下从 memcpy 里 siglongjmp 出来会把翻译缓存锁留死（2.4.7 卡死），
    //   而且信号恢复路径要读 thread_local（emutls）——实机 09:30:09 的致命崩溃
    //   正是 scan_once → actor_vt_learn → guarded_copy → `__emutls_get_address`
    //   的第二跳。把整条线程设成「信号禁区」＝ 这一类崩溃从根上不可能再发生。
    mem::mark_scan_thread();

    // ★ 降优先级 —— Houdini 下这个线程读内存很贵，绝不能和渲染/游戏主线程抢 CPU。
    //   nice=10 让它只在别人不忙的时候跑。这是 ANR 的第二道保险（第一道是 find_gamemode
    //   搬出主线程 + BUDGET 从 384MB 降到 96MB）。
    ::setpriority(PRIO_PROCESS, 0, 10);

    for (;;) {
        const Base b = game_module();
        if (!b.lo) {
            ::usleep(500 * 1000);
            continue;
        }
        g_libBase.store(b.lo);
        g_libEnd.store(b.hi);

        // ★★ 2.4.10：**不要**在 .so 刚 map 进来的那一瞬间就打补丁。
        //   hook 的实际装载时刻 = 扫描线程第一次看到 libminecraftpe.so（≤500ms 后），
        //   而那一刻 linker 很可能还在给这个 .so 做重定位 / 初始化 ——
        //   我们对它的 .text 段 mprotect(RWX) 并写 16 字节跳转桩，等于
        //   在人家还在搬家的房子里改承重墙。实机表现就是「加载一会直接闪退」。
        //   改成「连续看到 3 轮（≈1.5s）才算稳定」再装：仍然远早于
        //   normalTick / getFov 这些热函数被 Houdini 翻译（那是进世界 / 开渲染之后），
        //   所以「常装」的语义完整保住，只是把那段危险窗口躲开。
        static int stableRounds = 0;
        if (stableRounds < 3) ++stableRounds;
        if (stableRounds >= 3) ensure_hooks();

        // ── GameMode 探测（搬到这里了，不在主线程）──────────────────────
        //   self 由 normalTick hook 常装提供，稳定可靠；探到一次就缓存偏移，
        //   之后每次只要 2 次进程内 memcpy 复核。
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

        // self 现在主要来自 normalTick hook（免费、安全、主线程）。只有两种情况
        // 才值得扫堆：① 还没拿到 self；② 有功能真的要枚举实体。
        // 这样默认状态下扫描线程基本在睡，CPU 占用≈0。
        const bool needEnt = g_esp.load() || g_chest.load() || g_xray.load()
                             || g_kaura.load() || g_hitbox.load();

        // ★★ 2.4.2：self / GameMode 还没拿到时**必须快扫**。
        //
        //   实测 MuMu 的 Houdini 会把 LocalPlayer::normalTick 的翻译结果缓存住，
        //   inline hook 打上去是**静默失效**的（不崩、不报错、g_selfSeen 永远 false）。
        //   这时 self + GameMode 的唯一来源就是这个堆扫。
        //   旧逻辑在 !g_selfSeen 时 sleep 6 秒 —— 96MB/轮 × 6s ≈ 16MB/s，
        //   2GB 的堆要扫 2 分钟，用户开了 KillAura 之后几分钟没反应 = "功能用不了"。
        //   改成 400ms 间隔（单轮扫描本身 ~220ms，nice=10 不抢渲染）；一旦
        //   self + gm 都到手就立刻回落低速。
        const bool needSel = (g_player.load() == 0) || (g_gamemode.load() == 0);

        // ★★ 2.4.9：needSel 的快扫改成**逐级退让**。
        //
        //   原来的 400ms 恒定快扫有个盲区：**人在标题界面 / 大厅时根本还没有 LocalPlayer**，
        //   于是 needSel 永远为真，扫描线程就永远 400ms 一轮（单轮实测 221ms）→
        //   常年吃掉约 35% 的单核。MuMu 这台虚拟机只有 4 核，实测 `load average 4.16`
        //   就是满载 —— 在这种已经饱和的环境里，任何持续 CPU 都在挤压渲染线程，
        //   而渲染一卡，宿主 GPU 后端就更容易撑不住（实机表现：surfaceflinger
        //   `no suitable EGLConfig found` → 整机崩）。
        //
        //   现在：真的一直找不到 → 间隔 400 → 800 → 1600 → 2500ms 递增；
        //   一旦拿到 self/gm、或用户开了需要枚举实体的功能，立刻回到 400ms 快扫。
        //   「进世界后几秒内出功能」这一步的响应速度不受影响（那时才刚有 LocalPlayer）。
        static int missRounds = 0;
        if (needEnt || g_player.load() || g_gamemode.load()) missRounds = 0;
        else if (missRounds < 1000) ++missRounds;

        if (needSel || !g_selfSeen.load() || needEnt) {
            scan_once();
            int ms;
            if (needEnt) {
                ms = 2000;
            } else if (needSel) {
                // ★★ 2.4.13：退让上限从 4000ms 抬到 8000ms。
                //   用户报「加载到 5/6 卡顿然后闪退」—— 那一阶段游戏在生成地形 +
                //   初始化实体，4 核已经跑满；而此时我们**连 self 都还没拿到**
                //   （人还没进世界），扫得再勤也是白扫，纯粹在跟地形生成抢核。
                //
                //   注意 missRounds 的归零条件是 `g_player || g_gamemode`：
                //   所以**一旦进世界（拿到 self）就立刻回到 400ms 快扫**，
                //   "进世界后出功能"的响应速度完全不受影响。
                //
                //   另外 step 从 /8 收到 /3：前 1.2 秒仍然 400ms 快扫（覆盖
                //   "模块晚注入、游戏其实已经在世界里"的情况），之后就快速退下去。
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

// ═══════════════════════════════ JNI ═══════════════════════════════

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
    // ★★ 2.4.10：这里**不再**调 ensure_hooks()。
    //   nInit 是 handleLoadPackage 阶段从 JNI 调进来的（Application 还没起来），
    //   此刻 libminecraftpe.so 十有八九根本没 map —— 调了也只会被开头的
    //   `if (!base) return;` 挡掉，纯属噪音。hook 的唯一装载点收敛到扫描线程，
    //   并且在那里额外等 .so 稳定（见 scan_loop 里的 stableRounds）。
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
               // ★ self 的 vptr（相对模块基址）。正确值应当是 0x1270FA98（LocalPlayer 虚表）。
               //   若这里不是它 → g_player 指向的根本不是本地玩家，所有用它的功能都会半死。
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

/**
 * 玩家所在方块坐标 —— "X / Y / Z"；读不到返回空串。
 *
 * 走的就是 buildBlock 自己那条权威路径（Player+0x288 → BlockPos*），
 * 读不到时退回浮点位置向下取整。只读内存，主线程 20Hz 调用完全安全。
 */
JNIEXPORT jstring JNICALL
Java_com_onyx_clickgui_feature_Native_nPlayerPos(JNIEnv* env, jclass) {
    // ★ 2.4.2：hook 在 Houdini 上可能静默失效（g_player=0）→ 从 GameMode 反推
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

/**
 * ★ 2.4.9：给 setFeature 的日志做"只在变化时打印"。
 * 返回该名字上一次的值；*known 表示之前是否见过这个名字。
 * 名字表上限 64（实际 ~30 个），满了就不再记 —— 顶多是多打几条日志，不会出错。
 */
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
        // 只在「关 → 开」那一刻施加一次；反复下发（FeatureBus 每次状态变化都会全量重发）
        // 不再重复调 setPlayerGameType，免得和服务端来回打架。
        if (now && !was) g_gmDirty.store(true);
        if (!now)        g_gmDirty.store(false);      // 关掉 → 撤销未执行的待办
    }
    // ── 第三批：hook 型 + 位移型 ──
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
    // ★★ 2.4.9：只在**值真的变了**时才打日志。
    //   背景：FeatureBus.bind() 每次被调（watchdog / onResume / onWindowFocusChanged）
    //   都会重推全部 26 个功能 → 一次会话 286 行 "setFeature xxx = 0" 纯噪音
    //   （用户反馈"日志 589 行太多"）。第一次见到某名字时也打一次，保留初始化快照。
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
            // ★★ 2.4.4：Type 只记参数，**不在模块关着的时候执行**。
            //   用户明确反馈过这个 bug：开关没开，只把下拉改成 Surivival 就真的切过去了。
            const int t = (int)v;
            if (g_gametype.load() != t) {
                g_gametype.store(t);
                if (g_gamemodeOn.load()) g_gmDirty.store(true);   // ← 开关开着才动作
            }
        }
        // ── 第三批 ──
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
            // ★★ 2.4.5：killaura 现在有**两个**下拉（Mode + Target）→ 必须靠 key 区分。
            //   老代码不看 key，一律当成 Mode —— 那样 Target 会把 Mode 覆盖掉。
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

/**
 * 主线程每帧驱动 —— 只做必须跑在游戏主线程的事。
 * attack / buildBlock / destroyBlock / setAbility / setSize / teleportTo /
 * jumpFromGround / setPlayerGameType 全部在这一条线上，因为游戏对这些调用
 * 有线程亲和性（内部会构造闭包入队 / 碰线程局部状态）。
 */
JNIEXPORT void JNICALL
Java_com_onyx_clickgui_feature_Native_nTick(JNIEnv*, jclass) {
    g_mainTicks.fetch_add(1);

    // 时间戳：hook 没装上时（normalTick 没 hook）这里是唯一的时钟源
    const uint64_t now = now_ms();
    if (!g_nowMs.load()) g_nowMs.store(now);

    // 本地玩家的 EntityContext（= player + 0x08）—— isOnGround hook 靠它区分
    // 「自己」和别的实体，绝不能搞错，否则 NoFall/Criticals 会把物理系统带歪
    const uintptr_t self = g_player.load();
    g_selfCtx.store(self ? self + (uintptr_t)off::ACTOR_ENTITY_CONTEXT : 0);

    // ★ GameMode 的**探测不在主线程做**：
    //   Houdini 下一次 process_vm_readv ≈ 145us，旧实现探一次要 1000+ 次 syscall ≈ 150ms，
    //   每 300ms 来一发 → 主线程半数时间卡在 syscall 里 → 系统弹「应用无响应」。这就是那个 ANR。
    //   现在主线程只做「失效检查」（2 次进程内 memcpy，纳秒级），真正的探测丢给扫描线程。
    if (self) {
        const uintptr_t cur = g_gamemode.load();
        // ★★ 2.4.5：判据统一到 gm_valid()。
        //   老判据写死 `vptr == 0x12AAB340`（基类虚表），而活对象挂的是
        //   SurvivalMode 虚表（0x12AAB3F8）—— 于是**真的 gm 会在 50ms 内被这句清掉**，
        //   扫描线程每轮又把它探回来，来回打架（表现为 GameMode 时有时无、
        //   依赖 gm 的 Scaffold / KillAura 全在 NG 计数上跳）。
        //   更要命的是反过来：析构后的尸体恰好挂基类虚表 → 老判据认为它"有效"、留着用。
        //   gm_valid() 同时看两种虚表 + `*(gm+8)` 能不能双向往返，两头都堵死。
        if (cur != 0 && !gm_valid(cur, self)) {
            g_gamemode.store(0);          // 对象被回收 / 换维度 → 扫描线程会重探
        }
    }

    // ★ 2.4.4 诊断：每 500ms 读一次自己的位置 —— 这条链（*(actor+0x288) → float*）
    //   是 Teleport / Speed / Sprint / Scaffold / ESP / NameTags / Respawn 的共同前提。
    //   状态串里的 pos=ok/fail 直接反映它通不通，比一个个功能去试快得多。
    {
        static uint64_t lastPos = 0;
        if (self && now - lastPos >= 500) {
            lastPos = now;
            float px, py, pz;
            if (read_actor_pos(self, px, py, pz)) g_posOk.fetch_add(1);
            else                                  g_posFail.fetch_add(1);
        }
    }

    // 战斗 / 移动
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
    // 世界
    autobreak_tick();
    nuker_tick();
    gamemode_tick();
    autotool_tick();

    // 实体枚举走游戏正规入口，500ms 一次（必须主线程）
    static uint64_t lastDim = 0;
    if (now - lastDim >= 500) { lastDim = now; dim_refresh_entities(); }

    // 方块实体（ChestESP）分步扫，100ms 走一批
    if (g_chest.load()) {
        static uint64_t lastChest = 0;
        if (now - lastChest >= 100) { lastChest = now; chest_scan_step(); }
    }

    // 矿物扫描（Xray）分步走，60ms 一批
    if (g_xray.load()) {
        static uint64_t lastXray = 0;
        if (now - lastXray >= 60) { lastXray = now; xray_scan_step(); }
    } else if (g_xrayProgress.load() != 0) {
        g_xrayProgress.store(0);
        std::lock_guard<std::mutex> lk(g_xrayMtx);
        g_xrayPos.clear();
        g_xrayRare.store(0);
    }

    // 每秒结算 pps / tps
    net_rate_tick();
}

JNIEXPORT jint JNICALL
Java_com_onyx_clickgui_feature_Native_nMainTicks(JNIEnv*, jclass) {
    return (jint)g_mainTicks.load();
}

/**
 * ESP 数据：jfloatArray，每 4 个一组 = 一个屏幕矩形（x0,y0,x1,y1，0..1 归一化）。
 * null / 空数组 = 这帧没有可画的（没开 ESP / 位置或朝向没读到 / 实体全在视锥外）。
 * Java 侧每帧调用 —— 只读内存，主线程安全。
 */
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

/**
 * Name Tags 数据：每 3 个 float 一个实体 —— (x0, y0, dist)
 *   x0/y0 = 头顶那一点的归一化屏幕坐标（0..1，左上为原点），dist = 格数。
 * Java 侧就地画「名字 + 距离」。完全没有后端依赖：实体列表（g_ents）本来就有，
 * 投影链路和 ESP 共用同一套相机模型。
 */
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
        // 瞄头顶上方一点 —— 牌子不会糊在实体身上
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

/**
 * 网络统计：float[3] = { 发包间隔抖动ms, 每秒包数, 客户端 TPS }
 *   ★ 客户端拿不到真正的 RTT（收包路径这一版没有可用地址），所以给的是
 *     **发包节奏** —— 面板上如实标成 NET/Δ，不编造假的 ms 值。
 */
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

}  // extern "C"
