// Copyright 2017, Paul DeMarco.
// All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

part of flutter_blue;

class BluetoothDevice extends BluetoothDeviceCommon {
  BehaviorSubject<bool> _isDiscoveringServices = BehaviorSubject.seeded(false);
  Stream<bool> get isDiscoveringServices => _isDiscoveringServices.stream;

  factory BluetoothDevice.fromBuffer(List<int> data) {
    return BluetoothDevice.fromProto(protos.BluetoothDevice.fromBuffer(data));
  }

  BluetoothDevice.fromProto(protos.BluetoothDevice p)
      : super(new DeviceIdentifier(p.remoteId), p.name,
            BluetoothDeviceType.values[p.type.value]);

  protos.BluetoothDevice toProto() {
    return protos.BluetoothDevice.create()
      ..remoteId = id.id
      ..name = name
      ..type = BluetoothDevice_Type.valueOf(type.index);
  }

  Future<void> connect({
    Duration timeout,
    bool autoConnect = true,
  }) async {
    return Future.sync(() async {
      var lastError;
      for (int i = 0; i < 3; i++) {
        try {
          await _connectInternal(timeout, autoConnect);
          return;
        } catch (e) {
          lastError = e;
        }
      }
      throw FatalConnectionError(lastError);
    });
  }

  /// Establishes a connection to the Bluetooth Device.
  Future<void> _connectInternal(
    Duration timeout,
    bool autoConnect,
  ) async {
    var request = protos.ConnectRequest.create()
      ..remoteId = id.toString()
      ..androidAutoConnect = autoConnect;

    final completer = Completer();

    Timer timer;

    final waitForConnectSubscription =
        state.listen(null, onError: (error, stacktrace) {
      completer.completeError(error);
    }, cancelOnError: true);

    waitForConnectSubscription.onData((state) {
      if (state != BluetoothDeviceState.connected) {
        return;
      }
      timer?.cancel();
      completer.complete();
      waitForConnectSubscription.cancel();
    });

    if (timeout != null) {
      timer = Timer(timeout, () {
        waitForConnectSubscription.cancel();
        disconnect();
        completer.completeError(
            TimeoutException('Failed to connect in time.', timeout));
      });
    }

    await FlutterBlue.instance._channel
        .invokeMethod('connect', request.writeToBuffer());

    return completer.future;
  }

  /// Cancels connection to the Bluetooth Device
  Future<void> disconnect() async {
    final completer = Completer();
    state.firstWhere((s) => s == BluetoothDeviceState.disconnected).then((_) {
      completer.complete();
    });
    FlutterBlue.instance._channel.invokeMethod('disconnect', id.toString());
    return completer.future;
  }

  BehaviorSubject<List<BluetoothService>> _services =
      BehaviorSubject.seeded([]);

  /// Discovers services offered by the remote device as well as their characteristics and descriptors
  Future<List<BluetoothService>> discoverServices() async {
    var response = FlutterBlue.instance._methodStream
        .where((m) => m.method == "DiscoverServicesResult")
        .map((m) => m.arguments)
        .map((buffer) => new protos.DiscoverServicesResult.fromBuffer(buffer))
        .where((p) => p.remoteId == id.toString())
        .map((p) => p.services)
        .map((s) => s.map((p) => new BluetoothService.fromProto(p)).toList())
        .first
        .then((list) {
      _services.add(list);
      _isDiscoveringServices.add(false);
      return list;
    });

    await FlutterBlue.instance._channel
        .invokeMethod('discoverServices', id.toString());

    _isDiscoveringServices.add(true);

    return response;
  }

  /// Request mtu size, on iOS this doesn't actually request anything because iOS handles it automatically,
  /// however it will return the negotiated MTU size
  Future<int> requestMTUSize(int newMTUSize) async {
    var request = protos.RequestMTURequest.create()
      ..remoteId = id.toString()
      ..localMTUSize = newMTUSize;

    var response = FlutterBlue.instance._methodStream
        .where((m) => m.method == "RequestMTUResult")
        .map((m) => m.arguments)
        .map((buffer) => new protos.RequestMTUResult.fromBuffer(buffer))
        .where((p) => p.remoteId == id.toString())
        .map((p) => p.remoteMTUSize)
        .first;

    await FlutterBlue.instance._channel
        .invokeMethod('requestMTU', request.writeToBuffer());

    return response;
  }

  /// Returns a list of Bluetooth GATT services offered by the remote device
  /// This function requires that discoverServices has been completed for this device
  Stream<List<BluetoothService>> get services async* {
    yield await FlutterBlue.instance._channel
        .invokeMethod('services', id.toString())
        .then((buffer) =>
            new protos.DiscoverServicesResult.fromBuffer(buffer).services)
        .then((i) => i.map((s) => new BluetoothService.fromProto(s)).toList());
    yield* _services.stream;
  }

  /// The current connection state of the device
  Stream<BluetoothDeviceState> get state async* {
    yield await FlutterBlue.instance._channel
        .invokeMethod('deviceState', id.toString())
        .then((buffer) => new protos.DeviceStateResponse.fromBuffer(buffer))
        .then((p) => BluetoothDeviceState.values[p.state.value]);

    yield* FlutterBlue.instance._methodStream
        .where((m) => m.method == "DeviceState")
        .map((m) => m.arguments)
        .map((buffer) => new protos.DeviceStateResponse.fromBuffer(buffer))
        .where((p) => p.remoteId == id.toString())
        .map((p) => BluetoothDeviceState.values[p.state.value]);
  }

  /// Indicates whether the Bluetooth Device can send a write without response
  Future<bool> get canSendWriteWithoutResponse =>
      new Future.error(new UnimplementedError());

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is BluetoothDevice &&
          runtimeType == other.runtimeType &&
          id == other.id;

  @override
  int get hashCode => id.hashCode;
}

enum BluetoothDeviceType { unknown, classic, le, dual }

enum BluetoothDeviceState { disconnected, connecting, connected, disconnecting }
