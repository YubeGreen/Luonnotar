package com.yubegreen.luonnotar.privileged;

interface IPrivilegedGuardian {
    String configureAndStart(String configJson) = 0;
    String getStatusJson() = 1;
    String runCycle() = 2;
    String stop() = 3;
    String recoverGms() = 4;
    void destroy() = 16777114;
}
