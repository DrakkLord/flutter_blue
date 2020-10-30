
part of flutter_blue;

class BluetoothServiceServer {
  final Guid uuid;
  final DeviceIdentifier deviceId;
  final bool isPrimary;
  final List<BluetoothCharacteristicServer> characteristics;
  final List<BluetoothServiceServer> includedServices;

  BluetoothServiceServer.fromProto(protos.BluetoothService p)
      : uuid = new Guid(p.uuid),
        deviceId = new DeviceIdentifier(p.remoteId),
        isPrimary = p.isPrimary,
        characteristics = p.characteristics
            .map((c) => new BluetoothCharacteristicServer.fromProto(c))
            .toList(),
        includedServices = p.includedServices
            .map((s) => new BluetoothServiceServer.fromProto(s))
            .toList();

  protos.BluetoothService toProto() {
    final serviceRoot = protos.BluetoothService.create();

    serviceRoot.uuid = uuid.toString();
    serviceRoot.remoteId = deviceId.id;
    serviceRoot.isPrimary = isPrimary;

    for (final chrs in characteristics) {
      serviceRoot.characteristics.add(chrs.toProto());
    }

    for (final srvc in includedServices) {
      serviceRoot.includedServices.add(srvc.toProto());
    }

    return serviceRoot;
  }

  BluetoothServiceServer.forServer(Guid uuid,
      List<BluetoothCharacteristicServer> characteristics,
      { bool isPrimary = true,
        List<BluetoothServiceServer> includedServices })
      : uuid = uuid,
        deviceId = DeviceIdentifier(''),
        isPrimary = isPrimary,
        characteristics = <BluetoothCharacteristicServer>[]..addAll(characteristics??<BluetoothCharacteristicServer>[]),
        includedServices = <BluetoothServiceServer>[]..addAll(includedServices??<BluetoothServiceServer>[]);
}
