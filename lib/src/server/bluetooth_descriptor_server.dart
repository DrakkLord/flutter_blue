part of flutter_blue;

class BluetoothDescriptorServer {
  final Guid uuid;
  final DeviceIdentifier deviceId;
  final Guid serviceUuid;
  final Guid characteristicUuid;

  BehaviorSubject<List<int>> _value;
  Stream<List<int>> get value => _value.stream;

  List<int> get lastValue => _value.value;

  BluetoothDescriptorServer.fromProto(protos.BluetoothDescriptor p)
      : uuid = new Guid(p.uuid),
        deviceId = new DeviceIdentifier(p.remoteId),
        serviceUuid = new Guid(p.serviceUuid),
        characteristicUuid = new Guid(p.characteristicUuid),
        _value = BehaviorSubject.seeded(p.value);

  protos.BluetoothDescriptor toProto() {
    final desc = protos.BluetoothDescriptor.create();

    desc.uuid = uuid.toString();
    desc.remoteId = deviceId.id;
    desc.serviceUuid = serviceUuid.toString();
    desc.characteristicUuid = characteristicUuid.toString();
    desc.value = _value.value;

    return desc;
  }

  BluetoothDescriptorServer.forServer(Guid uuid,
      { List<int> initialValue })
      : uuid = uuid,
        deviceId = DeviceIdentifier(''),
        serviceUuid = Guid.empty(),
        characteristicUuid = Guid.empty(),
        _value = BehaviorSubject.seeded(initialValue??<int>[]);

  /// Retrieves the value of a specified descriptor
  Future<List<int>> read() async {
    var request = protos.ReadDescriptorRequest.create()
      ..remoteId = deviceId.toString()
      ..descriptorUuid = uuid.toString()
      ..characteristicUuid = characteristicUuid.toString()
      ..serviceUuid = serviceUuid.toString();

    await FlutterBlue.instance._channel
        .invokeMethod('readDescriptor', request.writeToBuffer());

    return FlutterBlue.instance._methodStream
        .where((m) => m.method == "ReadDescriptorResponse")
        .map((m) => m.arguments)
        .map((buffer) => new protos.ReadDescriptorResponse.fromBuffer(buffer))
        .where((p) =>
    (p.request.remoteId == request.remoteId) &&
        (p.request.descriptorUuid == request.descriptorUuid) &&
        (p.request.characteristicUuid == request.characteristicUuid) &&
        (p.request.serviceUuid == request.serviceUuid))
        .map((d) => d.value)
        .first
        .then((d) {
      _value.add(d);
      return d;
    });
  }

  /// Writes the value of a descriptor
  Future<Null> write(List<int> value) async {
    var request = protos.WriteDescriptorRequest.create()
      ..remoteId = deviceId.toString()
      ..descriptorUuid = uuid.toString()
      ..characteristicUuid = characteristicUuid.toString()
      ..serviceUuid = serviceUuid.toString()
      ..value = value;

    await FlutterBlue.instance._channel
        .invokeMethod('writeDescriptor', request.writeToBuffer());

    return FlutterBlue.instance._methodStream
        .where((m) => m.method == "WriteDescriptorResponse")
        .map((m) => m.arguments)
        .map((buffer) => new protos.WriteDescriptorResponse.fromBuffer(buffer))
        .where((p) =>
    (p.request.remoteId == request.remoteId) &&
        (p.request.descriptorUuid == request.descriptorUuid) &&
        (p.request.characteristicUuid == request.characteristicUuid) &&
        (p.request.serviceUuid == request.serviceUuid))
        .first
        .then((w) => w.success)
        .then((success) => (!success)
        ? throw new Exception('Failed to write the descriptor')
        : null)
        .then((_) => _value.add(value))
        .then((_) => null);
  }
}
