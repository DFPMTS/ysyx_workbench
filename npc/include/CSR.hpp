#ifndef CSR_HPP
#define CSR_HPP

#include "cpu.hpp"

using IData = uint32_t;

/*object LA32RCSRList {
  val csrMap: Map[String, UInt] = Map(
    // Basic CSRs
    "CRMD"      -> 0x0.U,    // 当前模式信息
    "PRMD"      -> 0x1.U,    // 例外前模式信息
    "EUEN"      -> 0x2.U,    // 扩展部件使能
    "ECFG"      -> 0x4.U,    // 例外配置
    "ESTAT"     -> 0x5.U,    // 例外状态
    "ERA"       -> 0x6.U,    // 例外返回地址
    "BADV"      -> 0x7.U,    // 出错虚地址
    "EENTRY"    -> 0xC.U,    // 例外入口地址

    // TLB related CSRs
    "TLBIDX"    -> 0x10.U,   // TLB 索引
    "TLBEHI"    -> 0x11.U,   // TLB 表项高位
    "TLBELO0"   -> 0x12.U,   // TLB 表项低位 0
    "TLBELO1"   -> 0x13.U,   // TLB 表项低位 1
    "ASID"      -> 0x18.U,   // 地址空间标识符
    "PGDL"      -> 0x19.U,   // 低半地址空间全局目录基址
    "PGDH"      -> 0x1A.U,   // 高半地址空间全局目录基址
    "PGD"       -> 0x1B.U,   // 全局目录基址

    // Processor identification
    "CPUID"     -> 0x20.U,   // 处理器编号

    // Data save registers
    "SAVE0"     -> 0x30.U,   // 数据保存0
    "SAVE1"     -> 0x31.U,   // 数据保存1
    "SAVE2"     -> 0x32.U,   // 数据保存2
    "SAVE3"     -> 0x33.U,   // 数据保存3

    // Timer related CSRs
    "TID"       -> 0x40.U,   // 定时器编号
    "TCFG"      -> 0x41.U,   // 定时器配置
    "TVAL"      -> 0x42.U,   // 定时器值
    "TICLR"     -> 0x44.U,   // 定时中断清除

    // LLBit control
    "LLBCTL"    -> 0x60.U,   // LLBit 控制

    // TLB refill exception entry
    "TLBRENTRY" -> 0x88.U,   // TLB 重填例外入口地址

    // Cache tag
    "CTAG"      -> 0x98.U,   // 高速缓存标签

    // Direct mapping windows
    "DMW0"      -> 0x180.U,  // 直接映射配置窗口0
    "DMW1"      -> 0x181.U   // 直接映射配置窗口1
  )

  def apply(name: String): UInt = csrMap(name)
  def exists(value: UInt): Bool = csrMap.map(_._2 === value).reduce(_ || _)
}*/

class CSR {
public:
  IData *crmd;
  IData *prmd;
  IData *euen;
  IData *ecfg;
  IData *estat;
  IData *era;
  IData *badv;
  IData *eentry;
  IData *tlbidx;
  IData *tlbehi;
  IData *tlbelo0;
  IData *tlbelo1;
  IData *asid;
  IData *pgdl;
  IData *pgdh;
  IData *save0;
  IData *save1;
  IData *save2;
  IData *save3;
  IData *tid;
  IData *tcfg;
  IData *tval;
  IData *ticlr;
  IData *llbctl;
  IData *tlbrentry;
  IData *dmw0;
  IData *dmw1;
};

#define CSR_FIELDS(X)                                                          \
  X(crmd, U)                                                                   \
  X(prmd, U)                                                                   \
  X(euen, U)                                                                   \
  X(ecfg, U)                                                                   \
  X(estat, U)                                                                  \
  X(era, )                                                                     \
  X(badv, )                                                                    \
  X(eentry, U)                                                                 \
  X(tlbidx, U)                                                                 \
  X(tlbehi, U)                                                                 \
  X(tlbelo0, U)                                                                \
  X(tlbelo1, U)                                                                \
  X(asid, U)                                                                   \
  X(pgdl, U)                                                                   \
  X(pgdh, U)                                                                   \
  X(save0, )                                                                   \
  X(save1, )                                                                   \
  X(save2, )                                                                   \
  X(save3, )                                                                   \
  X(tid, )                                                                     \
  X(tcfg, U)                                                                   \
  X(tval, )                                                                    \
  X(ticlr, U)                                                                  \
  X(llbctl, U)                                                                 \
  X(tlbrentry, U)                                                              \
  X(dmw0, U)                                                                   \
  X(dmw1, U)

#define V_CSRS(field, suffix)                                                  \
  top->rootp->npc_top__DOT__npc__DOT__csr__DOT__##field##suffix

#define BIND_CSRS_FIELD(field, suffix)                                         \
  CSRS.field = (decltype(CSRS.field))&V_CSRS(field, suffix);

#define CSR_ESTAT 0x5
#define CSR_TVAL 0x42

#endif