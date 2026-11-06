package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo
object HookInstallState{var routing=false;var scan=false;var bind=false;var measure=false;var lifecycle=false;var keepAlive=false;var catalog=false;val coreReady:Boolean get()=routing&&scan&&bind&&measure&&lifecycle&&keepAlive}
