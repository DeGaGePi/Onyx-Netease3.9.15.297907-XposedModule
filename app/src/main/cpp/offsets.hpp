


























#pragma once

#include <cstdint>

namespace off {



 
constexpr int GM_PLAYER = 0x08;

 









constexpr int PLAYER_GM = 0xA78;

 



constexpr int PLAYER_INV_HINT = 0xC18;

 













constexpr int ACTOR_POS_PTR = 0x288;

 
constexpr int ACTOR_AABB = 0x220;

 




constexpr int ACTOR_LEVEL_PTR = 0x250;

 
constexpr int ACTOR_ID = 0x18;

 






 







constexpr int ACTOR_ENTITY_CONTEXT = 0x08;

 






constexpr int ACTOR_AABB_SHAPE = 0x290;

 
constexpr int AABB_SHAPE_SIZE = 0x18;

 
constexpr int PLAYER_CARRIED = 0xC18;

 









constexpr int PLAYER_BODY_SCAN_LO = 0x100;
constexpr int PLAYER_BODY_SCAN_HI = 0x1400;

}  



namespace vt {

constexpr uintptr_t LOCALPLAYER = 0x1270FA98;   
constexpr uintptr_t PLAYER      = 0x12ACAA28;   
constexpr uintptr_t MOB         = 0x12A68098;   
constexpr uintptr_t ACTOR       = 0x12A8D808;   





















constexpr uintptr_t GAMEMODE    = 0x12AAB340;   
constexpr uintptr_t GAMEMODE_SURVIVAL = 0x12AAB3F8;  
constexpr uintptr_t BLOCKSOURCE = 0x12AEBF48;   
constexpr uintptr_t DIMENSION   = 0x12B9D600;   





constexpr uintptr_t LEVELRENDERCAMERA    = 0x1274D0E8;   
constexpr uintptr_t LEVELRENDERERPLAYER  = 0x1274D4D8;   
constexpr uintptr_t LOOPBACKPKTSENDER    = 0x12909558;   
constexpr uintptr_t JUMPCONTROL          = 0x12A65598;   
constexpr uintptr_t PLAYERINVENTORY      = 0x12ACB320;   



















}  



namespace rva {



 











constexpr uintptr_t FN_GAMEMODE_ATTACK = 0xDAC2664;

 







constexpr uintptr_t FN_GAMEMODE_DESTROY = 0xDAC3388;

 
























constexpr uintptr_t FN_GAMEMODE_BUILD = 0xDAC4764;

 
constexpr uintptr_t FN_GAMEMODE_START_BUILD = 0xDAC4750;



 
constexpr uintptr_t FN_ACTOR_SWING = 0x74CAD10;

 


















constexpr uintptr_t FN_PLAYER_GET_ATTACK_RANGE = 0xDAC72BC;

 





constexpr uintptr_t FN_PLAYER_GET_ATTACK_RANGE_SQR = 0xDAC72F0;

 

















constexpr uintptr_t OFF_PLAYER_ATTACK_RANGE = 0x18B4;

 




constexpr uintptr_t FN_ACTOR_GET_DIMENSION = 0xD8E7554;

 




constexpr uintptr_t FN_ACTOR_GET_DIM_BLOCKSOURCE = 0xD8DDE34;

 





constexpr uintptr_t FN_ACTOR_GET_WIDTH = 0xD8F78DC;

 






constexpr uintptr_t FN_ACTOR_SET_SIZE = 0xD8E8CB8;

 






constexpr uintptr_t FN_ACTOR_ISONGROUND = 0xDC216B4;

 

















constexpr uintptr_t FN_ACTOR_TELEPORT = 0xDC31E84;

 


























constexpr uintptr_t FN_ACTOR_TELEPORT_YOFF = 0xDC092D8;

 







constexpr uintptr_t FN_ACTOR_SET_POS_RAW = 0x9F69940;

 





constexpr uintptr_t FN_MOB_JUMPFROMGROUND = 0xCEF9270;



 





constexpr uintptr_t FN_PLAYER_GET_ABILITIES = 0xDC3B9CC;

 










constexpr uintptr_t FN_ABILITIES_SETABILITY = 0xDC2B6C8;

 




constexpr uintptr_t FN_LOCALPLAYER_ISFLYING = 0xDC34D50;

 
constexpr int ABILITY_FLYING = 9;
constexpr int ABILITY_MAYFLY = 10;



 




constexpr uintptr_t FN_PLAYER_GET_CARRIED = 0xDC3B6FC;

 



constexpr uintptr_t FN_ITEMSTACK_ISBLOCK = 0xE2CFA74;

 



constexpr uintptr_t FN_INV_GET_ITEM = 0xDC441E8;

 



constexpr uintptr_t FN_INV_SELECT_SLOT = 0xDC41130;



 




constexpr uintptr_t FN_BLOCKSOURCE_GETBLOCK = 0xDD7B3FC;

 



constexpr uintptr_t FN_BLOCKSOURCE_GETBLOCKENTITY = 0xDD7B660;

 



constexpr uintptr_t FN_BLOCK_ISAIR = 0xE575674;

 








constexpr uintptr_t FN_DIM_FOREACHPLAYER = 0xE72E390;



 





constexpr uintptr_t FN_PLAYER_SET_GAMETYPE = 0xDC4F3B0;

 
constexpr int GAMETYPE_SURVIVAL = 0;
constexpr int GAMETYPE_CREATIVE = 1;
constexpr int GAMETYPE_ADVENTURE = 2;



 
constexpr uintptr_t FN_BASEACTORRENDER_CTOR = 0x73CBF84;
 
constexpr uintptr_t FN_LOCALPLAYER_NORMALTICK = 0x74C79FC;
 
constexpr uintptr_t FN_LEVELRENDERER_SETUPFOG = 0x7B86B94;
 
constexpr uintptr_t FN_LEVELRENDERER_GETFOV = 0x7BA80A4;
 
constexpr uintptr_t FN_CAMERA_AVOIDANCE_TICK = 0x81421A0;
 
constexpr uintptr_t FN_LOOPBACK_SENDTOSERVER = 0xA7878A4;
 
constexpr uintptr_t FN_JUMPCONTROL_GETJUMPPOWER = 0xA86F584;
 
constexpr uintptr_t FN_TEXTPACKET_CTOR = 0xB0296B0;
 
constexpr uintptr_t FN_ACTOR_CTOR = 0xD8D97E8;
 
constexpr uintptr_t FN_ACTOR_DTOR = 0xD8DC45C;



 












constexpr int VT_YAW_SLOT   = 18;
constexpr int VT_PITCH_SLOT = 19;
 
constexpr int VT_GM_DESTROYBLOCK = 3;
constexpr int VT_GM_BUILDBLOCK   = 7;
constexpr int VT_GM_ATTACK       = 16;
 
constexpr int VT_ACTOR_TELEPORT  = 26;
constexpr int VT_ACTOR_DTOR      = 12;
 
constexpr int VT_LP_NORMALTICK   = 29;
 
constexpr int VT_BS_GETBLOCK     = 2;
 
constexpr int VT_DIM_FOREACHPLAYER = 11;

 
constexpr uintptr_t FN_GET_ENTITY = 0;

 
constexpr uintptr_t FN_BLOCKSRC_SETBLOCK = 0;

}  




































inline bool feature_available(const char* name) {
    if (name == nullptr) return false;

    
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

    
    
    if (__builtin_strcmp(name, "reach")      == 0)
        return rva::FN_PLAYER_GET_ATTACK_RANGE_SQR != 0 && rva::FN_PLAYER_GET_ATTACK_RANGE != 0;
    if (__builtin_strcmp(name, "criticals")  == 0) return rva::FN_ACTOR_ISONGROUND != 0;
    if (__builtin_strcmp(name, "nofall")     == 0) return rva::FN_ACTOR_ISONGROUND != 0;
    if (__builtin_strcmp(name, "fullbright") == 0) return rva::FN_LEVELRENDERER_SETUPFOG != 0;
    if (__builtin_strcmp(name, "fov")        == 0) return rva::FN_LEVELRENDERER_GETFOV != 0;
    if (__builtin_strcmp(name, "freecam")    == 0) return rva::FN_CAMERA_AVOIDANCE_TICK != 0;
    if (__builtin_strcmp(name, "ping")       == 0) return rva::FN_LOOPBACK_SENDTOSERVER != 0;
    if (__builtin_strcmp(name, "tps")        == 0) return rva::FN_LOCALPLAYER_NORMALTICK != 0;

    
    if (__builtin_strcmp(name, "carried")    == 0) return rva::FN_PLAYER_GET_CARRIED != 0;
    if (__builtin_strcmp(name, "isair")      == 0) return rva::FN_BLOCK_ISAIR != 0;
    return false;
}
