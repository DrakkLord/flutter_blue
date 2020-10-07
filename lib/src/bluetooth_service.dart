// Copyright 2017, Paul DeMarco.
// All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

part of flutter_blue;

class BluetoothService {
  final Guid uuid;
  final DeviceIdentifier deviceId;
  final bool isPrimary;
  final List<BluetoothCharacteristic> characteristics;
  final List<BluetoothService> includedServices;

  BluetoothService.fromProto(protos.BluetoothService p)
      : uuid = new Guid(p.uuid),
        deviceId = new DeviceIdentifier(p.remoteId),
        isPrimary = p.isPrimary,
        characteristics = p.characteristics
            .map((c) => new BluetoothCharacteristic.fromProto(c))
            .toList(),
        includedServices = p.includedServices
            .map((s) => new BluetoothService.fromProto(s))
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

  BluetoothService.forServer(Guid uuid,
                             List<BluetoothCharacteristic> characteristics,
                              { bool isPrimary = true,
                                List<BluetoothService> includedServices })
  : uuid = uuid,
        deviceId = DeviceIdentifier(''),
        isPrimary = isPrimary,
        characteristics = <BluetoothCharacteristic>[]..addAll(characteristics??<BluetoothCharacteristic>[]),
        includedServices = <BluetoothService>[]..addAll(includedServices??<BluetoothService>[]);
}
