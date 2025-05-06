## 原子指令与PTW bug
amo指令，和所有lockInst，一样，会等到ROB，storeQueue， storeBuffer完全清空后进入后端，地址翻译后进入amoUnit
amoOp进入amoUnit后，会阻止PTW访问LSU端口，但是如果PTW在原子指令前一个周期进入LSU，且
   1. 和AMO index相同
   2. 未命中，替换的way与amo操作的way相同
由于amo写延迟较长，导致MSHR读Cache时恰好是AMO 写cache 时。
由于采用了双端口Cache，MSHR读口与LSU写口不相同，且之间没有bypass，导致amo指令实际上没有执行，bug！
发现条件：ITLB DTLB 条目设为1，在linux启动时发现。

由于没有bypass，当cache Miss，LSU写tag时会阻止后续指令进入。但是这是时序上的关键路径。
writeTag延迟一拍后，后一个指令有可能命中一个会被替换的cache line，如果这时候是常规写，暂时不会出问题：cacheUop -> MSHR -> read cache有一段时间，MSHR能读到写数据。
但是对amo这种延迟长的，会出问题。

![amo-ptw-bug](amo-ptw-bug.png)