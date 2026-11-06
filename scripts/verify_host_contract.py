#!/usr/bin/env python3
"""Verify actual DEX declarations used by the 6.9.37 bridge, without executing the APK."""
import argparse
import struct
import zipfile


def declarations(data):
    def u32(offset):
        return struct.unpack_from('<I', data, offset)[0]

    def uleb(offset):
        result = shift = 0
        while True:
            byte = data[offset]
            offset += 1
            result |= (byte & 127) << shift
            if byte < 128:
                return result, offset
            shift += 7

    strings = []
    for index in range(u32(56)):
        _, offset = uleb(u32(u32(60) + index * 4))
        strings.append(data[offset:data.index(0, offset)].decode('utf-8', errors='replace'))
    types = [strings[u32(u32(68) + index * 4)] for index in range(u32(64))]
    methods = []
    for index in range(u32(88)):
        owner, proto, name = struct.unpack_from('<HHI', data, u32(92) + index * 8)
        offset = u32(76) + proto * 12
        params = u32(offset + 8)
        args = '' if params == 0 else ''.join(
            types[struct.unpack_from('<H', data, params + 4 + index * 2)[0]] for index in range(u32(params)))
        methods.append((types[owner][1:-1].replace('/', '.'), strings[name] + '(' + args + ')' + types[u32(offset + 4)]))
    classes = {}
    for index in range(u32(96)):
        offset = u32(100) + index * 32
        name = types[u32(offset)][1:-1].replace('/', '.')
        members = classes.setdefault(name, set())
        offset = u32(offset + 24)
        if offset == 0:
            continue
        counts = []
        for _ in range(4):
            count, offset = uleb(offset)
            counts.append(count)
        for _ in range(counts[0] + counts[1]):
            _, offset = uleb(offset)
            _, offset = uleb(offset)
        for count in counts[2:]:
            method_id = 0
            for _ in range(count):
                delta, offset = uleb(offset)
                method_id += delta
                _, offset = uleb(offset)
                _, offset = uleb(offset)
                members.add(methods[method_id][1])
    return classes


BASE = 'com.heytap.device.ui.weight.scale.boohee.'
REQUIRED = {
    BASE + 'BHDeviceManager': {
        'ensureInit(Landroid/content/Context;)Z', 'refreshUserModelToSdk(Ljava/lang/String;)V',
        'bind(Lcom/heytap/device/ui/weight/scale/BindableScaleDevice;Lcom/heytap/device/ui/weight/scale/boohee/BHDeviceManager$BindListener;)V',
        'startMeasureReceive(Lcom/heytap/device/ui/weight/scale/boohee/BHDeviceManager$MeasureListener;Ljava/lang/String;Ljava/lang/String;)V',
        'stopMeasureReceive()V', 'interruptBind(Z)V', 'forceStopKeepAlive(Ljava/lang/String;)V',
        'startKeepAlive(Ljava/lang/String;Lkotlin/jvm/functions/Function1;)V',
        'detachKeepAliveWithoutDisconnect()V', 'isSdkGattConnected(Ljava/lang/String;)Z',
        'isKeepAliveConnected(Ljava/lang/String;)Z', 'isKeepAliveGiveUp(Ljava/lang/String;)Z',
        'onBindSucceededOnThisPhone(Ljava/lang/String;)V',
    },
    BASE + 'BHDeviceManager$BindListener': {
        'onSuccess(Lcom/heytap/device/ui/weight/scale/BindableScaleDevice;)V', 'onFail(Ljava/lang/String;)V'},
    BASE + 'BHDeviceManager$MeasureListener': {
        'onProcessWeight(D)V', 'onLockWeight(DLcom/boohee/scale_sdk/data/BHScaleModel;)V', 'onFail(Ljava/lang/String;)V'},
    BASE + 'BooheeScaleBinder': {
        'startSearch(ILkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function1;)V',
        'stopSearch()V', 'stopSearchForPair()V', 'interruptPair()V', 'release()V'},
    BASE + 'BooheeScaleCapabilityStore': {'remove(Ljava/lang/String;)V', 'clearSkipHistoryUntilDisconnect(Ljava/lang/String;)V'},
    BASE + 'BooheeUnclaimedImporter': {
        'importHistory(ZLcom/boohee/scale_sdk/device/BHDeviceModel;Lcom/boohee/scale_sdk/data/BHScaleModel;)V'},
    BASE + 'BooheeDeviceLoader': {
        'convertBoohee(Ljava/util/List;)Ljava/util/List;'},
    'com.heytap.device.ui.weight.scale.WeightScaleKeepAliveCoordinator': {'pause()V', 'releaseForUnbind(Ljava/lang/String;)V'},
    'com.heytap.device.ui.weight.scale.BindableScaleDevice': {
        '<init>(Lcom/heytap/device/ui/weight/scale/ScaleVendor;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZLjava/lang/Object;)V'},
    'com.boohee.scale_sdk.device.BHDeviceModel': {
        '<init>(Ljava/lang/String;Ljava/lang/String;)V', 'setDeviceId(Ljava/lang/String;)V',
        'setDeviceMac(Ljava/lang/String;)V', 'setDeviceModel(Ljava/lang/String;)V', 'setDeviceName(Ljava/lang/String;)V'},
    'com.boohee.scale_sdk.data.BHScaleModel': {
        '<init>()V', 'setWeight(F)V', 'setBodyResistance(F)V', 'setHeartRate(I)V', 'setSecond(J)V', 'setHistory(Z)V',
        'setLockData(Z)V', 'setDeviceModel(Lcom/boohee/scale_sdk/device/BHDeviceModel;)V'},
    'com.boohee.scale_sdk.BHScaleManager': {
        'getBuilder()Lcom/boohee/scale_sdk/BHScaleManager$Builder;',
        'handleTheHistoryScaleModelDetailByScaleModel(Lcom/boohee/scale_sdk/data/BHScaleModel;)Lcom/boohee/scale_sdk/data/BHScaleModel;'},
    'com.heytap.health.account.AccountHelper': {'getAccountManager()Lcom/heytap/health/account/IAccount;'},
    'com.heytap.health.account.IAccount': {
        'getSsoid()Ljava/lang/String;', 'addLoginListener(Lcom/heytap/health/account/listener/ILoginListener;)V'},
    'com.heytap.health.account.listener.ILoginListener': set(),
    'com.heytap.health.devicemanager.third_device.weightscale.WeightScaleDeviceInfo': {
        '<init>()V', 'setId(Ljava/lang/String;)V', 'setDeviceType(I)V', 'setConnectScale(Z)V', 'setConnected(Z)V'},
    'com.heytap.health.devicemanager.third_device.bpg.IntentKeys': {
        'isBooheeModel(Ljava/lang/String;)Z', 'isBooheeConnectModel(Ljava/lang/String;)Z',
        'isBooheeBroadcastModel(Ljava/lang/String;)Z', 'resolveBodyFatVendor(Ljava/lang/String;)Ljava/lang/String;'},
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk')
    args = parser.parse_args()
    classes = {}
    with zipfile.ZipFile(args.apk) as apk:
        for name in apk.namelist():
            if name.endswith('.dex'):
                for owner, methods in declarations(apk.read(name)).items():
                    classes.setdefault(owner, set()).update(methods)
    for owner, methods in REQUIRED.items():
        assert owner in classes, 'Missing class: ' + owner
        assert methods <= classes[owner], 'Missing methods: ' + owner + ' ' + repr(methods - classes[owner])
    listener = classes['com.heytap.health.account.listener.ILoginListener']
    assert any(method.startswith('onLoginSuccess(') for method in listener)
    assert any(method.startswith('onLogout(') for method in listener)
    fragment = classes['com.heytap.health.watchpair.watchconnect.pair.producttype.ProductCategorFragment']
    assert any(method.startswith('showScreen$lambda$4$lambda$3(') for method in fragment)
    print('PASS: %d host classes and %d exact method signatures; catalog and account callbacks present' %
          (len(REQUIRED), sum(map(len, REQUIRED.values()))))


if __name__ == '__main__':
    main()
