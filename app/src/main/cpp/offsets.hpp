// ══════════════════════════════════════════════════════════════════════════
//  offsets.hpp —— 网易版《我的世界》Android ARM64（netease-3.9.15.297907.so）
//
//  内核 format_version 1.21.120（与 UWP 1.21.120.4 同版）。
//  so 符号被剥离，但 RTTI 完整 —— 全部靠 typeinfo → vtable → 槽位 定位。
//  so 在 IDA 里 imagebase = 0，所以下面所有地址都是「相对模块基址的 RVA」。
//
//  游戏文件 SHA256 : 953d4ebd1caf4d806597e20d4319538a265282a6e7b336ddfea14ff288c0c73d
//  模块大小        : 336,101,376
//  运行时地址      = module_base + RVA
//
//  ★ 标 [已定案] 的是有完整证据链的，可以放心用。
//  ★ 2.3.0 起 UI 里【不再有 N/A】：所有功能都有真后端了。要么真调函数，
//    要么用下面这套 AArch64 inline hook（见 onyxmc.cpp 的 hk::install）挂到
//    已定案的 7 个 Hook 点上。装不上时 nStatus 会报 hook=n/7，不会静默。
//
//  ⚠ 三处调用约定陷阱，写代码前必读：
//    ① Actor::isOnGround 的第一参是 **EntityContext***（= Actor + 0x08），
//       不是 Actor*。证据：Mob::jumpFromGround(0xCEF9270) 内部就是
//       `sub_DC216B4(a1 + 8)` 这么调的。传 Actor* 会读到错位的哈希表指针。
//    ② setSize / teleportTo / getBlock / getBlockEntity / destroyBlock
//       的向量/坐标参数**按指针传**（AArch64 上 `const Vec3&` = 一个指针）。
//    ③ 所有会「改变游戏状态」的调用（attack / buildBlock / destroyBlock /
//       teleportTo / setAbility / setSize / setPlayerGameType / jumpFromGround /
//       selectSlot）必须跑在**游戏主线程**（nTick 里），从后台线程调会踩
//       线程局部状态 —— 这是 UWP 侧已经吃过一次的坑。
// ══════════════════════════════════════════════════════════════════════════
#pragma once

#include <cstdint>

namespace off {

// ───────────────────────── 类内字段偏移 ─────────────────────────

/** GameMode → Player*  ★[已定案] ARM64 实证：Player::getAttackRange(sub_E3666F4) 读 [x0+8] */
constexpr int GM_PLAYER = 0x08;

/**
 * Player → GameMode*  [偏移不猜，运行期探针发现]
 *   ★ 不再依赖堆扫：`g_gamemode` 由 `find_gamemode(self)` 直接在 Player 的 body 里
 *     探「首字 == GameMode 虚表 且 [+0x08] == self」的指针 —— 与 AutoTool 认
 *     PlayerInventory 完全同一套路。下面这个值只当**首选试值**，探针会用实测值
 *     覆盖它（状态串里的 `gmoff=0x..`）。
 *   探针判据的依据：GameMode::attack 内部 `sub_D8E8B88(*(this+8))` 取 Player，
 *   即 GM_PLAYER(0x08) 是「GameMode → Player」的权威偏移，反向复核就是
 *   `*(gm + 0x08) == self`。
 */
constexpr int PLAYER_GM = 0xA78;

/**
 * Player → PlayerInventory*  [偏移不猜，运行期探针发现]  ★实测落在 0xC18
 *   交叉证据：Player::getCarriedItem(0xDC3B6FC) 读的就是 `[a1+0xC18]`。
 */
constexpr int PLAYER_INV_HINT = 0xC18;

/**
 * ★★★ Actor → 位置指针（`float*`，指向 {x, y, z} 三个连续 float）
 *   ★[已定案·本次 IDA 实证]
 *     · Actor 虚表[17] = sub_D8E96F0： `return **(float **)(a1 + 648);`   ← 648 = 0x288
 *     · sub_D8E1C5C(a1)：              `return *(_QWORD *)(a1 + 648);`
 *     · Player::teleportTo(0xDC31E84) 内部：`v12 = sub_D8E1C5C(a1); v13 = *(float*)v12;`
 *     · GameMode::buildBlock(0xDAC4764) 内部：`sub_D8E1C5C(Player)` 取玩家位置
 *   ⇒ `*(actor + 0x288)` 是 **float***，解引用即得 X；Y/Z 在 +4 / +8。
 *
 *   ⚠ 这里踩过一次坑：旧值 `ACTOR_POS = 0x218` 是从 UWP 版沿用的，
 *     在这一版上是错的 → `read_actor_pos` 永远失败 → ESP 的 espFail 每帧 +1
 *     （实机 157751 次）、KillAura/Scaffold/Speed/Teleport 全部静默失效。
 *     另外 0x288 曾被误当成「BlockPos*」（整数坐标），也是错的 —— 它是位置指针。
 */
constexpr int ACTOR_POS_PTR = 0x288;

/** Actor → AABB*（6 个 float：minX..maxZ）  [未定案，暂未使用] */
constexpr int ACTOR_AABB = 0x220;

/**
 * Actor → `*(actor+0x250)` 的组件（sub_D8DD58C = `return *(_QWORD*)(a1 + 592)`）
 *   setPlayerGameType(0xDC4F3B0) 里 `v5 = sub_D8DD58C(a1); (*(*(v5)+1184))(v5)`，
 *   说明它是个**带虚表的对象**（很可能是 Level），留档备用。
 */
constexpr int ACTOR_LEVEL_PTR = 0x250;

/** Actor → 唯一 id（uint64）  [未定案] */
constexpr int ACTOR_ID = 0x18;

/**
 * ~~Player → BlockPos*~~  ★ 已废弃（原本写成 0x288，是错的）
 *   0x288 真正的含义见上面 ACTOR_POS_PTR —— 它是**位置指针 float***，不是整数 BlockPos。
 *   现在「所在方块坐标」一律由 `read_feet_block()` 从浮点位置 floor 得到。
 */
// constexpr int PLAYER_BLOCKPOS = 0x288;   // ← 别再启用

/**
 * Actor → EntityContext（16 字节：{ 组件表指针@+0, 实体 id uint32@+8 }）
 *   ★[已定案] Actor+0x08 起就是 EntityContext。证据：
 *     · Actor::isOnGround(0xDC216B4) 读 [a1+8]=表指针、[a1+16]=id，而
 *       Mob::jumpFromGround 传的就是 `mob + 8` —— 两者指向同一对字段；
 *     · Actor::getWidth(0xD8F78DC) 读 [a1+592]/[a1+656]，说明对象头之后
 *       紧跟的就是 EntityContext，AABBShapeComponent 指针在 +0x290。
 */
constexpr int ACTOR_ENTITY_CONTEXT = 0x08;

/**
 * Actor → AABBShapeComponent*（+0x18 = 碰撞宽度 float，+0x1C = 高度 float）
 *   ★[已定案] 双重证据：
 *     · Actor::getWidth(0xD8F78DC)  = `*(float*)(*(QWORD*)(a1+656) + 24)`
 *     · Actor::setSize(0xD8E8CB8)   = `*(float32x2_t**)(a1+656)` 然后写 `v3[3]`（= +0x18，8 字节）
 *   两个函数读写的正是同一处。注意这是【尺寸】不是生命值。
 */
constexpr int ACTOR_AABB_SHAPE = 0x290;

/** AABBShapeComponent → 宽度/高度（两个连续 float） */
constexpr int AABB_SHAPE_SIZE = 0x18;

/** Player → 手持物品容器（getCarriedItem 读此；+0x118 空判定，+0x120 物品表） */
constexpr int PLAYER_CARRIED = 0xC18;

/**
 * Player → PlayerInventory*  【运行期动态发现，不硬编码】
 *
 *   旧的「Player+0x288 就是 PlayerInventory*」这条说法与另一条证据冲突
 *   （GameMode::buildBlock 反编译显示 *(Player+0x288) 是个 BlockPos*）。
 *   为了不拿错指针去调 selectSlot 把游戏搞崩，这里改成**探针式发现**：
 *   在 Player 对象前 0x1400 字节里找一个「解引用后首字 == PlayerInventory 虚表」
 *   的指针 —— 这是定义性的判据，找到就缓存住 offset。
 *   找不到时 Auto Tool 只是不切槽，绝不用可疑指针调用。
 */
constexpr int PLAYER_BODY_SCAN_LO = 0x100;
constexpr int PLAYER_BODY_SCAN_HI = 0x1400;

}  // namespace off

// ───────────────────────── 虚表 RVA（imagebase 0） ─────────────────────────

namespace vt {

constexpr uintptr_t LOCALPLAYER = 0x1270FA98;   // ★[已定案] LocalPlayer 虚表（273 槽）
constexpr uintptr_t PLAYER      = 0x12ACAA28;   //  Player 虚表
constexpr uintptr_t MOB         = 0x12A68098;   //  Mob 虚表（197 槽）
constexpr uintptr_t ACTOR       = 0x12A8D808;   //  Actor 虚表（156 槽）
//  ★★★ GameMode 是**抽象基类**，活着的对象不是它！
//
//  IDA 实证（虚表区 0x12AAB200..0x12AAC200 全枚举 + RTTI 反查）：
//      0x12AAB340  offset-to-top=0, typeinfo=_ZTI8GameMode      ← 基类虚表
//      0x12AAB3F8  offset-to-top=0, typeinfo=_ZTI12SurvivalMode ← 本地玩家用的是这个
//
//  只有下面三种对象首字 == base + GAMEMODE(0x12AAB340)：
//    ① 构造中途（基类 ctor 跑完、派生 ctor 还没改 vptr）
//    ② **已析构/已释放的对象** —— ~SurvivalMode(0xDAD73C4) 在 0xDAD7414 处
//       `ADRL X8, off_12AAB340; STR X8,[X19]` 把 vptr 改回基类虚表！
//    ③ 真的是个裸 GameMode 实例（本作里基本不存在）
//
//  ⇒ 老代码「首字 == base+GAMEMODE 就算 GameMode」这条判据，
//    在实机上命中的**全是②的尸体**。真机证据：gmp=0x779c00126dc0 而 *(gmp+8)=0x6B4
//    （不是 Player*），buildBlock 里 `LDR X0,[gm,#8]` 拿到 0x6B4 →
//    getCarriedItem(0x6B4) → `LDR X8,[0x6B4+0xC18]=[0x12CC]` → SIGSEGV。
//
//  ⇒ 现在唯一可靠的硬判据：**`*(gm + 0x08) == self`**。
//    构造函数实锤（GameMode::GameMode @0xDAC1C48）：
//        0xDAC1C58  ADRL X8, off_12AAB340
//        0xDAC1C64  STP  X8, X1, [X0]     ; [+0]=虚表 [+8]=X1=Player*
constexpr uintptr_t GAMEMODE    = 0x12AAB340;   // GameMode 基类虚表（只应出现在构造/析构窗口）
constexpr uintptr_t GAMEMODE_SURVIVAL = 0x12AAB3F8;  // ★ SurvivalMode 虚表 —— 本地玩家真身
constexpr uintptr_t BLOCKSOURCE = 0x12AEBF48;   //  BlockSource 虚表（67 槽）
constexpr uintptr_t DIMENSION   = 0x12B9D600;   //  Dimension 虚表（Overworld/Nether/TheEnd 共用）

// ── 以下 5 个是「vfunc0 地址」（= 函数合集【四】的 RVA + 0x10）：用于
//    - 动态识别对象类型（拿对象首字跟它比）
//    - 需要按类型定位某对象时当判据
//    实测与函数合集【四】逐条对齐：LocalPlayer 1270FA88+10 / GameMode 12AAB330+10 ...
constexpr uintptr_t LEVELRENDERCAMERA    = 0x1274D0E8;   //  LevelRendererCamera（38 槽）
constexpr uintptr_t LEVELRENDERERPLAYER  = 0x1274D4D8;   //  LevelRendererPlayer（40 槽）
constexpr uintptr_t LOOPBACKPKTSENDER    = 0x12909558;   //  LoopbackPacketSender（14 槽）
constexpr uintptr_t JUMPCONTROL          = 0x12A65598;   //  JumpControl（10 槽；[0] 是 nullsub 析构）
constexpr uintptr_t PLAYERINVENTORY      = 0x12ACB320;   //  PlayerInventory（5 槽，实测 5 槽全是真函数）

// ── 关键虚函数槽位（用「虚表基址 + 槽号*8」直接取函数指针，见下） ──
//
//  ★ GameMode 槽位表（本次 IDA 实测，与 NewUI 独立逆向一致）：
//      [3]  = destroyBlock  0xDAC3388
//      [7]  = buildBlock    0xDAC4764
//      [16] = attack        0xDAC2664
//  ★ Actor 系槽位表：
//      [18] = 插值 yaw      [19] = 插值 pitch
//      [26] = teleportTo（Player/RemotePlayer/ServerPlayer 都是 26）
//  ★ 其余已测槽位：
//      LocalPlayer[29]=normalTick  BlockSource[2]=getBlock
//      Dimension[11]=forEachPlayer  Actor[12]=~Actor
//      LevelRendererCamera[23]=setupFog
//
//  注意：虚表头 8 字节是「到 typeinfo 的偏移」，所以第 N 个虚函数在
//        vtable + 0x10 + N*8；上表的 [N] 已经是从 vfunc0 起算的序号，
//        换算时用 `vtable + 8 + N*8`（+8 跳过 typeinfo 指针本身）。

}  // namespace vt

// ───────────────────────── 函数 RVA ─────────────────────────

namespace rva {

// ═══════════ A. 攻击 / 放置 / 破坏（GameMode 三件套） ═══════════

/**
 * GameMode::attack(void* gm, void* target)   ★[已定案] = 0xDAC2664  GameMode 虚表[16]
 *
 * 证据链（四条独立）：
 *  ① 位于 GameMode 虚表 [16] 槽；
 *  ② 函数头两条指令 `MOV W2,#1` / `B` —— 与 UWP 侧 thunk（sub_14585FF80 传 a3=1）同构；
 *  ③ 函数体先 sub_D8E8B88(*(this+8)) 取 Player，再 operator new(200)+new(48) 构造闭包，
 *     其中 *(q+24) = a2 ——「目标 Actor 存进任务闭包」是 attack 的定义性特征；
 *  ④ 内部走「闭包入队」→ 必须在游戏主线程调用。
 *
 * ★ 只传两个参数。第 3 个参数（manual）由 0xDAC2664 自己设成 1，别自己传、别跳 0xDAC266C。
 */
constexpr uintptr_t FN_GAMEMODE_ATTACK = 0xDAC2664;

/**
 * GameMode::destroyBlock(void* gm, BlockPos const* pos, uint32 face, bool a4, bool a5)
 *   ★[已定案] = 0xDAC3388  GameMode 虚表[3]
 *   反编译确认：`sub_DAC3388(a1, unsigned int* a2, unsigned int a3, char a4, int a5)`
 *   —— 第 2 参是**指针**；函数体第一句就是 `sub_D8DDE34(*(a1+8))`（自取 BlockSource），
 *   所以只要给 gm + 坐标就能用，调用方不必自己找 BlockSource。
 * ★ 主线程调用。
 */
constexpr uintptr_t FN_GAMEMODE_DESTROY = 0xDAC3388;

/**
 * GameMode::buildBlock(void* gm, BlockPos const* pos, uint8 face, void* clickPos)
 *   ★[已定案] = 0xDAC4764  GameMode 虚表[7]
 *
 * 证据链：
 *  ① **骨架孪生**：[6] 槽 0xDAC4750 是一条 20 字节的 tail-call 壳，反汇编为
 *       LDR  X8,[X0]          ; 取虚表
 *       STR  WZR,[X0,#0x40]   ; 清 this+0x40 的状态位
 *       MOV  W3, WZR          ; 第 4 个参数 = 0  ← 就是 Vec3::ZERO 那个"零向量"
 *       LDR  X4,[X8,#0x38]    ; 虚表 +0x38 = 第 7 槽
 *       BR   X4               ; 尾调 [7]
 *     即 `startBuildBlock(pos, face) { flag=0; buildBlock(pos, face, ZERO); }` ——
 *     基岩版 GameMode 里唯一满足「只补一个零值参数、其余原样透传」的一对虚函数，
 *     所以 [6]=startBuildBlock、[7]=buildBlock。
 *  ② **参数形状吻合**：签名 (this, X1, char, char)，第 3 参是 face（转发时原样保留），
 *     第 4 参在 [7] 里只被 `(a4 & 1)` 这种**位测试**使用、全程不解引用 →
 *     与"零向量/可为空的点击位置"完全一致，传 0 不会崩。
 *  ③ **[6] 壳把第 4 参清零**，说明该参数在 ABI 上是单个 8 字节值（bool 或指针），
 *     不是 12 字节的 Vec3 按值 —— 调用时给 nullptr 即可，与游戏自己的调用方式一致。
 *  ④ [7] 函数体开头 `sub_DC3B6FC(*(this+8))` 取 Player、中间 `sub_D8E1C5C(Player)`
 *     读 BlockPos、末尾走 `(*(*(Player)+2040))(Player, ...)` —— 全是"放置方块"链路。
 *
 * ★ 调用：`fn(gm, &clickPos, 1, nullptr)`。第 4 参一律传 0，别自己造 Vec3。
 * ★ 主线程调用。
 */
constexpr uintptr_t FN_GAMEMODE_BUILD = 0xDAC4764;

/** GameMode::startBuildBlock —— [6] 槽的转发壳，一般不用，留档 */
constexpr uintptr_t FN_GAMEMODE_START_BUILD = 0xDAC4750;

// ═══════════ B. 坐标 / 朝向 读取 ═══════════

/** Actor::swing(void* actor)  ★[已定案] = 0x74CAD10 —— 挥手动画（攻击/放置配套） */
constexpr uintptr_t FN_ACTOR_SWING = 0x74CAD10;

/**
 * Player::getAttackRange(void* player) -> float   ★[已定案]
 *
 *   0xDAC72BC（size 0x34）反汇编：
 *       LDR  X0,[X0,#8]        ; ★ 解引用：X0 = *(this+8)
 *       LDR  S0,[X0,#0x18B4]   ; 覆盖值（float）—— 注意是**第二级**对象上的偏移
 *       FCMP S0,#0.0 / B.GT ret
 *       BL   sub_D91045C       ; 返回一个 bool 标志
 *       FMOV S0,#7.0 / FMOV S1,#12.0 / TST W0,#1 / FCSEL S0,S1,S0,NE
 *       RET                    ; → 7.0 或 12.0
 *
 *   ★★ 2.4.5 定位：实机「Reach 只增加了挖方块/放方块距离，攻击距离没变」，
 *      而 2.4.4 只 hook 了 **rangeSqr**（整库仅 1 个代码调用点）——
 *      说明那 1 个调用点走的是方块交互链路。
 *      **range 版有 12 个调用点**，实体攻击的射程判定极大概率在其中。
 *      ⇒ 两个都 hook，覆盖面才完整。状态串 `reach=x/y+z` 里 z 就是这个函数的命中数。
 *
 *   （备注：7 / 12 这两个数跟生存/创造模式相关，`sub_D91045C` 返回的 bool 是模式标志。）
 */
constexpr uintptr_t FN_PLAYER_GET_ATTACK_RANGE = 0xDAC72BC;

/**
 * Player::getAttackRangeSqr(void* player) -> float   ★[已定案] = 0xDAC72F0
 *   同一套逻辑，返回 S0*S0 → 49.0 / 144.0。
 *   实机证据：整库只有 **1 个**代码调用点（0xEDEEC40，宿主函数 0xEDEEA88）。
 *   ⇒ 只 hook 它 = 只覆盖了一条链路，这就是「Reach 对攻击无效」的直接原因。
 */
constexpr uintptr_t FN_PLAYER_GET_ATTACK_RANGE_SQR = 0xDAC72F0;

/**
 * 攻击距离**覆盖值**（float），地址 = `*(Player + 0x08) + 0x18B4`
 *
 *   ★★ 注意是「两级」不是一级：两个 getter 的第一句都是 `LDR X0,[X0,#8]`
 *      （**解引用**），然后才 `LDR S0,[X0,#0x18B4]`。
 *      所以不能用 `wr(self + 0x18B4)` —— 那是解引用前，写进去是错的。
 *      正确写法：
 *          const uintptr_t ctxData = mem::rd_hot<uintptr_t>(self + 8);
 *          mem::wr<float>(ctxData + 0x18B4, v);
 *
 *   语义：> 0 就直接用它（平方版再平方），<= 0 才回落到 7.0 / 12.0。
 *
 *   ⚠ 2.4.5 决定**不走这条路**。理由：`*(Player+8)` 指向的块由引擎自己管理，
 *     它的布局随版本变，而 `0x18B4` 这个偏移只是「读过一次」的实证，不是结构定义。
 *     万一它其实是别的字段，写坏的是引擎内部数据（不是崩，是"说不清的怪行为"）。
 *     改用**两个 getter 全 hook**，覆盖面已经够（range 共 12 个调用点），
 *     而且是纯返回值替换，零副作用。这个常量留作将来的取证线索。
 */
constexpr uintptr_t OFF_PLAYER_ATTACK_RANGE = 0x18B4;

/**
 * Actor::getDimension(void* actor) -> Dimension*   ★[已定案] = 0xD8E7554
 *   反编译：走 `Actor+0x240/0x248` 那对 shared_ptr 弱引用，为空返回 0。
 *   ★ 调用方必须判 0。
 */
constexpr uintptr_t FN_ACTOR_GET_DIMENSION = 0xD8E7554;

/**
 * Actor::getDimensionBlockSource(void* actor) -> BlockSource*   ★[已定案] = 0xD8DDE34
 *   反编译：同样的 shared_ptr 弱引用，最后 `(*(vtable+104))(obj)` 返回 BlockSource。
 *   ★ 调用方必须判 0。这是访问世界方块的最短路径。
 */
constexpr uintptr_t FN_ACTOR_GET_DIM_BLOCKSOURCE = 0xD8DDE34;

/**
 * Actor::getWidth(const void* actor) -> float   ★[已定案] = 0xD8F78DC
 *   反编译原文：`return *(float *)(*(_QWORD *)(a1 + 656) + 24LL);`
 *   = AABBShapeComponent(+0x290) + 0x18 的第一个 float。
 *   ⚠ 这是【碰撞宽度】，不是生命值 —— 原资料专门做过这条纠错。
 */
constexpr uintptr_t FN_ACTOR_GET_WIDTH = 0xD8F78DC;

/**
 * Actor::setSize(void* actor, Vec2 const* size)   ★[已定案] = 0xD8E8CB8
 *   反编译：`sub_D8E8CB8(__int64 a1, float32x2_t *a2)`，a2 是**指针**（解引用取 8 字节），
 *   最终写 `*(float32x2_t**)(a1+656)` 的第 3 个 8 字节 = AABBShapeComponent + 0x18。
 *   所以 Vec2 = { 宽度, 高度 }，正好就是 getWidth 读的那一对。
 * ★ 主线程调用，且游戏每 tick 会用自己的数据覆写该组件 —— 要按 tick 持续施加。
 */
constexpr uintptr_t FN_ACTOR_SET_SIZE = 0xD8E8CB8;

/**
 * Actor::isOnGround(EntityContext const* ctx) -> bool   ★[已定案] = 0xDC216B4
 *   ⚠⚠ **第一参是 EntityContext***，即 `actor + 0x08`，**不是 Actor***！
 *   反编译读 [a1+8]（组件表指针）和 [a1+16]（实体 id）；而
 *   Mob::jumpFromGround(0xCEF9270) 内部正是 `sub_DC216B4(a1 + 8)`。
 *   传 Actor* 会读到错位的指针并大概率返回 false / 崩。
 */
constexpr uintptr_t FN_ACTOR_ISONGROUND = 0xDC216B4;

/**
 * Player::teleportTo(void* player, Vec3 const* pos, bool a3, uint32 a4, uint32 a5, bool a6)
 *   ★[已定案] = 0xDC31E84   （注意：是 **Player 的覆写版**，不是 Actor 基类版！）
 *
 * 反编译：`(a1, __int64 a2, char a3, unsigned int a4, unsigned int a5, char a6)` —— 6 参，
 *   第 2 参是**指针**（Vec3 const&）；a1 是 **对象本体**（函数体用 a1+16 / a1+24 取
 *   EntityContext 的注册表与实体 id），**不是 actor+8**。
 *
 * ★ 定案证据（IDA 实证，2026-10-06）：函数体第一句是内置断言
 *      `sub_D607464(a2, "3 Player teleportTo");`
 *   —— 网易这个库里断言字符串是**明文且带真名**的，函数名混淆在这条链路上是失效的。
 *
 * ★ 顺带纠正一条旧记载：Actor 虚表 [26] 实测指向的是 **0xD8F3918**（Actor 基类版，
 *   一条独立的同构实现），而不是 0xDC31E84。两者是同一族虚函数的不同覆写；
 *   我们只对本地玩家调用，所以直接用 0xDC31E84 这个 Player 版是对的。
 *
 * ★ 主线程调用。
 */
constexpr uintptr_t FN_ACTOR_TELEPORT = 0xDC31E84;

/**
 * ★★★ Actor::teleportTo 内部的 Y 偏移取值器 = 0xDC092D8   ★[已定案，本轮新挖]
 *
 *   `float f(EntityContext* ctx)`，实现是：
 *       sub_DC092F0(ctx)  → 查 ECS 组件（hash 0xDE21C316）
 *       LDR S0,[X0]       → 返回该组件第一个 float
 *
 *   ★ 为什么必须知道它 —— 「Shift/Teleport/Sprint/Velocity 全上天」的根因：
 *
 *   Actor::teleportTo(0xD8F3918) 真正写位置之前有这么一串：
 *       0xD8F3B60  BL  sub_DC092D8        ; S0 = Δ（上面那个 float）
 *       0xD8F3B64  LDP S1, S2, [X20]      ; S1=pos.x  S2=pos.y   （X20 = 传入的 Vec3*）
 *       0xD8F3B6C  FADD S0, S2, S0        ; ★ S0 = pos.y + Δ
 *       0xD8F3B7C  STP S1, S0, [SP,#var_14]  ; 改成 {x, y+Δ, z}
 *       0xD8F3BA8  BL  sub_9F69940        ; 用「抬高后的 Y」写位置
 *
 *   ⇒ teleportTo 会把传进去的 Y **加一个偏移再落盘**。
 *     我们把 `*(actor+0x288)` 读到的当前 Y 原样传回去，它就每次都再抬一次：
 *       Speed  每 50ms 一次 → 20 次/秒狂升
 *       Sprint 每 100ms 一次 → 10 次/秒狂升
 *       Teleport(Forward) 每 600ms 一次 → 缓慢上升（"选前进反而上天"）
 *       Velocity 每次回弹 → 越弹越高
 *     这就是实机反馈的那一组「不是 XX 反而上天」。
 *
 *   ★ 修法：传进去 `y - Δ`，teleportTo 内部 +Δ 就正好抵消 —— 精确往返，
 *     而且 Δ 是同一个函数算出来的，随版本/随实体变化都自洽。
 */
constexpr uintptr_t FN_ACTOR_TELEPORT_YOFF = 0xDC092D8;

/**
 * ★ Actor::teleportTo 里真正「落位置」的那一句 = sub_9F69940   ★[已定案]
 *   签名 (EntityContext* ctx = actor+8, Vec3 const* pos, bool onGround)
 *   0xD8F3B9C  ADD X0, X19, #8 / ADD X1, SP,#var_14 / AND W2,W8,#1 / BL sub_9F69940
 *   之后 0xD8F3BC0 处再把 mPos 抄进 mPosPrev(+0x0C)。
 *   目前不直接调它（Δ 补偿法更省事、且保留 teleportTo 的 AABB/mPosPrev 维护），
 *   留作备选。
 */
constexpr uintptr_t FN_ACTOR_SET_POS_RAW = 0x9F69940;

/**
 * Mob::jumpFromGround(void* mob) -> bool   ★[已定案] = 0xCEF9270
 *   反编译自带三重守卫：虚表[424] 能力位、`sub_DC216B4(this+8)` 着地、`sub_DC55D78` 可跳。
 *   任一不满足直接 return 0 —— 所以「想跳就调」是安全的，不会在空中乱跳。
 * ★ 传 Mob*（函数内部自己 +8），主线程调用。
 */
constexpr uintptr_t FN_MOB_JUMPFROMGROUND = 0xCEF9270;

// ═══════════ C. Ability（Fly） ═══════════

/**
 * Player::getAbilities(void* player) -> Abilities*   ★[已定案] = 0xDC3B9CC
 *   反编译：4 字节 thunk `B sub_DC3E738`；sub_DC3E738 与 isFlying 用的
 *   sub_DC3E660 是**同一段 ECS 组件查找**（同 hash 0x68B75D48、同 a1+16/a1+24 取法），
 *   即两者返回同一个 Abilities 容器 —— 所以拿它去喂 setAbility 是自洽的。
 */
constexpr uintptr_t FN_PLAYER_GET_ABILITIES = 0xDC3B9CC;

/**
 * Abilities::setAbility(Abilities* ab, int index, bool value)   ★[已定案] = 0xDC2B6C8
 *   反编译：`sub_DC2B6C8(a1)` 实为 `sub_DCA36E8(a1 + 344)`（a1 = Abilities*），
 *   内层 `sub_DCA36E8(result, unsigned __int8 a2, char a3)`：
 *       entry = result + 12*a2;              // 每个能力 12 字节
 *       if (entry[0] == 1) entry[0] = 2;     // 状态位：1 → 2（已初始化）
 *       entry[4] = a3 & 1;                   // 值
 *   ⚠ a2 是 **uint8**，>= 0x14 时走全局默认槽 —— 传 9/10 安全。
 *   ★ 与 LocalPlayer::isFlying 完全自洽：isFlying = `entry[0]==2 && entry[4]!=0`。
 * ★ 主线程调用。
 */
constexpr uintptr_t FN_ABILITIES_SETABILITY = 0xDC2B6C8;

/**
 * LocalPlayer::isFlying(const void* player) -> bool   ★[已定案] = 0xDC34D50
 *   反编译：`sub_DCA2154(sub_DC2A738(sub_DC3E660(), 9))`，即查 Ability 序号 9。
 *   用来「读回」Fly 状态做自检，也可以直接拿来做 isFlying 覆盖。
 */
constexpr uintptr_t FN_LOCALPLAYER_ISFLYING = 0xDC34D50;

/** Ability 索引 —— 游戏内部枚举，实测 9/10 就是这两个 */
constexpr int ABILITY_FLYING = 9;
constexpr int ABILITY_MAYFLY = 10;

// ═══════════ D. 物品 / 背包 ═══════════

/**
 * Player::getCarriedItem(void* player) -> ItemStack*   ★[已定案] = 0xDC3B6FC
 *   反编译：读 `[a1+0xC18]` 容器，判 `[+0x118]` 空位，空则返回全局静态空物品
 *   `qword_133E4728`，否则按当前选中槽从 `[+0x120]` 取。
 */
constexpr uintptr_t FN_PLAYER_GET_CARRIED = 0xDC3B6FC;

/**
 * ItemStackBase::isBlock(const void* item) -> bool   ★[已定案] = 0xE2CFA74
 *   反编译：`item -> [item+8] -> [*] -> [+0x158] != 0`，即物品定义里有没有方块描述符。
 */
constexpr uintptr_t FN_ITEMSTACK_ISBLOCK = 0xE2CFA74;

/**
 * PlayerInventory::getItem(void* inv, int slot, bool a3) -> ItemStack*   ★[已定案] = 0xDC441E8
 *   反编译：`if (a3) return &qword_133E4728; else return (*(*(a1+0x120)+64))(*(a1+0x120));`
 */
constexpr uintptr_t FN_INV_GET_ITEM = 0xDC441E8;

/**
 * PlayerInventory::selectSlot(void* inv, int slot, bool a3) -> bool   ★[已定案] = 0xDC41130
 * ★ 主线程调用。
 */
constexpr uintptr_t FN_INV_SELECT_SLOT = 0xDC41130;

// ═══════════ E. 世界 / 方块 ═══════════

/**
 * BlockSource::getBlock(BlockSource const* bs, BlockPos const* pos) -> const Block*
 *   ★[已定案] = 0xDD7B3FC  BlockSource 虚表[2]
 *   反编译：`(__int64 a1, int32x2_t *a2)` —— 第 2 参是**指针**；带 y 越界检查。
 */
constexpr uintptr_t FN_BLOCKSOURCE_GETBLOCK = 0xDD7B3FC;

/**
 * BlockSource::getBlockEntity(BlockSource* bs, BlockPos const* pos) -> BlockActor*
 *   ★[已定案] = 0xDD7B660  —— 箱子/熔炉等方块实体的取法，ChestESP 用。
 */
constexpr uintptr_t FN_BLOCKSOURCE_GETBLOCKENTITY = 0xDD7B660;

/**
 * Block::isAir(const Block* block) -> bool   ★[已定案] = 0xE575674
 *   反编译：`sub_E436E28(*(a1 + 144))` —— 转发到方块描述符（Block+0x90）。
 */
constexpr uintptr_t FN_BLOCK_ISAIR = 0xE575674;

/**
 * Dimension::forEachPlayer(Dimension* dim, bool (*cb)(void* ctx, void* player), void* ctx)
 *   -> uintptr   ★[已定案] = 0xE72E390  Dimension 虚表[11]（Overworld/Nether/TheEnd 同位）
 *
 *   反编译：先 `(*(dim->[+160] vtable+3464))(...)` 拿实体表，按 24 字节步长遍历，
 *   逐条做「有效性检查 → sub_DC57C78 取实体 → 比对维度 id(*(dim+352))」，
 *   命中后 `cb(ctx, entity)`；**回调返回 false 即中断遍历**。
 *   → 是比重型堆扫更可靠的实体枚举入口。
 */
constexpr uintptr_t FN_DIM_FOREACHPLAYER = 0xE72E390;

// ═══════════ F. 玩家状态 ═══════════

/**
 * Player::setPlayerGameType(void* player, uint32 gameType)   ★[已定案] = 0xDC4F3B0
 *   反编译：`(a1, unsigned int a2)`；内部对 a2==5 有特判（取当前模式），
 *   所以传 0=Survival / 1=Creative / 2=Adventure 是安全的。
 * ★ 主线程调用。
 */
constexpr uintptr_t FN_PLAYER_SET_GAMETYPE = 0xDC4F3B0;

/** GameType 枚举（网易版实测 0/1/2 可用） */
constexpr int GAMETYPE_SURVIVAL = 0;
constexpr int GAMETYPE_CREATIVE = 1;
constexpr int GAMETYPE_ADVENTURE = 2;

// ═══════════ G. 渲染 / 相机（需 hook，暂未接） ═══════════

/** BaseActorRenderContext::ctor —— 渲染上下文，Render 系列安装点  0x73CBF84 */
constexpr uintptr_t FN_BASEACTORRENDER_CTOR = 0x73CBF84;
/** LocalPlayer::normalTick —— 主 tick，全功能心脏  0x74C79FC  虚表[29] */
constexpr uintptr_t FN_LOCALPLAYER_NORMALTICK = 0x74C79FC;
/** LevelRendererCamera::setupFog(bool, float)  0x7B86B94  虚表[23] —— 去雾/全亮 */
constexpr uintptr_t FN_LEVELRENDERER_SETUPFOG = 0x7B86B94;
/** LevelRendererPlayer::getFov  0x7BA80A4 —— FOV 修改 */
constexpr uintptr_t FN_LEVELRENDERER_GETFOV = 0x7BA80A4;
/** CameraAvoidanceSystem::tick adapter  0x81421A0  虚表[3] —— 运动相机 */
constexpr uintptr_t FN_CAMERA_AVOIDANCE_TICK = 0x81421A0;
/** LoopbackPacketSender::sendToServer  0xA7878A4  虚表[5] —— 发包 */
constexpr uintptr_t FN_LOOPBACK_SENDTOSERVER = 0xA7878A4;
/** JumpControl::getJumpPower  0xA86F584 —— 跳跃高度（反编译返回 0.2/0.42 常量） */
constexpr uintptr_t FN_JUMPCONTROL_GETJUMPPOWER = 0xA86F584;
/** TextPacket::ctor  0xB0296B0 —— 文本包（Command / ChatEffects） */
constexpr uintptr_t FN_TEXTPACKET_CTOR = 0xB0296B0;
/** Actor::Actor  0xD8D97E8  —— 实体创建钩子 */
constexpr uintptr_t FN_ACTOR_CTOR = 0xD8D97E8;
/** Actor::~Actor  0xD8DC45C  虚表[12] —— 实体销毁钩子 */
constexpr uintptr_t FN_ACTOR_DTOR = 0xD8DC45C;

// ═══════════ H. 虚表槽位（虚函数只能靠槽位定位） ═══════════

/**
 * ★[已定案] Actor 虚表 [18] / [19] = 插值 yaw / 插值 pitch 的 getter
 *   签名都是 `float f(void* actor, float partialTicks)`。
 *
 * 证据链：
 *  ① [18] = sub_D5B3F64：内部 `v = sub_D5ACB5C(this)` 拿 RotationComponent 的 float*，
 *     再 `sub_12132EB4(v[1], v[0], partial)` —— 后者反编译是
 *     `fmodf((180 - prev) + cur, 360); … return prev + (wrapped - 180) * partial`，
 *     正是「两帧之间的角度环绕插值」，输入输出都是角度。
 *  ② sub_D5ACB5C 是 ECS 组件查找（哈希表 + `(v3>>11)&0x7F` 索引），
 *     所以 yaw/pitch **不在 Actor 的固定偏移上**，只能走虚表 getter。
 *  ③ [19] = sub_D5B3F8C 同构，换用 sub_D91019C（另一个组件）—— pitch。
 */
constexpr int VT_YAW_SLOT   = 18;
constexpr int VT_PITCH_SLOT = 19;
/** GameMode 槽位 */
constexpr int VT_GM_DESTROYBLOCK = 3;
constexpr int VT_GM_BUILDBLOCK   = 7;
constexpr int VT_GM_ATTACK       = 16;
/** Actor 槽位 */
constexpr int VT_ACTOR_TELEPORT  = 26;
constexpr int VT_ACTOR_DTOR      = 12;
/** LocalPlayer 槽位 */
constexpr int VT_LP_NORMALTICK   = 29;
/** BlockSource 槽位 */
constexpr int VT_BS_GETBLOCK     = 2;
/** Dimension 槽位 */
constexpr int VT_DIM_FOREACHPLAYER = 11;

/** EntityRegistry::getEntity  [未定案] 留 0 */
constexpr uintptr_t FN_GET_ENTITY = 0;

/** BlockSource::setBlock  [未定案] 留 0 */
constexpr uintptr_t FN_BLOCKSRC_SETBLOCK = 0;

}  // namespace rva

// ───────────────────────── 功能 → 后端可用性 ─────────────────────────
//
//  ★ 2.3.0 起：UI 里**不再有任何 N/A**。每个功能都有真后端了，所以这里对
//    注册过的每个 backend key 都返回 true。保留这个函数是为了：
//      · 新增功能时如果漏了后端，忘了登记 key → 这里返回 false，
//        Java 侧 guard() 会把开关弹回并提示，而不是给一个「开了没反应」的开关；
//      · 需要在某些机型/环境下按能力降级时，有统一的收口点。
//
//  各 backend 依赖的游戏函数（全部来自「已定案」的 33 函数表 + 7 个 Hook 点）：
//    killaura   GameMode::attack              0xDAC2664      主线程
//    scaffold   GameMode::buildBlock          0xDAC4764      主线程
//    esp        Actor 虚表[18][19] + 自身投影
//    chestesp   BlockSource::getBlockEntity   0xDD7B660
//    fly        getAbilities + setAbility     0xDC3B9CC/0xDC2B6C8
//    autojump   isOnGround + jumpFromGround   0xDC216B4/0xCEF9270
//    teleport   Actor::teleportTo             0xDC31E84
//    hitbox     Actor::setSize                0xD8E8CB8
//    nuker      destroyBlock + getBlock       0xDAC3388/0xDD7B3FC
//    autobreak  同上
//    gamemode   setPlayerGameType             0xDC4F3B0
//    autotool   PlayerInventory::selectSlot   0xDC41130 + 虚表探针
//    xray       getBlock + Block::isAir       0xDD7B3FC/0xE575674
//    speed       teleportTo（沿视线微位移）
//    sprint      teleportTo（沿视线持续前进）
//    velocity    teleportTo（位移回弹抑制）
//    respawn     teleportTo（死亡/重生后归位）
//    reach       Hook getAttackRangeSqr       0xDAC72F0
//    criticals   Hook Actor::isOnGround       0xDC216B4
//    nofall      Hook Actor::isOnGround       0xDC216B4
//    fullbright  Hook setupFog                0x7B86B94
//    fov         Hook LevelRendererPlayer::getFov 0x7BA80A4
//    freecam     Hook CameraAvoidance tick    0x81421A0
//    ping/tps    Hook sendToServer / normalTick   0xA7878A4 / 0x74C79FC
//    nametags    实体枚举（Dimension::forEachPlayer 0xE72E390）

inline bool feature_available(const char* name) {
    if (name == nullptr) return false;

    // ── 直接调用型（33 函数表已覆盖）──
    if (__builtin_strcmp(name, "killaura")   == 0) return rva::FN_GAMEMODE_ATTACK != 0;
    if (__builtin_strcmp(name, "scaffold")   == 0) return rva::FN_GAMEMODE_BUILD != 0;
    if (__builtin_strcmp(name, "fly")        == 0)
        return rva::FN_PLAYER_GET_ABILITIES != 0 && rva::FN_ABILITIES_SETABILITY != 0;
    if (__builtin_strcmp(name, "autojump")   == 0)
        return rva::FN_ACTOR_ISONGROUND != 0 && rva::FN_MOB_JUMPFROMGROUND != 0;
    if (__builtin_strcmp(name, "teleport")   == 0) return rva::FN_ACTOR_TELEPORT != 0;
    if (__builtin_strcmp(name, "speed")      == 0) return rva::FN_ACTOR_TELEPORT != 0;
    if (__builtin_strcmp(name, "sprint")     == 0) return rva::FN_ACTOR_TELEPORT != 0;
    if (__builtin_strcmp(name, "velocity")   == 0) return rva::FN_ACTOR_TELEPORT != 0;
    if (__builtin_strcmp(name, "respawn")    == 0) return rva::FN_ACTOR_TELEPORT != 0;
    if (__builtin_strcmp(name, "autobreak")  == 0 || __builtin_strcmp(name, "nuker") == 0)
        return rva::FN_ACTOR_GET_DIM_BLOCKSOURCE != 0 && rva::FN_BLOCKSOURCE_GETBLOCK != 0
               && rva::FN_GAMEMODE_DESTROY != 0;
    if (__builtin_strcmp(name, "xray")       == 0)
        return rva::FN_ACTOR_GET_DIM_BLOCKSOURCE != 0 && rva::FN_BLOCKSOURCE_GETBLOCK != 0
               && rva::FN_BLOCK_ISAIR != 0;
    if (__builtin_strcmp(name, "chestesp")   == 0)
        return rva::FN_ACTOR_GET_DIM_BLOCKSOURCE != 0 && rva::FN_BLOCKSOURCE_GETBLOCKENTITY != 0;
    if (__builtin_strcmp(name, "autotool")   == 0)
        return rva::FN_PLAYER_GET_CARRIED != 0 && rva::FN_ITEMSTACK_ISBLOCK != 0
               && rva::FN_INV_SELECT_SLOT != 0;
    if (__builtin_strcmp(name, "gamemode")   == 0) return rva::FN_PLAYER_SET_GAMETYPE != 0;
    if (__builtin_strcmp(name, "hitbox")     == 0) return rva::FN_ACTOR_SET_SIZE != 0;
    if (__builtin_strcmp(name, "esp")        == 0) return true;
    if (__builtin_strcmp(name, "nametags")   == 0)
        return rva::FN_ACTOR_GET_DIMENSION != 0 && rva::FN_DIM_FOREACHPLAYER != 0;
    if (__builtin_strcmp(name, "entitylist") == 0)
        return rva::FN_ACTOR_GET_DIMENSION != 0 && rva::FN_DIM_FOREACHPLAYER != 0;

    // ── Hook 型（函数地址就是 Hook 目标，装不上时 nStatus 会报 hook= n/8）──
    //   ★ reach 现在要两个 getter 都在（2.4.5 起 rangeSqr 只覆盖方块链路）
    if (__builtin_strcmp(name, "reach")      == 0)
        return rva::FN_PLAYER_GET_ATTACK_RANGE_SQR != 0 && rva::FN_PLAYER_GET_ATTACK_RANGE != 0;
    if (__builtin_strcmp(name, "criticals")  == 0) return rva::FN_ACTOR_ISONGROUND != 0;
    if (__builtin_strcmp(name, "nofall")     == 0) return rva::FN_ACTOR_ISONGROUND != 0;
    if (__builtin_strcmp(name, "fullbright") == 0) return rva::FN_LEVELRENDERER_SETUPFOG != 0;
    if (__builtin_strcmp(name, "fov")        == 0) return rva::FN_LEVELRENDERER_GETFOV != 0;
    if (__builtin_strcmp(name, "freecam")    == 0) return rva::FN_CAMERA_AVOIDANCE_TICK != 0;
    if (__builtin_strcmp(name, "ping")       == 0) return rva::FN_LOOPBACK_SENDTOSERVER != 0;
    if (__builtin_strcmp(name, "tps")        == 0) return rva::FN_LOCALPLAYER_NORMALTICK != 0;

    // ── 辅助 ──
    if (__builtin_strcmp(name, "carried")    == 0) return rva::FN_PLAYER_GET_CARRIED != 0;
    if (__builtin_strcmp(name, "isair")      == 0) return rva::FN_BLOCK_ISAIR != 0;
    return false;
}
